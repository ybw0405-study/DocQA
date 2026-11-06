import { defineStore } from 'pinia';
import { completeUpload, getDocumentStatus, initUpload, uploadPart } from '../api/documents';
import { ApiError } from '../api/errors';
import { newIdempotencyKey } from '../api/http';
import type { CompletedPart, DocumentStatus, PresignedPart, UploadContentType } from '../types/documents';
import { md5OfFile } from '../utils/md5';

/** 契约 `UploadInitRequest.sizeBytes` 上限 50MiB；越界在本地拦下，不浪费一次 MD5 和一趟往返。 */
const MAX_SIZE_BYTES = 52_428_800;
const MAX_FILE_NAME_LENGTH = 255;

/** 契约 `UploadInitRequest.contentType` 只有 4 个枚举值，按扩展名映射；浏览器对 OOXML 的 `File.type` 推断不可靠。 */
const CONTENT_TYPES: Readonly<Record<string, UploadContentType>> = {
  pdf: 'application/pdf',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  txt: 'text/plain',
};

/** 契约 `fileName` 的模式：`^[^/\\<控制字符>]+$`。 */
// eslint-disable-next-line no-control-regex -- 这里要匹配的就是契约禁止的控制字符区间
const ILLEGAL_NAME_CHARACTERS = /[/\\\u0000-\u001F]/;

/** 契约建议 2 秒起指数退避；上限 16 秒，避免解析很慢时把间隔拉得比用户耐心还长。 */
const POLL_FIRST_DELAY_MS = 2_000;
const POLL_MAX_DELAY_MS = 16_000;

/** 轮询总预算；超时后停手并交给列表刷新，不无限期挂着计时器。 */
const POLL_BUDGET_MS = 120_000;

export type UploadPhase = 'hashing' | 'uploading' | 'indexing' | 'done' | 'failed' | 'canceled' | 'stale';

export interface UploadTask {
  id: string;
  fileName: string;
  sizeBytes: number;
  phase: UploadPhase;
  progressBytes: number;
  partsSent: number;
  partCount: number;
  documentId: string | null;
  status: DocumentStatus | null;
  errorCode: string | null;
  detail: string | null;
}

interface Handle {
  controller: AbortController;
  timer: ReturnType<typeof setTimeout> | null;
  wake: (() => void) | null;
}

/** 计时器与取消通道不是视图状态，放在模块级非响应式表里，避免 Vue 代理宿主对象。 */
const handles = new Map<string, Handle>();

function sleep(ms: number, taskId: string): Promise<void> {
  return new Promise((resolve) => {
    const handle = handles.get(taskId);
    if (handle === undefined) {
      resolve();
      return;
    }
    handle.wake = resolve;
    handle.timer = setTimeout(resolve, ms);
  });
}

function contentTypeOf(fileName: string): UploadContentType | null {
  const dot = fileName.lastIndexOf('.');
  if (dot < 0) {
    return null;
  }
  return CONTENT_TYPES[fileName.slice(dot + 1).toLowerCase()] ?? null;
}

/** 本地预检错误：与 DOC-VALIDATION-* 同义但由前端先拦，用 CLIENT- 前缀避免冒充服务端错误码。 */
function localError(status: number, code: string, detail: string): ApiError {
  return new ApiError(status, code, detail, null);
}

function reject(task: UploadTask, error: unknown): void {
  task.phase = 'failed';
  task.detail = error instanceof ApiError ? error.detail : '上传失败，请重试。';
  task.errorCode = error instanceof ApiError ? error.code : null;
}

export const useUploadsStore = defineStore('uploads', {
  state: (): { tasks: UploadTask[] } => ({ tasks: [] }),

  getters: {
    hasActiveTask: (state): boolean =>
      state.tasks.some(
        (task) => task.phase === 'hashing' || task.phase === 'uploading' || task.phase === 'indexing',
      ),

    indexedCount: (state): number => state.tasks.filter((task) => task.phase === 'done').length,
  },

  actions: {
    async upload(file: File): Promise<void> {
      const handle: Handle = { controller: new AbortController(), timer: null, wake: null };
      this.tasks.push({
        id: newIdempotencyKey(),
        fileName: file.name,
        sizeBytes: file.size,
        phase: 'hashing',
        progressBytes: 0,
        partsSent: 0,
        partCount: 0,
        documentId: null,
        status: null,
        errorCode: null,
        detail: null,
      });
      // push 之后取回的是响应式代理，后续必须改它；改入参对象不会驱动视图更新。
      const task = this.tasks[this.tasks.length - 1];
      handles.set(task.id, handle);
      try {
        await this.runPipeline(task, file, handle.controller.signal);
      } catch (error) {
        reject(task, error);
      } finally {
        if (handle.controller.signal.aborted) {
          task.phase = 'canceled';
          task.detail = null;
        }
        handles.delete(task.id);
      }
    },

    cancel(taskId: string): void {
      const handle = handles.get(taskId);
      if (handle === undefined) {
        return;
      }
      handle.controller.abort();
      if (handle.timer !== null) {
        clearTimeout(handle.timer);
      }
      handle.wake?.();
    },

    /** 离开页面前停掉所有轮询与在途分片，避免卸载后仍有定时器在跑。 */
    cancelAll(): void {
      for (const taskId of [...handles.keys()]) {
        this.cancel(taskId);
      }
    },

    clearFinished(): void {
      this.tasks = this.tasks.filter((task) => handles.has(task.id));
    },

    async runPipeline(task: UploadTask, file: File, signal: AbortSignal): Promise<void> {
      const contentType = contentTypeOf(task.fileName);
      if (contentType === null) {
        throw localError(415, 'CLIENT-DOC-TYPE-001', '仅支持 PDF、PPTX、DOCX 和 TXT。');
      }
      if (
        task.fileName.length === 0
        || task.fileName.length > MAX_FILE_NAME_LENGTH
        || ILLEGAL_NAME_CHARACTERS.test(task.fileName)
      ) {
        throw localError(400, 'CLIENT-DOC-NAME-001', '文件名不合法，请重命名后再上传。');
      }
      if (task.sizeBytes < 1 || task.sizeBytes > MAX_SIZE_BYTES) {
        throw localError(413, 'CLIENT-DOC-SIZE-001', '文件不能为空，且不能超过 50MiB。');
      }

      const md5 = await md5OfFile(
        file,
        (hashedBytes) => {
          task.progressBytes = hashedBytes;
        },
        signal,
      );

      task.phase = 'uploading';
      const init = await initUpload(
        { fileName: task.fileName, sizeBytes: task.sizeBytes, contentType, md5 },
        task.id,
      );
      task.documentId = init.documentId;
      task.status = init.status;
      task.partCount = init.partCount;
      // 秒传命中时服务端已有同内容对象，既不分片上传也不提交合并。
      if (!init.instantUpload) {
        const parts = await this.sendParts(task, file, init.parts, init.partSize, signal);
        const complete = await completeUpload(init.uploadId, parts, task.id);
        task.documentId = complete.documentId;
        task.status = complete.status;
      }
      if (task.status === 'INDEXED') {
        task.phase = 'done';
        task.progressBytes = task.sizeBytes;
        return;
      }
      await this.pollUntilTerminal(task, init.documentId, signal);
    },

    async sendParts(
      task: UploadTask,
      file: File,
      presigned: PresignedPart[],
      partSize: number,
      signal: AbortSignal,
    ): Promise<CompletedPart[]> {
      const collected: CompletedPart[] = [];
      // 契约要求按 partNumber 升序提交全部分片，乱序返回的预签名列表也照样归一化。
      for (const part of [...presigned].sort((a, b) => a.partNumber - b.partNumber)) {
        const start = (part.partNumber - 1) * partSize;
        const body = file.slice(start, Math.min(start + partSize, file.size));
        const etag = await uploadPart(part.url, body, signal);
        collected.push({ partNumber: part.partNumber, etag });
        task.partsSent += 1;
        task.progressBytes = Math.min(start + body.size, file.size);
      }
      return collected;
    },

    async pollUntilTerminal(task: UploadTask, documentId: string, signal: AbortSignal): Promise<void> {
      task.phase = 'indexing';
      const budgetEndsAt = Date.now() + POLL_BUDGET_MS;
      let delay = POLL_FIRST_DELAY_MS;
      while (!signal.aborted) {
        await sleep(delay, task.id);
        if (signal.aborted) {
          return;
        }
        try {
          const data = await getDocumentStatus(documentId);
          task.status = data.status;
          task.errorCode = data.errorCode;
          if (data.status === 'INDEXED') {
            task.phase = 'done';
            task.detail = null;
            return;
          }
          if (data.status === 'FAILED' || data.status === 'DEAD') {
            task.phase = 'failed';
            // 状态接口的 errorMessage 是契约声明可公开展示的安全文案，仍按纯文本渲染。
            task.detail = data.errorMessage ?? '解析失败，请检查文件内容后重新上传。';
            return;
          }
        } catch (error) {
          // 令牌失效或文档已不存在时继续轮询没有意义；其余错误按「还没好」处理并继续退避。
          if (error instanceof ApiError && (error.status === 401 || error.status === 404)) {
            reject(task, error);
            return;
          }
        }
        if (Date.now() >= budgetEndsAt) {
          // 预算耗尽不代表解析失败，只是前端不再守着；刷新列表仍能看到最终状态。
          task.phase = 'stale';
          task.detail = '文档仍在后台处理，可稍后刷新列表查看结果。';
          return;
        }
        delay = Math.min(delay * 2, POLL_MAX_DELAY_MS);
      }
    },
  },
});
