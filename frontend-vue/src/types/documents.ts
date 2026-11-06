export type DocumentStatus = 'UPLOADED' | 'PARSING' | 'INDEXED' | 'FAILED' | 'DEAD';

export const DOCUMENT_STATUSES: readonly DocumentStatus[] = [
  'UPLOADED',
  'PARSING',
  'INDEXED',
  'FAILED',
  'DEAD',
];

/** 契约 `VectorConsistency`：两侧行数的「最后一次确认」对照结论，不是跨库实时比对。 */
export type VectorConsistency = 'MATCHED' | 'DIVERGED' | 'UNKNOWN';

export const VECTOR_CONSISTENCIES: readonly VectorConsistency[] = ['MATCHED', 'DIVERGED', 'UNKNOWN'];

/** 契约 `DocumentRenameRequest.title.maxLength`。 */
export const DOCUMENT_TITLE_MAX_LENGTH = 120;

/** 契约 `DocumentTagsUpdateRequest.tags.maxItems`，超出整批拒绝而不是截断。 */
export const DOCUMENT_TAGS_MAX = 20;

/** 契约 `DocumentTagsUpdateRequest.tags` 的数组项级 `maxLength`。 */
export const DOCUMENT_TAG_MAX_LENGTH = 24;

/** 契约参数 `DocumentSearchQuery.maxLength`：`q` 只搜文件名与标题，不搜正文。 */
export const DOCUMENT_QUERY_MAX_LENGTH = 120;

/** 契约参数 `DocumentTagFilter.maxLength`：标签过滤只接受单个标签名。 */
export const DOCUMENT_TAG_FILTER_MAX_LENGTH = 24;

/** 契约 `UploadInitRequest.contentType` 的枚举，超出这四个值服务端直接 415。 */
export type UploadContentType =
  | 'application/pdf'
  | 'application/vnd.openxmlformats-officedocument.presentationml.presentation'
  | 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
  | 'text/plain';

export interface DocumentListItemData {
  documentId: string;
  fileName: string;
  title: string | null;
  tags: string[];
  contentType: string;
  sizeBytes: number;
  status: DocumentStatus;
  retryCount: number;
  maxRetry: number;
  errorCode: string | null;
  indexVersion: string;
  chunkCount: number;
  indexedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentListData {
  items: DocumentListItemData[];
  nextCursor: string | null;
  limit: number;
}

export interface DocumentStatusData {
  documentId: string;
  fileName: string;
  title: string | null;
  tags: string[];
  status: DocumentStatus;
  retryCount: number;
  maxRetry: number;
  errorCode: string | null;
  errorMessage: string | null;
  indexVersion: string;
  pendingIndexVersion: string | null;
  chunkCount: number;
  vectorRows: number | null;
  vectorConfirmedAt: string | null;
  vectorConsistency: VectorConsistency;
  indexedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 契约 `DocumentRenameRequest`：只改展示标题，`fileName` 不可改也不接受该字段。 */
export interface DocumentRenameRequest {
  title: string;
}

/** 契约 `DocumentTagsUpdateRequest`：整集替换语义，空数组表示清空全部标签。 */
export interface DocumentTagsUpdateRequest {
  tags: string[];
}

export interface UploadInitRequest {
  fileName: string;
  sizeBytes: number;
  contentType: UploadContentType;
  md5: string;
}

export interface PresignedPart {
  partNumber: number;
  url: string;
  expiresAt: string;
}

export interface UploadInitData {
  documentId: string;
  uploadId: string;
  status: DocumentStatus;
  instantUpload: boolean;
  partSize: number;
  partCount: number;
  expiresAt: string | null;
  parts: PresignedPart[];
  idempotentReplay: boolean;
}

export interface CompletedPart {
  partNumber: number;
  etag: string;
}

export interface CompleteUploadData {
  documentId: string;
  uploadId: string;
  status: DocumentStatus;
  parseRequestedAt: string;
  retryCount: number;
  idempotentReplay: boolean;
}
