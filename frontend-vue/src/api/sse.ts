/**
 * SSE 传输层（S8-4，D-175 方案 A）：原生 fetch + 手写帧解析，零新依赖。
 *
 * axios 在浏览器里拿不到响应体的 reader，所以流式通道不能走 `http.ts`；
 * 但会话钩子（取令牌 / 续期 / 结束会话）必须与 axios 共用同一份，否则两条通道的
 * 401 语义会各自漂移（一边续期重放、一边直接掉登录）。
 */
import type { ProblemDetail } from '../types/api';
import {
  ApiError,
  NETWORK_ERROR_CODE,
  StreamBufferOverflowError,
  StreamUnavailableError,
  UNKNOWN_ERROR_CODE,
} from './errors';
import {
  API_BASE_URL,
  AUTH_HEADER,
  IDEMPOTENCY_HEADER,
  currentSessionHooks,
  isProblemDetail,
  readRetryAfter,
} from './http';

/** 一帧 SSE：事件名 + `data:` 原文。载荷是否符合契约由调用方判定，本层不猜语义。 */
export interface SseFrame {
  event: string;
  data: string;
}

export interface SseRequest {
  /** 不含 `API_BASE_URL` 前缀的路径。 */
  path: string;
  body: unknown;
  /** 契约上 POST 必需；重放与首发复用同一个键。 */
  idempotencyKey: string;
  signal?: AbortSignal;
}

/**
 * 行缓冲上限（≈1 MiB UTF-16 码元）。SSE 行以换行符终止：服务端若持续下发无换行字节，
 * `buffer` 会无界增长撑爆标签页内存，必须在超过阈值前断开。
 */
const MAX_BUFFER_CHARS = 1024 * 1024;
/** 单帧 `data:` 行数上限：没有空行收尾的帧会无限堆行，同样要断开。 */
const MAX_DATA_LINES = 10_000;

/**
 * 发起流式 POST 并按帧产出。抛出三类错误：
 * `ApiError`（治理拒绝与流本身不合规）、`StreamUnavailableError`（服务端没有以事件流应答）、
 * 以及调用方 abort 产生的 AbortError（原样透传，由发起方判定为「主动断开」而非失败）。
 */
export async function* postSseStream(request: SseRequest): AsyncGenerator<SseFrame, void, void> {
  const response = await openStream(request);
  const body = response.body;
  if (body === null) {
    throw new StreamUnavailableError(response.status);
  }
  yield* sseFrames(body, request.signal);
}

/** 行缓冲解析：注释行忽略、`\r\n` 可被切在两片之间、多字节字符可被切在片中。 */
export async function* sseFrames(
  body: ReadableStream<Uint8Array>,
  signal?: AbortSignal,
): AsyncGenerator<SseFrame, void, void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = '';
  let dataLines: string[] = [];
  let dataSeen = false;
  try {
    for (;;) {
      let result: Awaited<ReturnType<typeof reader.read>>;
      try {
        result = await reader.read();
      } catch (error) {
        if (isAbort(error, signal)) {
          return;
        }
        throw error;
      }
      if (result.done) {
        break;
      }
      buffer += decoder.decode(result.value, { stream: true });
      // 无换行流会把整段字节堆在 buffer 里：超过上限立即断开并抛出明确错误，不能继续累积。
      if (buffer.length > MAX_BUFFER_CHARS) {
        await reader.cancel();
        throw new StreamBufferOverflowError();
      }
      const taken = takeLines(buffer, false);
      buffer = taken.rest;
      for (const line of taken.lines) {
        if (line === '') {
          if (dataSeen) {
            yield { event: eventName === '' ? 'message' : eventName, data: dataLines.join('\n') };
          }
          eventName = '';
          dataLines = [];
          dataSeen = false;
          continue;
        }
        // `: keep-alive` 之类的注释行必须忽略：契约明确检索等长间隔会下发它们。
        if (line.startsWith(':')) {
          continue;
        }
        const colon = line.indexOf(':');
        const field = colon === -1 ? line : line.slice(0, colon);
        const rawValue = colon === -1 ? '' : line.slice(colon + 1);
        // SSE 规范要求吃掉冒号后的第一个空格，其余字符原样保留（JSON 载荷里空格有意义）。
        const value = rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue;
        if (field === 'event') {
          eventName = value;
        } else if (field === 'data') {
          dataLines.push(value);
          dataSeen = true;
          // 没有空行收尾的帧会让 dataLines 无限增长：同样断开并抛出明确错误。
          if (dataLines.length > MAX_DATA_LINES) {
            await reader.cancel();
            throw new StreamBufferOverflowError();
          }
        }
        // `id` / `retry` 本契约不用，按规范忽略即可。
      }
    }
    buffer += decoder.decode();
    // 流结束时未以空行收尾的最后一帧按 SSE 规范丢弃，不做「补一帧」的猜测。
  } finally {
    // 消费者提前 break 也必须断开连接——这是 D-174「客户端断开即取消上游」的客户端一侧。
    try {
      await reader.cancel();
    } catch {
      // 已经结束的流取消会 reject，没有可补救的东西。
    }
    reader.releaseLock();
  }
}

async function openStream(request: SseRequest, retried = false): Promise<Response> {
  const session = currentSessionHooks();
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${request.path}`, {
      method: 'POST',
      headers: streamHeaders(session.accessToken(), request.idempotencyKey),
      body: JSON.stringify(request.body),
      // 刷新令牌只存在于 HttpOnly Cookie，跨源部署时也必须随请求发送（与 axios 的 withCredentials 同判据）。
      credentials: 'include',
      signal: request.signal,
    });
  } catch (error) {
    if (isAbort(error, request.signal)) {
      throw error;
    }
    throw new ApiError(0, NETWORK_ERROR_CODE, '无法连接服务，请检查网络。', null);
  }

  if (response.status === 401) {
    // 与 `http.ts` 逐条对齐：无内存令牌就没有续期机会；每次请求只放行一次续期 + 一次重放。
    const mayRefresh = !retried && session.accessToken() !== null;
    const refreshed = mayRefresh ? await session.refreshAccessToken() : null;
    if (refreshed !== null) {
      return openStream(request, true);
    }
    // 续期失败、没有会话、或重放后仍然 401，都到此为止：会话已结束。
    session.onUnauthorized();
  }

  if (!response.ok) {
    // 治理拒绝（校验 / 429 / Kill Switch / 幂等冲突）发生在首个事件帧之前，响应就是普通 JSON ProblemDetail。
    throw await problemApiError(response);
  }

  const contentType = (response.headers.get('content-type') ?? '').toLowerCase();
  if (!contentType.includes('text/event-stream')) {
    await cancelQuietly(response);
    throw new StreamUnavailableError(response.status);
  }
  return response;
}

function streamHeaders(token: string | null, idempotencyKey: string): Headers {
  const headers = new Headers({
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
    [IDEMPOTENCY_HEADER]: idempotencyKey,
  });
  if (token !== null) {
    headers.set(AUTH_HEADER, `Bearer ${token}`);
  }
  return headers;
}

async function problemApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | null = null;
  try {
    const body: unknown = await response.json();
    if (isProblemDetail(body)) {
      problem = body;
    }
  } catch {
    // 反向代理直接返回 HTML 之类的非 JSON 错误体：状态码仍然真实，按未知错误呈现。
  }
  return new ApiError(
    response.status,
    problem?.code ?? UNKNOWN_ERROR_CODE,
    problem?.detail ?? `服务返回了无法解析的错误（HTTP ${response.status}）。`,
    problem?.traceId ?? null,
    problem?.errors ?? [],
    readRetryAfter((name) => response.headers.get(name), response.status),
  );
}

async function cancelQuietly(response: Response): Promise<void> {
  try {
    await response.body?.cancel();
  } catch {
    // 无 body 可读时不必处理：连接由服务端或代理回收。
  }
}

function isAbort(error: unknown, signal?: AbortSignal): boolean {
  if (signal?.aborted === true) {
    return true;
  }
  return (error as { name?: string } | null)?.name === 'AbortError';
}

/**
 * 按 SSE 行终止符（CRLF / LF / CR）切出完整行。
 * 缓冲区结尾是孤立 `\r` 时必须留着不切——下一片可能是 `\n`，切了就会多出一个空行、把一帧拆成两帧。
 */
function takeLines(buffer: string, flush: boolean): { lines: string[]; rest: string } {
  const lines: string[] = [];
  let start = 0;
  for (let index = start; index < buffer.length; index += 1) {
    const char = buffer[index];
    if (char === '\n') {
      lines.push(buffer.slice(start, index));
      start = index + 1;
      continue;
    }
    if (char !== '\r') {
      continue;
    }
    if (index === buffer.length - 1 && !flush) {
      break;
    }
    lines.push(buffer.slice(start, index));
    const next = buffer[index + 1];
    start = next === '\n' ? index + 2 : index + 1;
    index = start - 1;
  }
  return { lines, rest: buffer.slice(start) };
}
