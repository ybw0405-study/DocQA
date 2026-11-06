import axios, { AxiosError, type AxiosHeaders, type AxiosRequestConfig, type AxiosResponse } from 'axios';
import type { ApiEnvelope, ProblemDetail } from '../types/api';
import { ApiError, NETWORK_ERROR_CODE, UNKNOWN_ERROR_CODE } from './errors';

/** axios 的 `baseURL` 与 fetch 侧拼路径共用同一个前缀常量，避免两条通道各写一遍 `/api/v1`。 */
export const API_BASE_URL = '/api/v1';

/** 必须大于 agent-gateway 对 Python 的 15s deadline，否则前端会先于后端断开，用户看不到治理字段。 */
const REQUEST_TIMEOUT_MS = 20_000;

/**
 * 头名常量由两条通道共用：`api/sse.ts` 走原生 fetch 不经过 axios 拦截器，
 * 但发出去的头必须与这里逐字一致，否则「一边带得上、一边带不上」要等真机才能发现。
 */
export const IDEMPOTENCY_HEADER = 'Idempotency-Key';
export const AUTH_HEADER = 'Authorization';

/**
 * 这些端点的 401 报的是「请求体里的凭据不对」，不是「会话失效」，所以既不该续期也不该结束会话。
 * `/auth/password` 尤其要挡在这里：服务端每个这种 401 都记一次登录失败，而它在失败时会清掉幂等记录，
 * 于是重放不是重放而是第二次失败（60 秒窗口内 5 次就锁）；续期失败时 `onUnauthorized` 又会把
 * 只是打错旧口令的管理员直接踢回登录页。`/auth/refresh` 自身再续期就绕成回环。
 */
const REFRESH_EXEMPT_PATHS = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/password'];

export const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: REQUEST_TIMEOUT_MS,
  headers: { Accept: 'application/json' },
  // 刷新令牌只存在于 HttpOnly Cookie 中，跨源部署时也必须随请求发送。
  withCredentials: true,
});

/** 解包后的业务数据加上成功信封的 `traceId`，供报障与本地行取证使用。 */
export interface Traced<T> {
  data: T;
  traceId: string;
}

export interface SessionHooks {
  accessToken: () => string | null;
  /** 用 HttpOnly 刷新 Cookie 换取新的访问令牌；失败返回 null，由调用方决定是否结束会话。 */
  refreshAccessToken: () => Promise<string | null>;
  onUnauthorized: () => void;
}

let session: SessionHooks = {
  accessToken: () => null,
  refreshAccessToken: async () => null,
  onUnauthorized: () => undefined,
};

export function configureSession(hooks: SessionHooks): void {
  session = hooks;
}

/**
 * SSE 通道走原生 fetch、不经过 axios 拦截器，但 401 语义必须与非流式完全一致，
 * 因此这里把同一份钩子交出去，而不是让 `api/sse.ts` 再配一套。
 */
export function currentSessionHooks(): SessionHooks {
  return session;
}

/** `crypto.randomUUID` 只在安全上下文提供，用局域网 IP 直访 dev 页面时会是 undefined；`getRandomValues` 不受此限制。 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

http.interceptors.request.use((config) => {
  const token = session.accessToken();
  if (token !== null) {
    config.headers.set(AUTH_HEADER, `Bearer ${token}`);
  }
  // 契约中所有 POST 都要求 Idempotency-Key，GET 一律不带；调用方显式给定的键优先。
  const method = config.method ?? 'get';
  if (method.toLowerCase() !== 'get' && !config.headers.has(IDEMPOTENCY_HEADER)) {
    config.headers.set(IDEMPOTENCY_HEADER, newIdempotencyKey());
  }
  return config;
});

/** 每次重试只放行一次，避免「续期成功但请求仍 401」时无限重放。 */
interface RetriableConfig extends AxiosRequestConfig {
  refreshRetried?: boolean;
}

function isRefreshExempt(config: RetriableConfig | undefined): boolean {
  const path = config?.url ?? '';
  return REFRESH_EXEMPT_PATHS.some((exempt) => path.startsWith(exempt));
}

function isRefreshable(error: AxiosError, config: RetriableConfig | undefined): config is RetriableConfig {
  if (config === undefined || error.response?.status !== 401) {
    return false;
  }
  if (config.refreshRetried === true) {
    return false;
  }
  if (isRefreshExempt(config)) {
    return false;
  }
  // 内存里没有令牌说明本来就没有会话，此时 401 就是最终结论。
  return session.accessToken() !== null;
}

async function retryAfterRefresh(config: RetriableConfig): Promise<AxiosResponse | null> {
  config.refreshRetried = true;
  const token = await session.refreshAccessToken();
  if (token === null) {
    return null;
  }
  // 走到这里的 config 一定经过请求拦截器，headers 已被 axios 归一为 AxiosHeaders。
  (config.headers as AxiosHeaders).set(AUTH_HEADER, `Bearer ${token}`);
  return http.request(config);
}

http.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (error instanceof AxiosError) {
      const config = error.config as RetriableConfig | undefined;
      if (isRefreshable(error, config)) {
        const retried = await retryAfterRefresh(config);
        if (retried !== null) {
          return retried;
        }
      }
      // 登录/注册/刷新自身的 401 是凭据问题，不是会话失效；由调用方决定怎么呈现。
      if (error.response?.status === 401 && !isRefreshExempt(config)) {
        session.onUnauthorized();
      }
    }
    throw error;
  },
);

/** 导出给 fetch 侧复用：两条通道对「响应体是不是 ProblemDetail」必须是同一个判据。 */
export function isProblemDetail(body: unknown): body is ProblemDetail {
  return typeof body === 'object' && body !== null && 'status' in body && 'code' in body;
}

/**
 * 契约只在 429 上声明了 `Retry-After`，且形如整秒。HTTP-date 形态、越界值和其他状态码
 * 一律按「服务端没给建议」处理，前端不自造等待时间。
 * 两条通道取头的方式不同（axios 是普通对象、fetch 是 `Headers`），因此这里收取值器而不是收容器，
 * 判据本身只写一遍。
 */
export function readRetryAfter(pick: (name: string) => unknown, status: number): number | null {
  if (status !== 429) {
    return null;
  }
  const raw = pick('retry-after');
  const seconds = typeof raw === 'number' ? raw : Number(raw);
  return Number.isInteger(seconds) && seconds >= 1 ? seconds : null;
}

function toApiError(error: unknown): never {
  if (error instanceof ApiError) {
    throw error;
  }
  if (error instanceof AxiosError) {
    const response = error.response;
    if (response === undefined) {
      const detail = error.code === 'ECONNABORTED' ? '请求超时，请稍后重试。' : '无法连接服务，请检查网络。';
      throw new ApiError(0, NETWORK_ERROR_CODE, detail, null);
    }
    const problem = isProblemDetail(response.data) ? response.data : null;
    throw new ApiError(
      response.status,
      problem?.code ?? UNKNOWN_ERROR_CODE,
      problem?.detail ?? `服务返回了无法解析的错误（HTTP ${response.status}）。`,
      problem?.traceId ?? null,
      problem?.errors ?? [],
      readRetryAfter((name) => (response.headers as Record<string, unknown> | undefined)?.[name], response.status),
    );
  }
  throw error;
}

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  return (await requestWithTrace<T>(config)).data;
}

/**
 * 成功信封的 `traceId` 只有这里能拿到；问答页把它写进本地消息行，
 * 使「本次提问」与刷新后服务端历史里的 `traceId` 是同一个值。
 */
export async function requestWithTrace<T>(config: AxiosRequestConfig): Promise<Traced<T>> {
  try {
    const response = await http.request<ApiEnvelope<T>>(config);
    return { data: response.data.data, traceId: response.data.traceId };
  } catch (error) {
    return toApiError(error);
  }
}

export type QueryParams = Record<string, string | number | boolean | undefined>;

/** 调用方显式给定的幂等键仍由本文件写入，任何上层模块不得自己拼这个头。 */
function keyHeaders(idempotencyKey?: string): Record<string, string> | undefined {
  return idempotencyKey === undefined ? undefined : { [IDEMPOTENCY_HEADER]: idempotencyKey };
}

export function get<T>(path: string, params?: QueryParams): Promise<T> {
  return request<T>({ method: 'get', url: path, params });
}

export function post<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  return request<T>({ method: 'post', url: path, data: body, headers: keyHeaders(idempotencyKey) });
}

export function postWithTrace<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<Traced<T>> {
  return requestWithTrace<T>({ method: 'post', url: path, data: body, headers: keyHeaders(idempotencyKey) });
}

/**
 * `multipart/form-data` 提交（契约上唯一的 multipart 操作面：ICS 课表导入）。
 * 这里**不设** `Content-Type`：手写 `multipart/form-data` 会丢掉浏览器生成的 `boundary`，
 * 服务端连信封都解不开，只能拿到 415。
 */
export function postFormWithTrace<T>(path: string, form: FormData, idempotencyKey?: string): Promise<Traced<T>> {
  return requestWithTrace<T>({ method: 'post', url: path, data: form, headers: keyHeaders(idempotencyKey) });
}

/**
 * 无响应体的写接口（204）。`bearerToken` 用于登出：本地会话已经先清空，
 * 但服务端仍需要它来吊销这一个 `jti`。
 */
export async function postNoContent(path: string, body?: unknown, bearerToken?: string): Promise<void> {
  try {
    await http.request({
      method: 'post',
      url: path,
      data: body,
      headers: bearerToken === undefined ? undefined : { [AUTH_HEADER]: `Bearer ${bearerToken}` },
    });
  } catch (error) {
    return toApiError(error);
  }
}

export function put<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  return request<T>({ method: 'put', url: path, data: body, headers: keyHeaders(idempotencyKey) });
}

export function patch<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  return request<T>({ method: 'patch', url: path, data: body, headers: keyHeaders(idempotencyKey) });
}

/** 契约上返回 204 的删除：没有信封可解包，因此与 `postNoContent` 同样绕开通用解包路径。 */
export async function deleteNoContent(path: string, idempotencyKey?: string): Promise<void> {
  try {
    await http.request({ method: 'delete', url: path, headers: keyHeaders(idempotencyKey) });
  } catch (error) {
    return toApiError(error);
  }
}
