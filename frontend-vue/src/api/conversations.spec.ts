import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import type { AskStreamEvent } from '../types/conversations';
import {
  askConversation,
  askConversationStream,
  createConversation,
  listConversationMessages,
  listConversations,
} from './conversations';
import { ApiError, STREAM_PROTOCOL_ERROR_CODE } from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import {
  DEMO_CONVERSATION_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  askData,
  askStreamDone,
  askStreamFrames,
  askStreamMeta,
  conversationListItem,
  envelope,
  messageHistoryItem,
  problem,
  sseFrame,
} from '../../tests/fixtures';
import { errorFrame, sseOpenResponse, sseResponse, stubFetch } from '../../tests/sseStub';
import { server } from '../../tests/server';

interface Captured {
  method: string;
  path: string;
  search: string;
  idempotencyKey: string | null;
  body: unknown;
}

const captured: Captured[] = [];

function record(request: Request, body: unknown): void {
  const url = new URL(request.url);
  captured.push({
    method: request.method,
    path: url.pathname,
    search: url.search,
    idempotencyKey: request.headers.get('Idempotency-Key'),
    body,
  });
}

async function expectApiError(action: Promise<unknown>): Promise<ApiError> {
  try {
    await action;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('预期抛出 ApiError，但调用成功了');
}

function problemResponse(status: number, code: string, detail: string, headers: Record<string, string> = {}) {
  return HttpResponse.json(
    problem({ status, code, detail, instance: '/api/v1/conversations/ask' }),
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );
}

describe('conversations api', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    captured.length = 0;
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('sends the cursor and limit exactly as asked and never a key on GET', async () => {
    server.use(
      mswHttp.get('/api/v1/conversations', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope({ items: [conversationListItem(1)], nextCursor: null, limit: 20 }));
      }),
    );

    const page = await listConversations({ limit: 20 });
    await listConversations({ cursor: 'PAGE2', limit: 20 });

    expect(page.items).toHaveLength(1);
    expect(captured.map((entry) => entry.search)).toEqual(['?limit=20', '?cursor=PAGE2&limit=20']);
    expect(captured.every((entry) => entry.idempotencyKey === null)).toBe(true);
  });

  it('creates the session with the caller-chosen key and only the fields the contract allows', async () => {
    server.use(
      mswHttp.post('/api/v1/conversations', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(
          envelope({
            conversationId: DEMO_CONVERSATION_ID,
            title: '概率论',
            documentScopeIds: [],
            idempotentReplay: false,
            createdAt: '2026-10-26T09:00:00Z',
          }),
          { status: 201 },
        );
      }),
    );

    const created = await createConversation({ title: '概率论' }, 'CREATE-KEY');

    expect(captured[0]).toMatchObject({
      method: 'POST',
      path: '/api/v1/conversations',
      idempotencyKey: 'CREATE-KEY',
      body: { title: '概率论' },
    });
    expect(created.conversationId).toBe(DEMO_CONVERSATION_ID);
  });

  it('posts the question under the conversation path and hands back the envelope traceId', async () => {
    server.use(
      mswHttp.post('/api/v1/conversations/:conversationId/ask', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(envelope(askData()), { status: 201 });
      }),
    );

    const result = await askConversation(
      DEMO_CONVERSATION_ID,
      { question: '请解释梯度下降。', documentIds: ['00000000-0000-0000-0000-000000000001'] },
      'ASK-KEY',
    );

    expect(captured[0]).toMatchObject({
      method: 'POST',
      path: `/api/v1/conversations/${DEMO_CONVERSATION_ID}/ask`,
      idempotencyKey: 'ASK-KEY',
      body: { question: '请解释梯度下降。', documentIds: ['00000000-0000-0000-0000-000000000001'] },
    });
    // AskData 里没有 traceId，只有信封有；本地消息行要的就是它。
    expect(result.traceId).toBe(DEMO_TRACE_ID);
    expect(result.data.budgetStatus).toBe('OK');
  });

  it('reads the message page without a key and keeps the conversation in the path', async () => {
    server.use(
      mswHttp.get('/api/v1/conversations/:conversationId/messages', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(
          envelope({
            conversationId: DEMO_CONVERSATION_ID,
            items: [messageHistoryItem(2, 'assistant')],
            nextCursor: null,
            limit: 20,
          }),
        );
      }),
    );

    const page = await listConversationMessages(DEMO_CONVERSATION_ID, { limit: 20 });

    expect(captured[0]).toMatchObject({
      method: 'GET',
      path: `/api/v1/conversations/${DEMO_CONVERSATION_ID}/messages`,
      idempotencyKey: null,
    });
    expect(page.items[0]?.role).toBe('assistant');
  });

  it('keeps the governance error code and trace on a rejected question', async () => {
    server.use(
      mswHttp.post('/api/v1/conversations/:conversationId/ask', () =>
        problemResponse(503, 'AGT-UPSTREAM-003', 'AI 功能已暂停。'),
      ),
    );

    const error = await expectApiError(askConversation(DEMO_CONVERSATION_ID, { question: 'q', documentIds: ['d'] }, 'K'));

    expect(error.status).toBe(503);
    expect(error.code).toBe('AGT-UPSTREAM-003');
    expect(error.detail).toBe('AI 功能已暂停。');
    expect(error.retryAfterSeconds).toBeNull();
  });

  it('carries the contract-declared Retry-After seconds only when the server issued them', async () => {
    server.use(
      mswHttp.post('/api/v1/conversations/:conversationId/ask', () =>
        problemResponse(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '30' }),
      ),
    );
    const limited = await expectApiError(askConversation(DEMO_CONVERSATION_ID, { question: 'q', documentIds: ['d'] }, 'K'));

    server.use(
      mswHttp.post('/api/v1/conversations/:conversationId/ask', () =>
        problemResponse(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': 'not-a-number' }),
      ),
    );
    const vague = await expectApiError(askConversation(DEMO_CONVERSATION_ID, { question: 'q', documentIds: ['d'] }, 'K'));

    expect(limited.retryAfterSeconds).toBe(30);
    // 非整秒形态契约里没有，前端不自造等待时间。
    expect(vague.retryAfterSeconds).toBeNull();
  });

  const askBody = { question: '请解释梯度下降。', documentIds: ['00000000-0000-0000-0000-000000000001'] };

  it('parses the contract frames into typed events and keeps the conversation in the path', async () => {
    const calls = stubFetch(() => sseResponse(askStreamFrames(['梯度下降', '沿负梯度方向。'])));

    const events = await collectStream(
      askConversationStream(DEMO_CONVERSATION_ID, askBody, 'ASK-KEY'),
    );

    expect(calls[0]?.path).toBe(`/api/v1/conversations/${DEMO_CONVERSATION_ID}/ask:stream`);
    expect(events.map((event) => event.kind)).toEqual(['meta', 'delta', 'delta', 'done']);
    expect(events[0]).toEqual({ kind: 'meta', payload: askStreamMeta() });
    expect(events[1]).toEqual({ kind: 'delta', payload: { text: '梯度下降' } });
    // done 的载荷按契约就是 AskData + traceId，一个字段都不许在传输层被改写。
    expect(events[3]).toEqual({ kind: 'done', payload: askStreamDone() });
  });

  it('hands an error frame to the caller as the failure it describes', async () => {
    const calls = stubFetch(() =>
      sseResponse([
        sseFrame('meta', askStreamMeta()),
        sseFrame('delta', { text: '已经生成的部分。' }),
        errorFrame(503, 'AGT-UPSTREAM-003', 'AI 功能已暂停。'),
      ]),
    );

    const error = await expectApiError(collectStream(askConversationStream(DEMO_CONVERSATION_ID, askBody, 'K')));

    // error 帧不进事件联合：流中失败与非 2xx 拒绝在调用方那里必须是同一个判据。
    expect(error.status).toBe(503);
    expect(error.code).toBe('AGT-UPSTREAM-003');
    expect(error.traceId).toBe(DEMO_TRACE_ID);
    expect(calls).toHaveLength(1);
  });

  it('ends without a protocol error when the caller aborts before the terminal frame', async () => {
    const controller = new AbortController();
    stubFetch((call) =>
      sseOpenResponse([sseFrame('meta', askStreamMeta()), sseFrame('delta', { text: '第一段' })], call.signal),
    );
    const kinds: string[] = [];

    const draining = (async () => {
      for await (const event of askConversationStream(DEMO_CONVERSATION_ID, askBody, 'K', controller.signal)) {
        kinds.push(event.kind);
      }
      return kinds;
    })();
    await vi.waitFor(() => expect(kinds).toEqual(['meta', 'delta']));

    controller.abort();

    // 「没有终止帧」只在非主动断开时才是协议违例，否则用户按停止就会看到一条错误。
    await expect(draining).resolves.toEqual(['meta', 'delta']);
  });

  const violations: { name: string; frames: string[]; detail: string }[] = [
    {
      name: 'rejects a stream that never opened with meta',
      frames: [sseFrame('delta', { text: 'x' }), sseFrame('done', askStreamDone())],
      detail: '首个事件帧不是 meta',
    },
    {
      name: 'rejects a second meta frame',
      frames: [sseFrame('meta', askStreamMeta()), sseFrame('meta', askStreamMeta())],
      detail: 'meta 帧出现了第二次',
    },
    {
      name: 'rejects a meta frame missing a required field',
      frames: [sseFrame('meta', { conversationId: DEMO_CONVERSATION_ID, messageId: 'm', cacheHit: false })],
      detail: 'meta 帧缺少 conversationId / messageId / traceId / cacheHit',
    },
    {
      name: 'rejects a delta frame without string text',
      frames: [sseFrame('meta', askStreamMeta()), sseFrame('delta', { text: 12 })],
      detail: 'delta 帧缺少字符串字段 text',
    },
    {
      name: 'rejects a done frame without its trace',
      // AskData 本身没有 traceId：契约上 `AskStreamDone.data` 是 AskData allOf traceId，缺它就是帧不合规。
      frames: [sseFrame('meta', askStreamMeta()), sseFrame('done', askData())],
      detail: 'done 帧缺少 messageId / answer / traceId',
    },
    {
      name: 'rejects an event name outside the contract',
      frames: [sseFrame('meta', askStreamMeta()), sseFrame('ping', {}), sseFrame('done', askStreamDone())],
      detail: '契约之外的事件名「ping」',
    },
    {
      name: 'rejects anything after a terminal frame',
      frames: [sseFrame('meta', askStreamMeta()), sseFrame('done', askStreamDone()), sseFrame('delta', { text: 'x' })],
      detail: '终止帧之后仍继续收到事件「delta」',
    },
    {
      name: 'rejects a payload that is not JSON',
      frames: [sseFrame('meta', 'not-json')],
      detail: '不是合法 JSON',
    },
    {
      name: 'rejects a stream that ends without a terminal frame',
      frames: [sseFrame('meta', askStreamMeta()), sseFrame('delta', { text: '第一段' })],
      detail: '事件流在没有终止帧的情况下结束',
    },
    {
      name: 'rejects an error frame that is not a ProblemDetail',
      // error 帧排在 meta 之后，否则会先撞上「首帧不是 meta」这条判定。
      frames: [sseFrame('meta', askStreamMeta()), sseFrame('error', { detail: '只有一句说明。' })],
      detail: 'error 帧的载荷不是 ProblemDetail 形态',
    },
  ];

  for (const violation of violations) {
    it(violation.name, async () => {
      stubFetch(() => sseResponse(violation.frames));

      const error = await expectApiError(collectStream(askConversationStream(DEMO_CONVERSATION_ID, askBody, 'K')));

      // 502 与契约 ProtocolViolation 同判据；状态码不能是 0，否则会被读成「连不上服务」。
      expect(error.status).toBe(502);
      expect(error.code).toBe(STREAM_PROTOCOL_ERROR_CODE);
      expect(error.isNetworkFailure).toBe(false);
      expect(error.detail).toContain(violation.detail);
    });
  }
});

async function collectStream(iterable: AsyncGenerator<AskStreamEvent, void, void>): Promise<AskStreamEvent[]> {
  const events: AskStreamEvent[] = [];
  for await (const event of iterable) {
    events.push(event);
  }
  return events;
}
