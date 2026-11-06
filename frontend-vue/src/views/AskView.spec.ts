import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';
import AskView from './AskView.vue';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { useConversationsStore } from '../stores/conversations';
import {
  DEMO_CONVERSATION_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  askData,
  askStreamFrames,
  askStreamMeta,
  callLog,
  citation,
  conversationListItem,
  documentListItem,
  envelope,
  problem,
  sseFrame,
} from '../../tests/fixtures';
import { notAStreamResponse, sseOpenResponse, sseResponse, stubFetch } from '../../tests/sseStub';
import { server } from '../../tests/server';

const INDEXED_ID = documentListItem(1).documentId;

async function mountView() {
  const wrapper = mount(AskView);
  const store = useConversationsStore();
  await vi.waitFor(() => expect(store.listLoaded && store.pickerLoaded).toBe(true));
  return { wrapper, store };
}

async function openSession(wrapper: VueWrapper, index = 0): Promise<void> {
  const sessions = wrapper.findAll('.ask__sessions button');
  await sessions[index]?.trigger('click');
  await vi.waitFor(() => expect(useConversationsStore().historyLoaded).toBe(true));
}

function askAnswers(overrides: Record<string, unknown>) {
  server.use(
    mswHttp.post('/api/v1/conversations/:conversationId/ask', () =>
      HttpResponse.json(envelope(askData(overrides)), { status: 201 }),
    ),
  );
}

function askFailures(status: number, code: string, detail: string, headers: Record<string, string> = {}) {
  server.use(
    mswHttp.post('/api/v1/conversations/:conversationId/ask', () =>
      HttpResponse.json(problem({ status, code, detail, instance: '/api/v1/conversations/ask' }), {
        status,
        headers: { 'Content-Type': 'application/problem+json', ...headers },
      }),
    ),
  );
}

function conflictThenSuccess() {
  let served = 0;
  server.use(
    mswHttp.post('/api/v1/conversations/:conversationId/ask', async ({ request }) => {
      callLog.askedKeys.push(request.headers.get('Idempotency-Key') ?? '');
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

describe('AskView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    // 问答页现在流式优先；默认伪造成「不是事件流」，让下面这些既有断言继续跑在同键回退的非流式通道上。
    stubFetch(() => notAStreamResponse());
  });

  it('lists sessions on entry and opens the one that was clicked', async () => {
    const { wrapper } = await mountView();
    const sessions = wrapper.findAll('.ask__sessions button');
    const rows = wrapper.findAll('.ask__sessions li');

    expect(sessions).toHaveLength(2);
    expect(sessions[0]?.text()).toContain('会话 1');
    expect(rows[0]?.text()).toContain(
      new Date('2026-10-20T10:17:12Z').toLocaleString('zh-CN', { hour12: false }),
    );

    await openSession(wrapper, 1);

    expect(useConversationsStore().currentId).toBe(conversationListItem(2).conversationId);
    expect(wrapper.findAll('.ask__message')).toHaveLength(2);
    expect(wrapper.find('.ask__current').text()).toBe('当前');
  });

  it('marks history rows as 历史消息 and keeps only the fields the history contract returns', async () => {
    const { wrapper } = await mountView();
    await openSession(wrapper);

    const badge = wrapper.findAll('.governance')[0];
    expect(badge?.text()).toContain('历史消息');
    expect(badge?.text()).toContain('治理字段只在提问当次返回');
    expect(badge?.text()).toContain('消息 token 48');
    expect(badge?.text()).toContain(`trace ${DEMO_TRACE_ID}`);
    expect(badge?.text()).not.toContain('置信度');
  });

  it('creates a session through the server and clears the draft title afterwards', async () => {
    const { wrapper, store } = await mountView();
    await wrapper.get('[data-testid="title"]').setValue('线性代数复习');

    await wrapper.findAll('button').find((button) => button.text() === '新建会话')?.trigger('click');
    // 历史页是在 currentId 落地之后异步补取的，只等 currentId 会读到空消息列表。
    await vi.waitFor(() => {
      expect(store.currentId).toBe(DEMO_CONVERSATION_ID);
      expect(store.messages).toHaveLength(2);
    });
    await nextTick();

    expect(callLog.createdKeys).toHaveLength(1);
    expect((wrapper.get('[data-testid="title"]').element as HTMLInputElement).value).toBe('');
  });

  it('sends the question with only the checked documents and shows the governance badge', async () => {
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    await wrapper.findAll('.ask__docs input[type="checkbox"]')[0]?.setValue(true);
    await wrapper.get('[data-testid="question"]').setValue('请解释梯度下降。');

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.messages).toHaveLength(4));

    expect(callLog.askedBodies[0]).toEqual({ question: '请解释梯度下降。', documentIds: [INDEXED_ID] });
    const badge = wrapper.findAll('.governance')[1]?.text();
    expect(badge).toContain('正常回答');
    expect(badge).toContain('置信度 0.87');
    expect(badge).toContain('token 1260');
    expect(badge).toContain('工具调用 1');
    expect(badge).toContain('轮次 2');
    expect(badge).toContain('重新检索');
    expect(badge).not.toContain('幂等重放');
    expect(wrapper.text()).toContain('片段 1');
    expect(wrapper.text()).toContain('note-1.pdf');
    expect(wrapper.text()).toContain('相关度 87.0%');
  });

  it('renders untrusted answer, citation name and snippet as escaped text', async () => {
    askAnswers({
      answer: '<img src=x onerror=alert(1)>',
      citations: [{ ...citation(1), fileName: '<b>bold-name</b>', snippet: '<i>bold-snippet</i>' }],
    });
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    await wrapper.findAll('.ask__docs input[type="checkbox"]')[0]?.setValue(true);
    await wrapper.get('[data-testid="question"]').setValue('q');

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.messages).toHaveLength(4));

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');
    expect(wrapper.text()).toContain('<b>bold-name</b>');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('b').exists()).toBe(false);
  });

  it('refuses locally before any request leaves when no session is open', async () => {
    const { wrapper } = await mountView();

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').text()).toContain('请先新建或选择一个会话。'));

    expect(callLog.askedKeys).toHaveLength(0);
    expect(wrapper.find('[role="alert"]').text()).not.toContain('code');
  });

  it('offers the same-key retry only after a rejected question', async () => {
    conflictThenSuccess();
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    await wrapper.findAll('.ask__docs input[type="checkbox"]')[0]?.setValue(true);
    await wrapper.get('[data-testid="question"]').setValue('请解释梯度下降。');

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.detail).toBe('该会话正在处理另一个问题，请稍后重试。'));
    await nextTick();

    expect(wrapper.get('[data-testid="submit"]').text()).toBe('重试同一问题');
    expect(wrapper.get('[data-testid="submit"]').attributes('disabled')).toBeUndefined();

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.messages).toHaveLength(4));
    // `submitting` 要等成功分支里的 `loadListFirst()` 返回才落回 false，
    // 只等 messages 长度会在列表刷新期间读到「提问中…」，因此等按钮真正回到空闲态。
    await vi.waitFor(() => expect(wrapper.get('[data-testid="submit"]').text()).toBe('提问'));

    expect(callLog.askedKeys[0]).toBe(callLog.askedKeys[1]);
  });

  it('holds the submit button for the server-suggested backoff', async () => {
    askFailures(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '30' });
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    await wrapper.findAll('.ask__docs input[type="checkbox"]')[0]?.setValue(true);
    await wrapper.get('[data-testid="question"]').setValue('请解释梯度下降。');

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.retryAfterSeconds).toBe(30));
    await nextTick();

    expect(wrapper.get('[data-testid="submit"]').attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toMatch(/限流等待 \d+ 秒。/);
    expect(wrapper.find('[role="alert"]').text()).toContain('code COM-RATE_LIMIT-001');
    wrapper.unmount();
  });

  it('loads older messages only while the history cursor is issued', async () => {
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    const older = wrapper.findAll('button').find((button) => button.text() === '加载更早的消息');
    expect(older).toBeDefined();

    await older?.trigger('click');
    await vi.waitFor(() => expect(store.messages).toHaveLength(3));

    expect(wrapper.findAll('button').some((button) => button.text() === '加载更早的消息')).toBe(false);
  });

  it('renders each chunk as it arrives and lets the user stop the generation', async () => {
    const calls = stubFetch((call) =>
      sseOpenResponse(
        [
          sseFrame('meta', askStreamMeta()),
          sseFrame('delta', { text: '梯度下降' }),
          sseFrame('delta', { text: '沿负梯度方向。' }),
        ],
        call.signal,
      ),
    );
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    await wrapper.findAll('.ask__docs input[type="checkbox"]')[0]?.setValue(true);
    await wrapper.get('[data-testid="question"]').setValue('请解释梯度下降。');

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.messages.at(-1)?.content).toBe('梯度下降沿负梯度方向。'));
    await nextTick();

    expect(wrapper.get('[data-testid="submit"]').text()).toBe('生成中…');
    expect(wrapper.findAll('.ask__message').at(-1)?.text()).toContain('梯度下降沿负梯度方向。');
    expect(wrapper.find('[data-testid="stream-cursor"]').exists()).toBe(true);
    // 生成中的半行没有治理结论：既不出现徽章，也不能被标成「历史消息」。
    expect(wrapper.findAll('.governance')).toHaveLength(1);

    await wrapper.get('[data-testid="stop"]').trigger('click');
    await vi.waitFor(() => expect(store.streaming).toBe(false));
    await nextTick();

    expect(wrapper.findAll('.ask__message')).toHaveLength(2);
    expect(wrapper.find('[data-testid="stream-cursor"]').exists()).toBe(false);
    // 主动断开不是一种失败：没有告警，草稿与同一把幂等键都留着。
    expect(store.detail).toBeNull();
    expect(wrapper.get('[data-testid="submit"]').text()).toBe('重试同一问题');
    expect(calls[0]?.signal?.aborted).toBe(true);
  });

  it('replaces the streamed text with the server answer once the stream is done', async () => {
    stubFetch(() => sseResponse(askStreamFrames(['拼接中的半成品。'])));
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    await wrapper.findAll('.ask__docs input[type="checkbox"]')[0]?.setValue(true);
    await wrapper.get('[data-testid="question"]').setValue('请解释梯度下降。');

    await wrapper.get('[data-testid="submit"]').trigger('click');
    // 治理字段只在 done 之后出现，以此判定「服务端定稿」确实落到了这一行。
    await vi.waitFor(() => expect(store.messages.at(-1)?.governance).not.toBeNull());
    await nextTick();

    const last = wrapper.findAll('.ask__message').at(-1);
    // done 是字段真源：定稿答案整体替换拼接结果，半成品不留残影。
    expect(last?.text()).toContain(askData().answer);
    expect(last?.text()).not.toContain('拼接中的半成品');
    expect(last?.text()).toContain('正常回答');
    expect(wrapper.find('[data-testid="stream-cursor"]').exists()).toBe(false);
    expect(store.question).toBe('');
    await vi.waitFor(() => expect(wrapper.get('[data-testid="submit"]').text()).toBe('提问'));
  });

  it('escapes streamed text and drops the connection when the view unmounts', async () => {
    const calls = stubFetch((call) =>
      sseOpenResponse(
        [sseFrame('meta', askStreamMeta()), sseFrame('delta', { text: '<img src=x onerror=alert(1)>' })],
        call.signal,
      ),
    );
    const { wrapper, store } = await mountView();
    await openSession(wrapper);
    await wrapper.findAll('.ask__docs input[type="checkbox"]')[0]?.setValue(true);
    await wrapper.get('[data-testid="question"]').setValue('q');

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.messages.at(-1)?.content).toContain('<img'));

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');
    expect(wrapper.find('img').exists()).toBe(false);

    // 离开页面即断开：连接继续挂着既占服务端资源，也没有下一个视图来消费这半行。
    wrapper.unmount();
    expect(calls[0]?.signal?.aborted).toBe(true);
  });
});
