import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useConversationsStore } from './conversations';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import {
  DEMO_CONVERSATION_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  askData,
  askStreamDone,
  askStreamMeta,
  callLog,
  conversationListItem,
  documentListItem,
  envelope,
  messageHistoryItem,
  problem,
  sseFrame,
} from '../../tests/fixtures';
import {
  errorFrame,
  notAStreamResponse,
  sseOpenResponse,
  sseResponse,
  stubFetch,
} from '../../tests/sseStub';
import { server } from '../../tests/server';

const INDEXED_ID = documentListItem(1).documentId;
const FAILED_ID = documentListItem(3).documentId;

function askFailure(status: number, code: string, detail: string, headers: Record<string, string> = {}) {
  server.use(
    mswHttp.post('/api/v1/conversations/:conversationId/ask', async ({ request }) => {
      callLog.askedKeys.push(request.headers.get('Idempotency-Key') ?? '');
      callLog.askedBodies.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json(
        problem({ status, code, detail, instance: '/api/v1/conversations/ask' }),
        { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
      );
    }),
  );
}

function createFailure(status: number, code: string, detail: string) {
  server.use(
    mswHttp.post('/api/v1/conversations', ({ request }) => {
      callLog.createdKeys.push(request.headers.get('Idempotency-Key') ?? '');
      return HttpResponse.json(
        problem({ status, code, detail, instance: '/api/v1/conversations' }),
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }),
  );
}

function askSuccessOnce() {
  let served = 0;
  server.use(
    mswHttp.post('/api/v1/conversations/:conversationId/ask', async ({ request }) => {
      callLog.askedKeys.push(request.headers.get('Idempotency-Key') ?? '');
      callLog.askedBodies.push((await request.json()) as Record<string, unknown>);
      served += 1;
      if (served === 1) {
        return HttpResponse.json(
          problem({ status: 409, code: 'AGT-CONFLICT-001', detail: '该会话正在处理另一个问题，请稍后重试。', instance: '/x' }),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }
      return HttpResponse.json(envelope(askData()), { status: 201 });
    }),
  );
}

async function openSession(store: ReturnType<typeof useConversationsStore>): Promise<void> {
  await store.openConversation(DEMO_CONVERSATION_ID);
}

describe('conversations store', () => {
  let conversations: ReturnType<typeof useConversationsStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    // store 现在流式优先；默认把流式应答伪造成「不是事件流」，让下面这些既有断言继续跑在非流式通道上。
    // 需要观察流式分支的用例各自再 `stubFetch` 覆盖本桩。
    stubFetch(() => notAStreamResponse());
    conversations = useConversationsStore();
  });

  it('pages the session list with the cursor the server issued', async () => {
    await conversations.loadListFirst();

    expect(conversations.items.map((item) => item.conversationId)).toEqual([
      conversationListItem(1).conversationId,
      conversationListItem(2).conversationId,
    ]);
    expect(conversations.nextCursor).toBe('PAGE2');
    expect(conversations.canLoadListMore).toBe(true);

    await conversations.loadListMore();
    expect(conversations.items).toHaveLength(3);
    expect(conversations.items[2]?.lastMessageAt).toBeNull();
    expect(conversations.canLoadListMore).toBe(false);
  });

  it('falls back to the first page when the list cursor is rejected', async () => {
    let firstPages = 0;
    server.use(
      mswHttp.get('/api/v1/conversations', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor === null) {
          firstPages += 1;
          return HttpResponse.json(
            envelope({
              items: [conversationListItem(1)],
              nextCursor: firstPages === 1 ? 'STALE' : null,
              limit: 20,
            }),
          );
        }
        return HttpResponse.json(
          problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法。', instance: '/api/v1/conversations' }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    await conversations.loadListFirst();
    await conversations.loadListMore();

    expect(firstPages).toBe(2);
    expect(conversations.nextCursor).toBeNull();
    expect(conversations.detail).toBe('会话翻页游标已失效，列表已回到第一页。');
  });

  it('reverses the history page into display order and prepends older pages', async () => {
    await openSession(conversations);

    expect(conversations.messages.map((row) => row.key)).toEqual([
      messageHistoryItem(1, 'user').messageId,
      messageHistoryItem(2, 'assistant').messageId,
    ]);
    expect(conversations.messages[0]?.content).toBe('问题 1');
    expect(conversations.canLoadHistoryMore).toBe(true);

    await conversations.loadHistoryMore();

    expect(conversations.messages.map((row) => row.key)).toEqual([
      messageHistoryItem(0, 'user').messageId,
      messageHistoryItem(1, 'user').messageId,
      messageHistoryItem(2, 'assistant').messageId,
    ]);
    expect(conversations.historyCursor).toBeNull();
  });

  it('takes the session identity from the server and never reuses the create key after success', async () => {
    await conversations.provisionConversation();

    expect(conversations.currentId).toBe(DEMO_CONVERSATION_ID);
    expect(callLog.createdKeys).toHaveLength(1);
    expect(callLog.createdKeys[0]).toMatch(/^[A-Za-z0-9._:-]{1,64}$/);
    expect(conversations.createKey).toBeNull();

    await conversations.provisionConversation();
    expect(callLog.createdKeys).toHaveLength(2);
    expect(callLog.createdKeys[0]).not.toBe(callLog.createdKeys[1]);
  });

  it('keeps the create key while creation keeps failing so the server can replay its own result', async () => {
    createFailure(409, 'AGT-CONFLICT-002', '同一幂等键已用于不同的会话创建请求。');

    await conversations.provisionConversation();
    await conversations.provisionConversation();

    expect(callLog.createdKeys).toHaveLength(2);
    expect(callLog.createdKeys[0]).toBe(callLog.createdKeys[1]);
    expect(conversations.code).toBe('AGT-CONFLICT-002');
  });

  it('drops the held create key as soon as the title changes', async () => {
    createFailure(500, 'COM-INTERNAL-001', '服务暂不可用，请稍后重试。');

    await conversations.provisionConversation();
    const held = conversations.createKey;
    conversations.updateTitle('线性代数复习');

    expect(held).not.toBeNull();
    expect(conversations.createKey).toBeNull();
  });

  it('reuses the ask key across a manual retry and releases it once the answer lands', async () => {
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');
    askSuccessOnce();

    await conversations.submitQuestion();
    expect(conversations.pendingKey).not.toBeNull();
    expect(conversations.detail).toBe('该会话正在处理另一个问题，请稍后重试。');
    expect(conversations.messages).toHaveLength(2);

    await conversations.submitQuestion();

    expect(callLog.askedKeys).toHaveLength(2);
    expect(callLog.askedKeys[0]).toBe(callLog.askedKeys[1]);
    expect(conversations.pendingKey).toBeNull();
    expect(conversations.question).toBe('');
    expect(callLog.askedBodies[0]).toEqual({ question: '请解释梯度下降。', documentIds: [INDEXED_ID] });
  });

  it('mints a fresh key when the question text changes after a failure', async () => {
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('第一版问题');
    askFailure(409, 'AGT-CONFLICT-001', '该会话正在处理另一个问题，请稍后重试。');

    await conversations.submitQuestion();
    conversations.updateQuestion('改过的问题');
    await conversations.submitQuestion();

    expect(callLog.askedKeys).toHaveLength(2);
    expect(callLog.askedKeys[0]).not.toBe(callLog.askedKeys[1]);
  });

  it('appends the pair locally with the envelope trace and keeps the governance fields on the answer', async () => {
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    await conversations.submitQuestion();

    const rows = conversations.messages.slice(-2);
    expect(rows[0]?.key).toBe(`local:${callLog.askedKeys[0]}`);
    expect(rows[1]?.key).toBe(askData().messageId);
    expect(rows.map((row) => row.traceId)).toEqual([DEMO_TRACE_ID, DEMO_TRACE_ID]);
    expect(rows[0]?.governance).toBeNull();
    expect(rows[1]?.governance?.budgetStatus).toBe('OK');
    // 用户行没有服务端落库时间，助手行的时间来自 AskData.createdAt。
    expect(rows[0]?.createdAt).toBeNull();
    expect(rows[1]?.createdAt).toBe(askData().createdAt);
  });

  it('returns the session list to the first page after a question lands', async () => {
    const cursors: (string | null)[] = [];
    server.use(
      mswHttp.get('/api/v1/conversations', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        cursors.push(cursor);
        return HttpResponse.json(
          envelope(
            cursor === null
              ? { items: [conversationListItem(1)], nextCursor: 'PAGE2', limit: 20 }
              : { items: [conversationListItem(3)], nextCursor: null, limit: 20 },
          ),
        );
      }),
    );
    await conversations.loadListFirst();
    await conversations.loadListMore();
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    await conversations.submitQuestion();

    // 8A-2 边界：last_message_at 变了，第二页的游标可能漏读，回到第一页而不是接着翻页。
    expect(cursors).toEqual([null, 'PAGE2', null]);
  });

  it('writes nothing locally and keeps the draft when the answer is rejected', async () => {
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');
    askFailure(504, 'AGT-UPSTREAM-002', 'AI 服务响应超时，请稍后重试。');

    await conversations.submitQuestion();

    expect(conversations.messages).toHaveLength(2);
    expect(conversations.question).toBe('请解释梯度下降。');
    expect(conversations.code).toBe('AGT-UPSTREAM-002');
    expect(conversations.traceId).toBe(DEMO_TRACE_ID);
  });

  it('surfaces the server-suggested wait without inventing one', async () => {
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');
    askFailure(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '30' });

    await conversations.submitQuestion();

    expect(conversations.retryAfterSeconds).toBe(30);
  });

  it('refuses locally when nothing is selected or the conversation is missing', async () => {
    await conversations.loadPickerFirst();
    conversations.updateQuestion('请解释梯度下降。');
    await conversations.submitQuestion();

    expect(conversations.detail).toBe('请先新建或选择一个会话。');
    expect(conversations.code).toBeNull();

    await openSession(conversations);
    await conversations.submitQuestion();
    expect(conversations.detail).toBe('请至少勾选一份已可检索的文档。');
    expect(callLog.askedKeys).toHaveLength(0);
  });

  it('refuses to send a selection the retrievable list no longer contains', async () => {
    await openSession(conversations);
    await conversations.loadPickerFirst();
    await conversations.loadPickerMore();
    conversations.toggleDocument(FAILED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    await conversations.submitQuestion();

    expect(conversations.detail).toBe('有文档已不可检索或不在当前列表里，请重新勾选。');
    expect(callLog.askedKeys).toHaveLength(0);
  });

  it('caps the selection at the contract ceiling of twenty documents', async () => {
    const ids = Array.from({ length: 21 }, (_, index) => `00000000-0000-0000-0000-0000000000f${index}`);
    const [firstId, ...restIds] = ids;
    server.use(
      mswHttp.get('/api/v1/documents', () =>
        HttpResponse.json(
          envelope({
            items: ids.map((id) => ({ ...documentListItem(1), documentId: id })),
            nextCursor: null,
            limit: 20,
          }),
        ),
      ),
    );
    await conversations.loadPickerFirst();

    for (const id of [firstId, ...restIds]) {
      conversations.toggleDocument(id);
    }

    expect(conversations.selected).toHaveLength(20);
    expect(conversations.detail).toBe('一次最多勾选 20 份文档。');

    conversations.toggleDocument(firstId);
    expect(conversations.selected).toHaveLength(19);
  });

  it('drops the pending key when another conversation is opened', async () => {
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');
    askFailure(503, 'AGT-UPSTREAM-001', 'AI 能力暂不可用，请稍后重试。');

    await conversations.submitQuestion();
    await conversations.openConversation(conversationListItem(2).conversationId);

    expect(conversations.pendingKey).toBeNull();
    expect(conversations.currentId).toBe(conversationListItem(2).conversationId);
    expect(conversations.messages).toHaveLength(2);
  });

  it('keeps the history page intact when a message cursor went stale', async () => {
    let firstPages = 0;
    server.use(
      mswHttp.get('/api/v1/conversations/:conversationId/messages', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor === null) {
          firstPages += 1;
          return HttpResponse.json(
            envelope({
              conversationId: DEMO_CONVERSATION_ID,
              items: [messageHistoryItem(1, 'user')],
              nextCursor: firstPages === 1 ? 'STALE' : null,
              limit: 20,
            }),
          );
        }
        return HttpResponse.json(
          problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法。', instance: '/api/v1/conversations/messages' }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    await openSession(conversations);
    await conversations.loadHistoryMore();

    expect(firstPages).toBe(2);
    expect(conversations.messages.map((row) => row.key)).toEqual([messageHistoryItem(1, 'user').messageId]);
    expect(conversations.detail).toBe('消息翻页游标已失效，已回到最新一页。');
    expect(conversations.canLoadHistoryMore).toBe(false);
  });

  it('falls back to the first page when the document picker cursor is rejected', async () => {
    let firstPages = 0;
    server.use(
      mswHttp.get('/api/v1/documents', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor === null) {
          firstPages += 1;
          return HttpResponse.json(
            envelope({
              items: [documentListItem(1)],
              nextCursor: firstPages === 1 ? 'STALE' : null,
              limit: 20,
            }),
          );
        }
        return HttpResponse.json(
          problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法。', instance: '/api/v1/documents' }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    await conversations.loadPickerFirst();
    await conversations.loadPickerMore();

    expect(firstPages).toBe(2);
    expect(conversations.pickerCursor).toBeNull();
    expect(conversations.detail).toBe('可选文档翻页游标已失效，已回到第一页。');
    expect(conversations.canLoadPickerMore).toBe(false);
  });

  it('streams the frames and lets done replace the assistant row wholesale', async () => {
    const streamingRowId = '00000000-0000-0000-0000-0000000000ee';
    const calls = stubFetch(() =>
      sseResponse([
        sseFrame('meta', askStreamMeta({ messageId: streamingRowId })),
        sseFrame('delta', { text: '梯度下降' }),
        sseFrame('delta', { text: '沿损失函数负梯度方向更新参数。' }),
        sseFrame('done', askStreamDone()),
      ]),
    );
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    await conversations.submitQuestion();

    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({
      path: `/api/v1/conversations/${DEMO_CONVERSATION_ID}/ask:stream`,
      method: 'POST',
      accept: 'text/event-stream',
      authorization: `Bearer ${DEMO_TOKEN}`,
      body: { question: '请解释梯度下降。', documentIds: [INDEXED_ID] },
    });
    const rows = conversations.messages.slice(-2);
    expect(rows[0]?.key).toBe(`local:${calls[0]?.key}`);
    // done 是字段真源：行键重定到服务端 messageId，正文用服务端定稿答案而不是本地拼接结果。
    expect(rows[1]?.key).toBe(askData().messageId);
    expect(rows[1]?.content).toBe(askData().answer);
    expect(rows[1]?.tokenCount).toBe(askData().tokenUsed);
    expect(rows[1]?.createdAt).toBe(askData().createdAt);
    expect(rows[1]?.governance?.budgetStatus).toBe('OK');
    expect(rows.map((row) => row.traceId)).toEqual([DEMO_TRACE_ID, DEMO_TRACE_ID]);
    expect(conversations.streaming).toBe(false);
    expect(conversations.streamKey).toBeNull();
    expect(conversations.question).toBe('');
    expect(conversations.pendingKey).toBeNull();
  });

  it('renders each delta the moment it arrives and drops the half pair when generation is stopped', async () => {
    const calls = stubFetch((call) =>
      sseOpenResponse(
        [
          sseFrame('meta', askStreamMeta()),
          sseFrame('delta', { text: '第一段' }),
          ': keep-alive\r\n',
          sseFrame('delta', { text: '第二段' }),
        ],
        call.signal,
      ),
    );
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    const pending = conversations.submitQuestion();
    await vi.waitFor(() => expect(conversations.messages.at(-1)?.content).toBe('第一段第二段'));

    expect(conversations.streaming).toBe(true);
    expect(conversations.streamKey).toBe(askData().messageId);
    // 治理徽章只在 done 之后出现：拼接中的半行不能冒充「已有治理结论」。
    expect(conversations.messages.at(-1)?.governance).toBeNull();

    conversations.abortStream();
    await pending;

    expect(conversations.messages).toHaveLength(2);
    expect(conversations.streaming).toBe(false);
    expect(conversations.detail).toBeNull();
    // 主动断开不是失败：草稿与同一条幂等键都留着，由用户决定要不要再问一次。
    expect(conversations.question).toBe('请解释梯度下降。');
    expect(conversations.pendingKey).toBe(calls[0]?.key);
  });

  it('falls back to the non-streaming endpoint with the very same key', async () => {
    const calls = stubFetch(() => notAStreamResponse());
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    await conversations.submitQuestion();

    // 回退不是重试：同一次提问在两个端点上共用一个键，服务端不会因此看到两次问题。
    expect(callLog.askedKeys).toEqual([calls[0]?.key]);
    expect(callLog.askedBodies[0]).toEqual({ question: '请解释梯度下降。', documentIds: [INDEXED_ID] });
    expect(conversations.messages).toHaveLength(4);
    expect(conversations.messages.at(-1)?.content).toBe(askData().answer);
    expect(conversations.streaming).toBe(false);
  });

  it('reports a mid-stream rejection through the same failure path and keeps the key', async () => {
    const calls = stubFetch(() =>
      sseResponse([
        sseFrame('meta', askStreamMeta()),
        sseFrame('delta', { text: '已经生成的部分。' }),
        errorFrame(503, 'AGT-UPSTREAM-001', 'AI 能力暂不可用，请稍后重试。'),
      ]),
    );
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    await conversations.submitQuestion();

    // 失败就没有「已经说出口的一半答案」可留：本地两行整体丢弃，等重试或历史回填。
    expect(conversations.messages).toHaveLength(2);
    expect(conversations.code).toBe('AGT-UPSTREAM-001');
    expect(conversations.traceId).toBe(DEMO_TRACE_ID);
    expect(conversations.question).toBe('请解释梯度下降。');
    expect(conversations.pendingKey).toBe(calls[0]?.key);
  });

  it('drops a non-streaming answer that is still in flight when the view is left', async () => {
    let asked = false;
    let settle: (value: Response) => void = () => undefined;
    server.use(
      mswHttp.post(
        '/api/v1/conversations/:conversationId/ask',
        () => new Promise<Response>((resolve) => {
          settle = resolve;
          asked = true;
        }),
      ),
    );
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    const pending = conversations.submitQuestion();
    await vi.waitFor(() => expect(asked).toBe(true));

    // 模拟卸载：abortStream() 现在会取消同步请求并让归属代号失效。
    conversations.abortStream();
    settle(HttpResponse.json(envelope(askData()), { status: 201 }));
    await pending;

    // 无论请求是被 abort 还是稍后才返回，答案都不能落进已经不属于它的 messages，也不当成失败。
    expect(conversations.messages).toHaveLength(2);
    expect(conversations.messages.at(-1)?.content).not.toBe(askData().answer);
    expect(conversations.detail).toBeNull();
    expect(conversations.question).toBe('请解释梯度下降。');
  });

  it('abandons the in-flight generation when another conversation is opened', async () => {
    stubFetch((call) => sseOpenResponse([sseFrame('meta', askStreamMeta({ messageId: askData().messageId }))], call.signal));
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');

    const pending = conversations.submitQuestion();
    await vi.waitFor(() => expect(conversations.messages).toHaveLength(4));

    await conversations.openConversation(conversationListItem(2).conversationId);
    await pending;

    expect(conversations.currentId).toBe(conversationListItem(2).conversationId);
    expect(conversations.streaming).toBe(false);
    // 历史页整页替换后不可能残留被放弃的半行，键也随会话作用域一起清掉。
    expect(conversations.messages).toHaveLength(2);
    expect(conversations.pendingKey).toBeNull();
  });
});

describe('approval resume (human-in-the-loop)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    useAuthStore().token = DEMO_TOKEN;
    stubFetch(() => notAStreamResponse());
  });

  it('records pendingApproval and resumes the same checkpoint with resumeApproved', async () => {
    const conversations = useConversationsStore();
    await openSession(conversations);
    await conversations.loadPickerFirst();
    conversations.toggleDocument(INDEXED_ID);
    conversations.updateQuestion('请解释梯度下降。');
    server.use(
      mswHttp.post('/api/v1/conversations/:conversationId/ask', async ({ request }) => {
        const first = callLog.askedBodies.length === 0;
        callLog.askedBodies.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json(
          envelope(
            first
              ? {
                  ...askData(),
                  budgetStatus: 'PENDING_APPROVAL',
                  pendingApproval: { tool: 'mcp__srv__drop', input: '{"id":1}' },
                }
              : askData(),
          ),
          { status: 201 },
        );
      }),
    );
    await conversations.submitQuestion();
    expect(callLog.askedBodies).toHaveLength(1);
    expect(conversations.pendingApproval).not.toBeNull();
    expect(conversations.pendingApproval?.approval.tool).toBe('mcp__srv__drop');

    await conversations.resumeApproval(true);
    expect(callLog.askedBodies[1]).toEqual({
      question: '请解释梯度下降。',
      documentIds: [INDEXED_ID],
      resumeApproved: true,
    });
    expect(conversations.pendingApproval).toBeNull();
  });
});
