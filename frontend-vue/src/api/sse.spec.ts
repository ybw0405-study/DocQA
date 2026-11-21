import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { postSseStream, type SseFrame, type SseRequest } from './sse';
import {
  ApiError,
  NETWORK_ERROR_CODE,
  STREAM_BUFFER_OVERFLOW_CODE,
  StreamBufferOverflowError,
  StreamUnavailableError,
  UNKNOWN_ERROR_CODE,
} from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import {
  DEMO_CONVERSATION_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  askStreamFrames,
  askStreamMeta,
  refreshHandler,
  sseFrame,
} from '../../tests/fixtures';
import {
  byteChunks,
  emptyStreamResponse,
  notAStreamResponse,
  problemStreamResponse,
  sseOpenResponse,
  sseResponse,
  stubFetch,
} from '../../tests/sseStub';
import { server } from '../../tests/server';

/** `api/sse.ts` 只管「一帧一帧地解」，事件名与载荷语义由 `api/conversations.ts` 判定。 */
const STREAM_PATH = `/conversations/${DEMO_CONVERSATION_ID}/ask:stream`;

function streamRequest(signal?: AbortSignal): SseRequest {
  return {
    path: STREAM_PATH,
    body: { question: '请解释梯度下降。', documentIds: ['00000000-0000-0000-0000-0000000000a1'] },
    idempotencyKey: 'ASK-KEY',
    signal,
  };
}

async function collect(iterable: AsyncGenerator<SseFrame, void, void>): Promise<SseFrame[]> {
  const frames: SseFrame[] = [];
  for await (const frame of iterable) {
    frames.push(frame);
  }
  return frames;
}

async function expectThrown(action: Promise<unknown>): Promise<unknown> {
  try {
    await action;
  } catch (error) {
    return error;
  }
  throw new Error('预期抛出错误，但调用成功了');
}

function plainResponse(status: number, contentType: string, body: string): Response {
  return new Response(body, { status, headers: { 'Content-Type': contentType } });
}

describe('api/sse', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('sends the contract headers once and hands the frames back in arrival order', async () => {
    const calls = stubFetch(() => sseResponse(askStreamFrames(['第一段', '第二段'])));

    const frames = await collect(postSseStream(streamRequest()));

    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({
      path: `/api/v1${STREAM_PATH}`,
      method: 'POST',
      key: 'ASK-KEY',
      accept: 'text/event-stream',
      authorization: `Bearer ${DEMO_TOKEN}`,
      credentials: 'include',
      body: { question: '请解释梯度下降。', documentIds: ['00000000-0000-0000-0000-0000000000a1'] },
    });
    expect(frames.map((frame) => frame.event)).toEqual(['meta', 'delta', 'delta', 'done']);
    expect(JSON.parse(String(frames[1]?.data))).toEqual({ text: '第一段' });
  });

  it('reassembles frames split at arbitrary byte boundaries, inside multibyte text too', async () => {
    const wire = askStreamFrames(['梯度下降沿损失函数的负梯度方向更新参数。', '学习率决定每步步长。']).join('');
    stubFetch(() => sseResponse(byteChunks(wire, 5)));

    const frames = await collect(postSseStream(streamRequest()));

    expect(frames.map((frame) => frame.event)).toEqual(['meta', 'delta', 'delta', 'done']);
    expect(JSON.parse(String(frames[2]?.data))).toEqual({ text: '学习率决定每步步长。' });
    expect(JSON.parse(String(frames[3]?.data)).answer).toBeTypeOf('string');
  });

  it('keeps a lone trailing CR buffered so a CR/LF split across chunks stays one line break', async () => {
    // 把 `\r` 当行终止符立刻切割，就会多出一个空行：事件名在 data 之前被清空，帧会退化成 message。
    stubFetch(() => sseResponse(['event: delta\r', '\ndata: {"text":"第一段"}\r\n\r\n']));

    const frames = await collect(postSseStream(streamRequest()));

    expect(frames).toEqual([{ event: 'delta', data: '{"text":"第一段"}' }]);
  });

  it('ignores keep-alive comment lines wherever they land', async () => {
    stubFetch(() =>
      sseResponse([
        ': keep-alive\r\n',
        sseFrame('delta', { text: '第一段' }),
        ': keep-alive\r\n',
        '\r\n',
      ]),
    );

    const frames = await collect(postSseStream(streamRequest()));

    // 注释行前后的空行不构成一帧：没有 data 的行组必须整个丢弃。
    expect(frames).toEqual([{ event: 'delta', data: '{"text":"第一段"}' }]);
  });

  it('joins every data line of one frame with a newline', async () => {
    stubFetch(() => sseResponse(['event: delta\r\ndata: {"text":\ndata: "第一段"}\r\n\r\n']));

    const frames = await collect(postSseStream(streamRequest()));

    expect(frames).toHaveLength(1);
    expect(frames[0]?.data).toBe('{"text":\n"第一段"}');
    expect(JSON.parse(String(frames[0]?.data))).toEqual({ text: '第一段' });
  });

  it('drops a frame that carries no data', async () => {
    stubFetch(() => sseResponse(['event: delta\r\n\r\n', ': keep-alive\r\n\r\n']));

    expect(await collect(postSseStream(streamRequest()))).toEqual([]);
  });

  it('stops quietly without an error when the caller aborts mid-stream', async () => {
    const controller = new AbortController();
    const calls = stubFetch((call) => sseOpenResponse([sseFrame('delta', { text: '第一段' })], call.signal));
    const seen: string[] = [];

    const draining = (async () => {
      for await (const frame of postSseStream(streamRequest(controller.signal))) {
        seen.push(frame.event);
      }
      return seen;
    })();
    await vi.waitFor(() => expect(seen).toEqual(['delta']));

    controller.abort();

    // 主动断开不是失败，也不是协议违例：调用方只看到「流结束了」。
    await expect(draining).resolves.toEqual(['delta']);
    expect(calls[0]?.signal).toBe(controller.signal);
  });

  it('keeps the governance code, trace and server-suggested wait on a pre-first-byte rejection', async () => {
    stubFetch(() =>
      problemStreamResponse(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '30' }),
    );

    const error = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(429);
    expect(error.code).toBe('COM-RATE_LIMIT-001');
    expect(error.traceId).toBe(DEMO_TRACE_ID);
    expect(error.retryAfterSeconds).toBe(30);
  });

  it('does not invent a wait when Retry-After is not whole seconds on a 429', async () => {
    stubFetch(() =>
      problemStreamResponse(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '0.5' }),
    );
    const vague = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    stubFetch(() =>
      problemStreamResponse(503, 'AGT-UPSTREAM-003', 'AI 功能已暂停。', { 'Retry-After': '30' }),
    );
    const offContract = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    expect(vague.retryAfterSeconds).toBeNull();
    // 契约只在 429 上声明 Retry-After，其他状态码上的这个头按「服务端没给建议」处理。
    expect(offContract.retryAfterSeconds).toBeNull();
    expect(offContract.code).toBe('AGT-UPSTREAM-003');
  });

  it('falls back to the unknown marker when the rejection body is not a ProblemDetail', async () => {
    stubFetch(() => plainResponse(502, 'text/html', '<html>Bad Gateway</html>'));

    const error = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    expect(error.code).toBe(UNKNOWN_ERROR_CODE);
    expect(error.status).toBe(502);
    expect(error.detail).toBe('服务返回了无法解析的错误（HTTP 502）。');
  });

  it('calls a 200 that is not an event stream an unavailable deployment', async () => {
    stubFetch(() => notAStreamResponse());

    const error = (await expectThrown(collect(postSseStream(streamRequest())))) as StreamUnavailableError;

    expect(error).toBeInstanceOf(StreamUnavailableError);
    expect(error.status).toBe(200);
  });

  it('calls a 200 with no response body an unavailable deployment too', async () => {
    stubFetch(() => emptyStreamResponse());

    expect(await expectThrown(collect(postSseStream(streamRequest())))).toBeInstanceOf(StreamUnavailableError);
  });

  it('keeps a missing route a real failure instead of a fallback trigger', async () => {
    // 网关的「会话不存在」与契约的 COM-NOT_FOUND-001 在 404 上无法区分；
    // 把它当成「部署不支持流式」会让一次拼错的会话 ID 静默变成另一条通道上的重问。
    stubFetch(() => problemStreamResponse(404, 'COM-NOT_FOUND-001', '会话不存在。'));

    const error = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(404);
    expect(error.code).toBe('COM-NOT_FOUND-001');
  });

  it('refreshes once and replays the stream request with the new token', async () => {
    server.use(refreshHandler('stream-refreshed-token'));
    const calls = stubFetch((_call, attempt) =>
      attempt === 1 ? problemStreamResponse(401, 'USR-AUTH-001', '令牌无效。') : sseResponse(askStreamFrames(['第一段'])),
    );

    const frames = await collect(postSseStream(streamRequest()));

    expect(calls).toHaveLength(2);
    expect(calls[0]?.authorization).toBe(`Bearer ${DEMO_TOKEN}`);
    expect(calls[1]?.authorization).toBe('Bearer stream-refreshed-token');
    // 重放复用同一个幂等键：服务端看到的仍是同一次提问。
    expect(calls[1]?.key).toBe('ASK-KEY');
    expect(frames.map((frame) => frame.event)).toEqual(['meta', 'delta', 'done']);
    expect(useAuthStore().token).toBe('stream-refreshed-token');
  });

  it('ends the session without a replay when the refresh itself fails', async () => {
    const calls = stubFetch(() => problemStreamResponse(401, 'USR-AUTH-001', '令牌无效。'));

    const error = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    expect(error.status).toBe(401);
    expect(error.code).toBe('USR-AUTH-001');
    // 续期失败就没有重放：一次请求都不该再发出去，否则掉登录的用户会看到无限转圈。
    expect(calls).toHaveLength(1);
    expect(useAuthStore().token).toBeNull();
  });

  it('gives the replay exactly one chance', async () => {
    server.use(refreshHandler('stream-refreshed-token'));
    const calls = stubFetch(() => problemStreamResponse(401, 'USR-AUTH-001', '令牌无效。'));

    const error = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    expect(error.status).toBe(401);
    expect(calls).toHaveLength(2);
    expect(useAuthStore().token).toBeNull();
  });

  it('reports an unreachable service as a network failure', async () => {
    stubFetch(() => {
      throw new TypeError('fetch failed');
    });

    const error = (await expectThrown(collect(postSseStream(streamRequest())))) as ApiError;

    expect(error.status).toBe(0);
    expect(error.code).toBe(NETWORK_ERROR_CODE);
    expect(error.isNetworkFailure).toBe(true);
  });

  it('aborts and throws a clear error when a no-newline stream overruns the buffer', async () => {
    let cancelled = false;
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        // 单块就超过 1 MiB 且没有任何行终止符：takeLines 切不出完整行，buffer 会无界增长。
        controller.enqueue(encoder.encode('x'.repeat(1024 * 1024 + 1)));
      },
      cancel() {
        cancelled = true;
      },
    });
    stubFetch(() => new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));

    const error = await expectThrown(collect(postSseStream(streamRequest())));

    expect(error).toBeInstanceOf(StreamBufferOverflowError);
    expect((error as ApiError).code).toBe(STREAM_BUFFER_OVERFLOW_CODE);
    // 超限时必须主动断开上游，而不是让标签页内存继续被撑大。
    expect(cancelled).toBe(true);
  });

  it('aborts when a frame keeps stacking data lines without a terminator', async () => {
    let cancelled = false;
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        // 10001 条 data: 行、始终没有空行收尾：dataLines 同样会无界增长。
        controller.enqueue(encoder.encode('data: x\n'.repeat(10_001)));
      },
      cancel() {
        cancelled = true;
      },
    });
    stubFetch(() => new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));

    const error = await expectThrown(collect(postSseStream(streamRequest())));

    expect(error).toBeInstanceOf(StreamBufferOverflowError);
    expect((error as ApiError).code).toBe(STREAM_BUFFER_OVERFLOW_CODE);
    expect(cancelled).toBe(true);
  });

  it('cancels the connection when the consumer stops reading early', async () => {
    let cancelled = false;
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(sseFrame('meta', askStreamMeta())));
        controller.enqueue(encoder.encode(sseFrame('delta', { text: '第一段' })));
      },
      cancel() {
        cancelled = true;
      },
    });
    stubFetch(() => new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));

    const seen: string[] = [];
    for await (const frame of postSseStream(streamRequest())) {
      seen.push(frame.event);
      break;
    }

    // D-174「客户端断开即取消上游」的客户端一侧：提前 break 也必须把这条连接放掉。
    expect(seen).toEqual(['meta']);
    expect(cancelled).toBe(true);
  });
});
