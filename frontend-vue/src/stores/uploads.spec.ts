import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { ApiError } from '../api/errors';
import { useUploadsStore } from './uploads';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import { DEMO_DOCUMENT_ID, DEMO_TOKEN, DEMO_UPLOAD_ID, PART_OBJECT_PATH } from '../../tests/fixtures';
import type {
  CompleteUploadData,
  DocumentStatusData,
  DocumentStatus,
  PresignedPart,
  UploadInitData,
} from '../types/documents';

const api = vi.hoisted(() => ({
  listDocuments: vi.fn(),
  initUpload: vi.fn(),
  completeUpload: vi.fn(),
  getDocumentStatus: vi.fn(),
  uploadPart: vi.fn(),
}));

// HTTP 与预签名细节由 src/api/documents.spec.ts 覆盖；这里只测状态机与退避，用替身换取确定性。
vi.mock('../api/documents', () => api);

function initResponse(data: Partial<UploadInitData> = {}): UploadInitData {
  return {
    documentId: DEMO_DOCUMENT_ID,
    uploadId: DEMO_UPLOAD_ID,
    status: 'UPLOADED',
    instantUpload: false,
    partSize: 5_242_880,
    partCount: 1,
    expiresAt: '2026-10-20T10:30:00Z',
    parts: [{ partNumber: 1, url: `http://127.0.0.1:9000${PART_OBJECT_PATH}?partNumber=1`, expiresAt: '2026-10-20T10:30:00Z' }],
    idempotentReplay: false,
    ...data,
  };
}

function statusResponse(status: DocumentStatus, overrides: Partial<DocumentStatusData> = {}): DocumentStatusData {
  const indexed = status === 'INDEXED';
  return {
    documentId: DEMO_DOCUMENT_ID,
    fileName: 'notes.pdf',
    title: null,
    tags: [],
    status,
    retryCount: 0,
    maxRetry: 3,
    errorCode: null,
    errorMessage: null,
    indexVersion: 'v1',
    pendingIndexVersion: null,
    chunkCount: indexed ? 42 : 0,
    vectorRows: indexed ? 42 : null,
    vectorConfirmedAt: null,
    vectorConsistency: indexed ? 'MATCHED' : 'UNKNOWN',
    indexedAt: null,
    createdAt: '2026-10-20T10:15:50Z',
    updatedAt: '2026-10-20T10:15:52Z',
    ...overrides,
  };
}

const completeResponse: CompleteUploadData = {
  documentId: DEMO_DOCUMENT_ID,
  uploadId: DEMO_UPLOAD_ID,
  status: 'PARSING',
  parseRequestedAt: '2026-10-20T10:16:00Z',
  retryCount: 0,
  idempotentReplay: false,
};

function textFile(content = 'lecture notes'): File {
  return new File([content], 'notes.txt', { type: 'text/plain' });
}

function oversizedFile(): File {
  const file = new File(['small'], 'huge.txt', { type: 'text/plain' });
  // File 的 size 是原型上的 getter，own property 会盖住它，用来模拟 50MiB 上限之外的文件而不必真的分配 50MiB。
  Object.defineProperty(file, 'size', { value: 52_428_801 });
  return file;
}

describe('uploads store', () => {
  let uploads: ReturnType<typeof useUploadsStore>;

  beforeEach(() => {
    vi.useFakeTimers();
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    uploads = useUploadsStore();
    api.uploadPart.mockResolvedValue('"part-etag"');
    api.completeUpload.mockResolvedValue(completeResponse);
  });

  afterEach(() => {
    uploads.cancelAll();
    vi.useRealTimers();
  });

  it('runs hash → init → part PUT → complete → poll and lands on INDEXED', async () => {
    api.initUpload.mockResolvedValue(initResponse());
    const seen: DocumentStatus[] = [];
    api.getDocumentStatus.mockImplementation(async () => {
      const status = seen.length === 0 ? 'PARSING' : 'INDEXED';
      seen.push(status);
      return statusResponse(status);
    });
    const pending = uploads.upload(textFile());

    await vi.runAllTimersAsync();
    await pending;

    const task = uploads.tasks[0];
    expect(task.phase).toBe('done');
    expect(task.status).toBe('INDEXED');
    expect(task.partsSent).toBe(1);
    expect(task.progressBytes).toBe(task.sizeBytes);
    expect(api.uploadPart).toHaveBeenCalledTimes(1);
    expect(api.getDocumentStatus).toHaveBeenCalledTimes(2);
    expect(uploads.indexedCount).toBe(1);
  });

  it('sends the same stable idempotency key for init and complete of one attempt', async () => {
    api.initUpload.mockResolvedValue(initResponse({ status: 'INDEXED' }));
    api.getDocumentStatus.mockResolvedValue(statusResponse('INDEXED'));
    const pending = uploads.upload(textFile());

    await vi.runAllTimersAsync();
    await pending;

    const initKey = api.initUpload.mock.calls[0][1];
    const completeKey = api.completeUpload.mock.calls[0][2];
    expect(initKey).toBe(uploads.tasks[0].id);
    expect(completeKey).toBe(uploads.tasks[0].id);
    expect(String(initKey).length).toBeGreaterThanOrEqual(8);
  });

  it('uploads parts in ascending order even when the presigned list is shuffled', async () => {
    const parts: PresignedPart[] = [
      { partNumber: 2, url: `http://127.0.0.1:9000${PART_OBJECT_PATH}?partNumber=2`, expiresAt: '2026-10-20T10:30:00Z' },
      { partNumber: 1, url: `http://127.0.0.1:9000${PART_OBJECT_PATH}?partNumber=1`, expiresAt: '2026-10-20T10:30:00Z' },
    ];
    api.initUpload.mockResolvedValue(initResponse({ partCount: 2, parts }));
    api.getDocumentStatus.mockResolvedValue(statusResponse('INDEXED'));
    const deck = new File([new Uint8Array(6 * 1024 * 1024).fill(7)], 'deck.txt', { type: 'text/plain' });
    const pending = uploads.upload(deck);

    await vi.runAllTimersAsync();
    await pending;

    const submitted = api.completeUpload.mock.calls[0][1] as { partNumber: number; etag: string }[];
    expect(submitted.map((part) => part.partNumber)).toEqual([1, 2]);
    expect(uploads.tasks[0].partsSent).toBe(2);
    expect(uploads.tasks[0].progressBytes).toBe(deck.size);
  });

  it('skips the part PUT and the complete call on an instant upload', async () => {
    api.initUpload.mockResolvedValue(initResponse({ instantUpload: true, status: 'INDEXED', partCount: 0, parts: [] }));

    await uploads.upload(textFile());

    expect(uploads.tasks[0].phase).toBe('done');
    expect(api.uploadPart).not.toHaveBeenCalled();
    expect(api.completeUpload).not.toHaveBeenCalled();
    expect(api.getDocumentStatus).not.toHaveBeenCalled();
  });

  it('reports a terminal FAILED with the server safe message and stops polling', async () => {
    api.initUpload.mockResolvedValue(initResponse());
    api.getDocumentStatus.mockResolvedValue(
      statusResponse('FAILED', {
        retryCount: 3,
        errorCode: 'DOC-VALIDATION-003',
        errorMessage: '无法解析加密的 PDF，请先解除密码保护。',
      }),
    );
    const pending = uploads.upload(textFile());

    await vi.advanceTimersByTimeAsync(2_000);
    await pending;

    const task = uploads.tasks[0];
    expect(task.phase).toBe('failed');
    expect(task.detail).toBe('无法解析加密的 PDF，请先解除密码保护。');
    expect(task.errorCode).toBe('DOC-VALIDATION-003');
    expect(api.getDocumentStatus).toHaveBeenCalledTimes(1);
  });

  it('backs off 2s, 4s, 8s then caps at 16s until the polling budget ends', async () => {
    const startedAt = Date.now();
    api.initUpload.mockResolvedValue(initResponse());
    const calls: number[] = [];
    api.getDocumentStatus.mockImplementation(async () => {
      calls.push(Date.now());
      return statusResponse('PARSING');
    });
    const pending = uploads.upload(textFile());

    await vi.runAllTimersAsync();
    await pending;

    const gaps = calls.map((at, index) => at - (index === 0 ? startedAt : calls[index - 1]));
    expect(gaps.map((gap) => Math.round(gap / 1_000))).toEqual([2, 4, 8, 16, 16, 16, 16, 16, 16, 16]);
    expect(uploads.tasks[0].phase).toBe('stale');
    expect(uploads.tasks[0].detail).toBe('文档仍在后台处理，可稍后刷新列表查看结果。');

    const stoppedAt = calls.length;
    await vi.advanceTimersByTimeAsync(120_000);
    expect(calls).toHaveLength(stoppedAt);
  });

  it('keeps polling through a transport failure instead of failing the document', async () => {
    api.initUpload.mockResolvedValue(initResponse());
    let first = true;
    api.getDocumentStatus.mockImplementation(async () => {
      if (first) {
        first = false;
        throw new ApiError(0, 'CLIENT-NETWORK-001', '无法连接服务，请检查网络。', null);
      }
      return statusResponse('INDEXED');
    });
    const pending = uploads.upload(textFile());

    await vi.advanceTimersByTimeAsync(2_000);
    await vi.advanceTimersByTimeAsync(4_000);
    await pending;

    expect(uploads.tasks[0].phase).toBe('done');
    expect(api.getDocumentStatus).toHaveBeenCalledTimes(2);
  });

  it('treats a vanished document as terminal rather than retrying until the budget', async () => {
    api.initUpload.mockResolvedValue(initResponse());
    api.getDocumentStatus.mockRejectedValue(new ApiError(404, 'DOC-NOT_FOUND-001', '文档不存在。', null));
    const pending = uploads.upload(textFile());

    await vi.advanceTimersByTimeAsync(60_000);
    await pending;

    expect(uploads.tasks[0].phase).toBe('failed');
    expect(uploads.tasks[0].detail).toBe('文档不存在。');
    expect(api.getDocumentStatus).toHaveBeenCalledTimes(1);
  });

  it('stops polling and marks the task canceled when the user cancels', async () => {
    api.initUpload.mockResolvedValue(initResponse());
    const calls: number[] = [];
    api.getDocumentStatus.mockImplementation(async () => {
      calls.push(Date.now());
      return statusResponse('PARSING');
    });
    const pending = uploads.upload(textFile());

    await vi.advanceTimersByTimeAsync(2_000);
    expect(calls).toHaveLength(1);

    uploads.cancel(uploads.tasks[0].id);
    await pending;

    expect(uploads.tasks[0].phase).toBe('canceled');
    expect(uploads.tasks[0].detail).toBeNull();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(calls).toHaveLength(1);
  });

  it.each([
    ['a type the contract does not allow', () => new File(['x'], 'sheet.xlsx', { type: 'application/octet-stream' }), 'CLIENT-DOC-TYPE-001'],
    ['an empty file', () => new File([], 'empty.txt', { type: 'text/plain' }), 'CLIENT-DOC-SIZE-001'],
    ['a name with a path separator', () => new File(['x'], 'nested/name.txt', { type: 'text/plain' }), 'CLIENT-DOC-NAME-001'],
    ['a file over the 50MiB ceiling', () => oversizedFile(), 'CLIENT-DOC-SIZE-001'],
  ])('rejects %s before touching the network', async (_label, makeFile, expectedCode) => {
    await uploads.upload(makeFile());

    expect(uploads.tasks[0].phase).toBe('failed');
    expect(uploads.tasks[0].errorCode).toBe(expectedCode);
    expect(uploads.tasks[0].detail).not.toBeNull();
    expect(api.initUpload).not.toHaveBeenCalled();
  });

  it('fails the task when the object storage rejects a part', async () => {
    api.initUpload.mockResolvedValue(initResponse());
    api.uploadPart.mockRejectedValue(new ApiError(403, 'CLIENT-UNKNOWN-001', '对象存储拒绝了这次分片写入（HTTP 403）。', null));

    await uploads.upload(textFile());

    expect(uploads.tasks[0].phase).toBe('failed');
    expect(uploads.tasks[0].detail).toBe('对象存储拒绝了这次分片写入（HTTP 403）。');
    expect(api.completeUpload).not.toHaveBeenCalled();
  });

  it('clears only the tasks that already reached an end state', async () => {
    api.initUpload.mockResolvedValue(initResponse());
    api.getDocumentStatus.mockResolvedValue(statusResponse('PARSING'));
    const pending = uploads.upload(textFile());
    await vi.advanceTimersByTimeAsync(2_000);

    await uploads.upload(new File(['x'], 'bad.exe', { type: 'application/octet-stream' }));
    expect(uploads.tasks.map((task) => task.phase)).toEqual(['indexing', 'failed']);

    uploads.clearFinished();
    expect(uploads.tasks.map((task) => task.fileName)).toEqual(['notes.txt']);

    uploads.cancel(uploads.tasks[0].id);
    await pending;
    uploads.clearFinished();
    expect(uploads.tasks).toHaveLength(0);
  });
});
