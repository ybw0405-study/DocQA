import axios, { AxiosError, type AxiosResponse } from 'axios';
import type {
  CompletedPart,
  CompleteUploadData,
  DocumentListData,
  DocumentRenameRequest,
  DocumentStatus,
  DocumentStatusData,
  DocumentTagsUpdateRequest,
  UploadInitData,
  UploadInitRequest,
} from '../types/documents';
import {
  ApiError,
  NETWORK_ERROR_CODE,
  UPLOAD_ETAG_ERROR_CODE,
  UPLOAD_TARGET_ERROR_CODE,
  UNKNOWN_ERROR_CODE,
} from './errors';
import { deleteNoContent, get, patch, post, put } from './http';

/**
 * 预签名分片地址的桶前缀。分片 PUT 必须走同源相对路径，才能同时保住
 * `connect-src 'self'`、「前端只用同源相对路径」这条红线，并免掉对象存储的 CORS 与 ETag 暴露配置。
 */
const OBJECT_STORAGE_PREFIX: string = `/${import.meta.env.VITE_MINIO_BUCKET ?? 'docqa-documents'}`;

/** 单个分片上限 5MiB，10 片串行也远超业务请求的 20s 预算，因此分片通道单独放宽。 */
const PART_TIMEOUT_MS = 120_000;

/** 分片通道是独立实例：不带 `http` 实例上的任何拦截器，因此既没有会话令牌，也没有幂等头。 */
const storage = axios.create({ timeout: PART_TIMEOUT_MS });

export interface DocumentListQuery {
  status?: DocumentStatus;
  q?: string;
  tag?: string;
  cursor?: string;
  limit?: number;
}

/** `status`/`q`/`tag` 是 AND 关系；游标不编码过滤条件，切换条件必须回第一页。 */
export function listDocuments(query: DocumentListQuery): Promise<DocumentListData> {
  return get<DocumentListData>('/documents', {
    status: query.status,
    q: query.q,
    tag: query.tag,
    cursor: query.cursor,
    limit: query.limit,
  });
}

function documentPath(documentId: string): string {
  return `/documents/${encodeURIComponent(documentId)}`;
}

export function initUpload(request: UploadInitRequest, idempotencyKey: string): Promise<UploadInitData> {
  return post<UploadInitData>('/documents/upload/init', request, idempotencyKey);
}

export function completeUpload(
  uploadId: string,
  parts: CompletedPart[],
  idempotencyKey: string,
): Promise<CompleteUploadData> {
  return post<CompleteUploadData>(
    `/documents/${encodeURIComponent(uploadId)}/complete`,
    { parts },
    idempotencyKey,
  );
}

export function getDocumentStatus(documentId: string): Promise<DocumentStatusData> {
  return get<DocumentStatusData>(`${documentPath(documentId)}/status`);
}

/** 只改展示标题；`fileName` 不可改，契约上也不接受该字段。 */
export function renameDocument(documentId: string, title: string, idempotencyKey: string): Promise<DocumentStatusData> {
  return patch<DocumentStatusData>(documentPath(documentId), { title } satisfies DocumentRenameRequest, idempotencyKey);
}

/** 整集替换语义：空数组表示清空该文档的全部标签，传的是标签名而不是标签 ID。 */
export function replaceDocumentTags(
  documentId: string,
  tags: string[],
  idempotencyKey: string,
): Promise<DocumentStatusData> {
  return put<DocumentStatusData>(
    `${documentPath(documentId)}/tags`,
    { tags } satisfies DocumentTagsUpdateRequest,
    idempotencyKey,
  );
}

/** 204 只声明 MySQL 侧已不可见，向量侧由 `vector-change-completed` 异步收敛。 */
export function deleteDocument(documentId: string, idempotencyKey: string): Promise<void> {
  return deleteNoContent(documentPath(documentId), idempotencyKey);
}

/** 仅 `FAILED`/`DEAD` 可手动重试；202 表示已重新投递解析，重放返回 200。 */
export function retryDocumentParse(documentId: string, idempotencyKey: string): Promise<DocumentStatusData> {
  return post<DocumentStatusData>(`${documentPath(documentId)}/retry`, undefined, idempotencyKey);
}

/** 在新索引版本上重建，旧版本期间保持可检索；仅 `INDEXED` 且无在途重建可发起，其它状态一律 409。 */
export function reindexDocument(documentId: string, idempotencyKey: string): Promise<DocumentStatusData> {
  return post<DocumentStatusData>(`${documentPath(documentId)}/reindex`, undefined, idempotencyKey);
}

/**
 * 只取 pathname 与 search：签名覆盖的是规范 URI 和查询串，Host 由 dev 代理改写后与签名一致。
 * 同时把目标限定在约定桶前缀内，避免服务端返回的地址被用作任意路径的出口。
 */
function sameOriginTarget(presignedUrl: string): string {
  let parsed: URL;
  try {
    parsed = new URL(presignedUrl);
  } catch {
    throw new ApiError(0, UPLOAD_TARGET_ERROR_CODE, '分片地址无法解析，已停止上传。', null);
  }
  const target = `${parsed.pathname}${parsed.search}`;
  if (!target.startsWith(`${OBJECT_STORAGE_PREFIX}/`)) {
    throw new ApiError(0, UPLOAD_TARGET_ERROR_CODE, '分片地址不在约定的对象存储桶前缀内，已停止上传。', null);
  }
  return target;
}

/** ETag 必须保留原样引号，契约 `CompletedPart.etag` 就是这么声明的。 */
function readEtag(response: AxiosResponse): string | null {
  const raw = (response.headers as Record<string, unknown>).etag;
  if (typeof raw !== 'string') {
    return null;
  }
  const value = raw.trim();
  return value.length === 0 ? null : value;
}

function toStorageError(error: unknown): never {
  if (error instanceof ApiError) {
    throw error;
  }
  if (error instanceof AxiosError) {
    const response = error.response;
    if (response === undefined) {
      throw new ApiError(0, NETWORK_ERROR_CODE, '无法连接对象存储，分片写入未完成。', null);
    }
    // S3 错误体是 XML 且包含对象键，只回显协议状态码。
    throw new ApiError(
      response.status,
      UNKNOWN_ERROR_CODE,
      `对象存储拒绝了这次分片写入（HTTP ${response.status}）。`,
      null,
    );
  }
  throw error;
}

/** 返回该分片的 ETag；静态托管的 SPA 兜底页也会回 200，所以缺 ETag 一律按失败处理。 */
export async function uploadPart(presignedUrl: string, body: Blob, signal?: AbortSignal): Promise<string> {
  const target = sameOriginTarget(presignedUrl);
  let response: AxiosResponse;
  try {
    response = await storage.put(target, body, { signal });
  } catch (error) {
    return toStorageError(error);
  }
  const etag = readEtag(response);
  if (etag === null) {
    throw new ApiError(
      response.status,
      UPLOAD_ETAG_ERROR_CODE,
      '分片响应缺少 ETag，无法确认对象存储已接收该分片。',
      null,
    );
  }
  return etag;
}
