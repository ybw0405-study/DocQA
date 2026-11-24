/** 契约 `openapi-java.yaml` 2.5.0 中与问答相关的 schema 逐字段镜像，字段名不做本地化改名。 */

export type BudgetStatus = 'OK' | 'BUDGET_EXHAUSTED' | 'LOW_CONFIDENCE' | 'PENDING_APPROVAL';

export type MessageRole = 'user' | 'assistant';

/** 契约 `AskRequest.question` 的 `maxLength`。 */
export const QUESTION_MAX_LENGTH = 4000;

/** 契约 `CreateConversationRequest.title` 的 `maxLength`；省略时服务端用「新会话」。 */
export const TITLE_MAX_LENGTH = 200;

/** 契约 `AskRequest.documentIds` 的 `maxItems`；其 `minItems` 为 1，空选择不允许提交。 */
export const DOCUMENT_IDS_MAX = 20;

export interface ConversationListItemData {
  conversationId: string;
  title: string;
  documentScopeIds: string[];
  lastMessageAt: string | null;
  createdAt: string;
}

export interface ConversationListData {
  items: ConversationListItemData[];
  nextCursor: string | null;
  limit: number;
}

export interface CreateConversationRequest {
  title?: string;
  documentScopeIds?: string[];
}

export interface ConversationData {
  conversationId: string;
  title: string;
  documentScopeIds: string[];
  idempotentReplay: boolean;
  createdAt: string;
}

export interface AskRequest {
  question: string;
  documentIds: string[];
  resumeApproved?: boolean;
}

export interface PendingApproval {
  tool: string;
  input: string;
}

export interface Citation {
  documentId: string;
  chunkIndex: number;
  score: number;
  snippet: string;
  fileName?: string;
}

export interface AskData {
  conversationId: string;
  messageId: string;
  answer: string;
  citations: Citation[];
  confidence: number;
  budgetStatus: BudgetStatus;
  tokenUsed: number;
  toolCallsMade: number;
  roundsUsed: number;
  cacheHit: boolean;
  idempotentReplay: boolean;
  createdAt: string;
  pendingApproval?: PendingApproval | null;
}

/** 契约 `AskStreamMeta` 的 `data`：`messageId` 在流首即固定，本地行从第一刻起就能与服务端历史对账。 */
export interface AskStreamMetaPayload {
  conversationId: string;
  messageId: string;
  traceId: string;
  cacheHit: boolean;
}

/** 契约 `AskStreamDelta` 的 `data`：纯增量文本而非快照，空串增量允许出现。 */
export interface AskStreamDeltaPayload {
  text: string;
}

/** 契约 `AskStreamDone` 的 `data` = `AskData` 全部字段 + `traceId`；字段真源以本帧为准。 */
export interface AskStreamDonePayload extends AskData {
  traceId: string;
}

/**
 * 契约的四帧协议在调用方这一侧的可收事件：`meta` 恰好一次且在最前，`delta` 0..n，
 * `done` 是终止帧。`error` 帧不在此列——它由 api 层直接映射成 `ApiError` 抛出，
 * 使流中失败与首字节前的 JSON 拒绝走同一条呈现路径（store 不需要第二个失败分支）。
 */
export type AskStreamEvent =
  | { kind: 'meta'; payload: AskStreamMetaPayload }
  | { kind: 'delta'; payload: AskStreamDeltaPayload }
  | { kind: 'done'; payload: AskStreamDonePayload };

export interface MessageHistoryItemData {
  messageId: string;
  role: MessageRole;
  content: string;
  tokenCount: number;
  traceId: string;
  createdAt: string;
}

export interface MessageHistoryData {
  conversationId: string;
  items: MessageHistoryItemData[];
  nextCursor: string | null;
  limit: number;
}
