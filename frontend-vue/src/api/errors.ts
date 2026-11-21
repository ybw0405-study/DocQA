import type { FieldError } from '../types/api';

/** 请求没能到达服务或在传输层失败时使用；它不是契约里登记的业务错误码，避免与 `AGT-*`/`DOC-*` 混淆。 */
export const NETWORK_ERROR_CODE = 'CLIENT-NETWORK-001';

/** 响应有状态码但正文不是 ProblemDetail（例如反向代理直接返回 HTML）时的兜底标记。 */
export const UNKNOWN_ERROR_CODE = 'CLIENT-UNKNOWN-001';

/** 预签名分片地址不指向约定的对象存储桶前缀，前端拒绝向它发送任何字节。 */
export const UPLOAD_TARGET_ERROR_CODE = 'CLIENT-UPLOAD-TARGET-001';

/** 分片 PUT 返回成功但没有 ETag 头，说明响应并非来自对象存储，无法提交本次合并。 */
export const UPLOAD_ETAG_ERROR_CODE = 'CLIENT-UPLOAD-ETAG-001';

/** 流式响应自身不合规：出现契约外事件名、帧序断裂或没有终止帧。 */
export const STREAM_PROTOCOL_ERROR_CODE = 'CLIENT-STREAM-PROTOCOL-001';

/** 流式响应缓冲超过上限：无换行/无终止帧的流会无限堆积，客户端主动断开以免撑爆标签页内存。 */
export const STREAM_BUFFER_OVERFLOW_CODE = 'CLIENT-STREAM-BUFFER-001';

/** 后端 ProblemDetail 的客户端表示；`detail` 已保证不含堆栈、SQL、PII 与密钥，可直接展示。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly traceId: string | null,
    readonly fieldErrors: readonly FieldError[] = [],
    /** 契约只在 429 上声明 `Retry-After`，且形如整秒；其他状态与非法值一律为 null。 */
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(detail);
    this.name = 'ApiError';
  }

  get isNetworkFailure(): boolean {
    return this.status === 0;
  }

  fieldMessage(field: string): string | undefined {
    return this.fieldErrors.find((entry) => entry.field === field)?.message;
  }
}

/**
 * 流式请求拿到 2xx 但不是 `text/event-stream`，或该路由直接 404/405——
 * 说明当前部署不提供 SSE，而不是这次问答失败。store 据此刻以同一幂等键回退非流式 `/ask`。
 */
export class StreamUnavailableError extends Error {
  constructor(readonly status: number) {
    super('当前部署未以事件流应答该请求。');
    this.name = 'StreamUnavailableError';
  }
}

/**
 * 流式响应的行缓冲或单帧 `data:` 行数超过上限。与 `StreamUnavailableError` 区分：
 * 前者是「部署不支持流」，本错误是「响应不合规，已主动断开」。
 * 继承 `ApiError` 是为了让 store 的 `applyFailure` 把明确文案与 code 呈现给用户；
 * 状态用 502 而非 0，避免 `isNetworkFailure` 把安全断开误读成连不上服务。
 */
export class StreamBufferOverflowError extends ApiError {
  constructor() {
    super(502, STREAM_BUFFER_OVERFLOW_CODE, '流式响应缓冲区超过上限，已主动断开连接。', null);
    this.name = 'StreamBufferOverflowError';
  }
}
