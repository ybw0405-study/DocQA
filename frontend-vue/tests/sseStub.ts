/**
 * 流式通道的测试替身（S8-4）。
 *
 * `api/sse.ts` 用原生 fetch：Node 侧既解析不了相对 URL，msw 对 `text/event-stream` 的
 * 逐帧到达也没有保真度可言，所以「分片切在哪、注释行会不会漏、401 重放几次、abort 是否静默」
 * 只能在 fetch 桩上断言。非流式回退仍走 axios + msw，两条通道分别记账。
 */
import { vi } from 'vitest';
import { envelope, problem } from './fixtures';

/** 桩真实收到的一次流式请求；只读 `init`，不碰实现内部对象。 */
export interface StreamCall {
  path: string;
  method: string;
  key: string | null;
  authorization: string | null;
  accept: string | null;
  /** 浏览器是否随请求发送 HttpOnly 刷新 Cookie；流式通道必须与非流式一样带上它才有续期机会。 */
  credentials: string | null;
  body: Record<string, unknown>;
  signal: AbortSignal | null;
}

/** 打桩原生 fetch，返回桩收到的调用序列。`respond` 拿到本次请求与本桩内的第几次调用。 */
export function stubFetch(respond: (call: StreamCall, attempt: number) => Response): StreamCall[] {
  const calls: StreamCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const headers = new Headers(init?.headers ?? {});
      const call: StreamCall = {
        path: String(input),
        method: String(init?.method ?? 'GET'),
        key: headers.get('Idempotency-Key'),
        authorization: headers.get('Authorization'),
        accept: headers.get('Accept'),
        credentials: (init?.credentials as string | undefined) ?? null,
        body: JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>,
        signal: (init?.signal as AbortSignal | null) ?? null,
      };
      calls.push(call);
      return respond(call, calls.length);
    }),
  );
  return calls;
}

function byteStream(chunks: (string | Uint8Array)[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk);
      }
      controller.close();
    },
  });
}

/** 正常应答的事件流：分片原样入队后关闭，逐帧到达的节奏由用例自己切。 */
export function sseResponse(
  chunks: (string | Uint8Array)[],
  init: { status?: number; contentType?: string | null } = {},
): Response {
  const headers: Record<string, string> = {};
  if (init.contentType !== null) {
    headers['Content-Type'] = init.contentType ?? 'text/event-stream';
  }
  return new Response(byteStream(chunks), { status: init.status ?? 200, headers });
}

/** 200 但响应体为空：同样「没有事件流可解析」，是 StreamUnavailable 的第二条触发面。 */
export function emptyStreamResponse(): Response {
  return new Response(null, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

/**
 * 下发若干帧后吊着不断：`signal` 一 abort 就按浏览器口径让读取以 AbortError 结束。
 * 「停止生成」与切换会话的中止语义只有靠这种应答才测得到——服务端可不会替你关流。
 */
export function sseOpenResponse(chunks: (string | Uint8Array)[], signal: AbortSignal | null): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk);
      }
      if (signal === null) {
        return;
      }
      if (signal.aborted) {
        controller.error(abortError());
        return;
      }
      signal.addEventListener('abort', () => controller.error(abortError()), { once: true });
    },
  });
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

function abortError(): Error {
  const error = new Error('The operation was aborted.');
  error.name = 'AbortError';
  return error;
}

/** 2xx 但不是事件流（例如反向代理吞掉了流式语义）：store 应以**同一条幂等键**回退非流式 `/ask`。 */
export function notAStreamResponse(
  init: { status?: number; contentType?: string } = {},
): Response {
  return new Response(JSON.stringify(envelope({ ok: true })), {
    status: init.status ?? 200,
    headers: { 'Content-Type': init.contentType ?? 'application/json' },
  });
}

/** 首字节前的 JSON 拒绝：治理闸在 SSE 建立之前就把它挡掉了。 */
export function problemStreamResponse(status: number, code: string, detail: string, headers: Record<string, string> = {}): Response {
  return new Response(
    JSON.stringify(problem({ status, code, detail, instance: '/api/v1/conversations/ask:stream' })),
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );
}

/** error 帧：契约上它就是 ProblemDetail 同构，与首字节前的 JSON 拒绝同一个错误码集合。 */
export function errorFrame(status: number, code: string, detail: string): string {
  return `event: error\r\ndata: ${JSON.stringify(problem({ status, code, detail, instance: '/api/v1/conversations/ask:stream' }))}\r\n\r\n`;
}

/** 按字节数切片，用来把一帧（甚至一个多字节字符）精确切在两片之间。 */
export function byteChunks(text: string, size: number): Uint8Array[] {
  const bytes = new TextEncoder().encode(text);
  const chunks: Uint8Array[] = [];
  for (let index = 0; index < bytes.length; index += size) {
    chunks.push(bytes.slice(index, index + size));
  }
  return chunks;
}
