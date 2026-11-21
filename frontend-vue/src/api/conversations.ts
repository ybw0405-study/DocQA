import type {
  AskData,
  AskRequest,
  AskStreamDonePayload,
  AskStreamEvent,
  AskStreamMetaPayload,
  ConversationData,
  ConversationListData,
  CreateConversationRequest,
  MessageHistoryData,
} from '../types/conversations';
import type { ProblemDetail } from '../types/api';
import { get, isProblemDetail, post, postWithTrace, type Traced } from './http';
import { postSseStream } from './sse';
import { ApiError, STREAM_PROTOCOL_ERROR_CODE } from './errors';

export interface PageQuery {
  cursor?: string;
  limit?: number;
}

export function listConversations(query: PageQuery): Promise<ConversationListData> {
  return get<ConversationListData>('/conversations', { cursor: query.cursor, limit: query.limit });
}

export function createConversation(
  request: CreateConversationRequest,
  idempotencyKey: string,
): Promise<ConversationData> {
  return post<ConversationData>('/conversations', request, idempotencyKey);
}

/**
 * 提问必须拿到成功信封的 `traceId`：`AskData` 里没有该字段，而消息历史接口每条都带，
 * 两者指向同一次落库，缺了它本地行与刷新后的行就无法对上。
 */
export function askConversation(
  conversationId: string,
  request: AskRequest,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<Traced<AskData>> {
  return postWithTrace<AskData>(
    `/conversations/${encodeURIComponent(conversationId)}/ask`,
    request,
    idempotencyKey,
    signal,
  );
}

export function listConversationMessages(
  conversationId: string,
  query: PageQuery,
): Promise<MessageHistoryData> {
  return get<MessageHistoryData>(`/conversations/${encodeURIComponent(conversationId)}/messages`, {
    cursor: query.cursor,
    limit: query.limit,
  });
}

/**
 * 流式问答（契约 2.5.0 `askConversationStream`）。四帧协议的帧序在这里判定：
 * `meta` 恰好一次且在最前，`delta` 0..n，`done` / `error` 互为替代且是最后一帧。
 * 治理拒绝不升级 SSE（首字节前仍是 JSON ProblemDetail），由 `api/sse.ts` 直接映射成 `ApiError`。
 */
export async function* askConversationStream(
  conversationId: string,
  request: AskRequest,
  idempotencyKey: string,
  signal?: AbortSignal,
): AsyncGenerator<AskStreamEvent, void, void> {
  let meta: AskStreamMetaPayload | null = null;
  let terminal = false;
  const path = `/conversations/${encodeURIComponent(conversationId)}/ask:stream`;
  for await (const frame of postSseStream({ path, body: request, idempotencyKey, signal })) {
    if (terminal) {
      throw streamProtocolError(`终止帧之后仍继续收到事件「${frame.event}」。`);
    }
    const payload = parsePayload(frame.data, frame.event);
    if (frame.event === 'meta') {
      if (meta !== null) {
        throw streamProtocolError('meta 帧出现了第二次。');
      }
      meta = requireMetaFields(payload);
      yield { kind: 'meta', payload: meta };
      continue;
    }
    if (meta === null) {
      throw streamProtocolError(`首个事件帧不是 meta，而是「${frame.event}」。`);
    }
    if (frame.event === 'delta') {
      const text = (payload as { text?: unknown }).text;
      if (typeof text !== 'string') {
        throw streamProtocolError('delta 帧缺少字符串字段 text。');
      }
      yield { kind: 'delta', payload: { text } };
      continue;
    }
    if (frame.event === 'done') {
      const data = requireDoneFields(payload) as AskStreamDonePayload;
      terminal = true;
      yield { kind: 'done', payload: data };
      continue;
    }
    if (frame.event === 'error') {
      if (!isProblemDetail(payload)) {
        throw streamProtocolError('error 帧的载荷不是 ProblemDetail 形态。');
      }
      const problem = payload as ProblemDetail;
      // error 帧即本次问答的失败结论：调用方拿到 ApiError 后按既有失败呈现，不再继续读流。
      throw new ApiError(
        problem.status,
        problem.code,
        problem.detail,
        problem.traceId ?? null,
        problem.errors ?? [],
      );
    }
    throw streamProtocolError(`契约之外的事件名「${frame.event}」。`);
  }
  if (signal?.aborted === true) {
    // 主动断开导致的「没有终止帧」不是协议违例，交给调用方按中断处理。
    return;
  }
  if (!terminal) {
    throw streamProtocolError('事件流在没有终止帧的情况下结束。');
  }
}

function parsePayload(data: string, event: string): unknown {
  try {
    return JSON.parse(data) as unknown;
  } catch {
    throw streamProtocolError(`事件「${event}」的 data 不是合法 JSON。`);
  }
}

/** 只校验 store 真正依赖的字段：行键与 traceId 取错会让本地行与服务端历史永久对不上。 */
function requireMetaFields(payload: unknown): AskStreamMetaPayload {
  const record = payload as { conversationId?: unknown; messageId?: unknown; traceId?: unknown; cacheHit?: unknown };
  if (
    typeof record?.messageId !== 'string' ||
    typeof record.traceId !== 'string' ||
    typeof record.conversationId !== 'string' ||
    typeof record.cacheHit !== 'boolean'
  ) {
    throw streamProtocolError('meta 帧缺少 conversationId / messageId / traceId / cacheHit。');
  }
  return {
    conversationId: record.conversationId,
    messageId: record.messageId,
    traceId: record.traceId,
    cacheHit: record.cacheHit,
  };
}

function requireDoneFields(payload: unknown): Partial<AskStreamDonePayload> {
  const record = payload as { messageId?: unknown; answer?: unknown; traceId?: unknown };
  if (typeof record?.messageId !== 'string' || typeof record.answer !== 'string' || typeof record.traceId !== 'string') {
    throw streamProtocolError('done 帧缺少 messageId / answer / traceId。');
  }
  return payload as Partial<AskStreamDonePayload>;
}

/** 502 与契约里的 `ProtocolViolation` 同判据；不能用 0，否则 `isNetworkFailure` 会把协议违例误读成连不上服务。 */
function streamProtocolError(detail: string): ApiError {
  return new ApiError(502, STREAM_PROTOCOL_ERROR_CODE, `流式响应不符合帧协议：${detail}`, null);
}
