import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import {
  completeUpload,
  deleteDocument,
  getDocumentStatus,
  initUpload,
  listDocuments,
  reindexDocument,
  renameDocument,
  replaceDocumentTags,
  retryDocumentParse,
  uploadPart,
} from './documents';
import { ApiError } from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import {
  DEMO_DOCUMENT_ID,
  DEMO_TOKEN,
  DEMO_UPLOAD_ID,
  PART_OBJECT_PATH,
  documentStatusData,
  envelope,
  problem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';
import type { CompletedPart } from '../types/documents';

interface Captured {
  path: string;
  search: string;
  authorization: string | null;
  idempotencyKey: string | null;
  body: unknown;
}

const captured: Captured[] = [];

function record(request: Request, body: unknown): void {
  const url = new URL(request.url);
  captured.push({
    path: url.pathname,
    search: url.search,
    authorization: request.headers.get('Authorization'),
    idempotencyKey: request.headers.get('Idempotency-Key'),
    body,
  });
}

async function expectApiError(action: Promise<unknown>): Promise<ApiError> {
  try {
    await action;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('预期抛出 ApiError，但调用成功了');
}

describe('documents api', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    captured.length = 0;
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('reads the list without an idempotency key and keeps the query parameters verbatim', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 20 }));
      }),
    );

    await listDocuments({ status: 'PARSING', cursor: 'AbC_123', limit: 50 });

    const sent = captured[0];
    expect(sent.idempotencyKey).toBeNull();
    expect(sent.authorization).toBe(`Bearer ${DEMO_TOKEN}`);
    expect(new URLSearchParams(sent.search).get('status')).toBe('PARSING');
    expect(new URLSearchParams(sent.search).get('cursor')).toBe('AbC_123');
    expect(new URLSearchParams(sent.search).get('limit')).toBe('50');
  });

  it('omits an undefined filter instead of sending an empty status', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 20 }));
      }),
    );

    await listDocuments({ cursor: undefined });

    expect(captured[0].search).toBe('');
  });

  it('sends the contract-shaped init body with the key chosen by the caller', async () => {
    server.use(
      mswHttp.post('/api/v1/documents/upload/init', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(
          envelope({
            documentId: DEMO_DOCUMENT_ID,
            uploadId: DEMO_UPLOAD_ID,
            status: 'UPLOADED',
            instantUpload: false,
            partSize: 5_242_880,
            partCount: 0,
            expiresAt: '2026-10-20T10:30:00Z',
            parts: [],
            idempotentReplay: false,
          }),
          { status: 201 },
        );
      }),
    );

    await initUpload(
      {
        fileName: 'calculus-notes.pdf',
        sizeBytes: 1024,
        contentType: 'application/pdf',
        md5: '0123456789abcdef0123456789abcdef',
      },
      'stable-key-for-this-file',
    );

    const sent = captured[0];
    expect(sent.idempotencyKey).toBe('stable-key-for-this-file');
    expect(sent.body).toEqual({
      fileName: 'calculus-notes.pdf',
      sizeBytes: 1024,
      contentType: 'application/pdf',
      md5: '0123456789abcdef0123456789abcdef',
    });
  });

  it('sends no idempotency key on the status read', async () => {
    server.use(
      mswHttp.get('/api/v1/documents/:documentId/status', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(
          envelope({
            documentId: DEMO_DOCUMENT_ID,
            status: 'PARSING',
            retryCount: 0,
            maxRetry: 3,
            errorCode: null,
            errorMessage: null,
            indexVersion: 'v1',
            chunkCount: 0,
            indexedAt: null,
            createdAt: '2026-10-20T10:15:50Z',
            updatedAt: '2026-10-20T10:15:52Z',
          }),
        );
      }),
    );

    const data = await getDocumentStatus(DEMO_DOCUMENT_ID);

    expect(captured[0].path).toBe(`/api/v1/documents/${DEMO_DOCUMENT_ID}/status`);
    expect(captured[0].idempotencyKey).toBeNull();
    expect(data.status).toBe('PARSING');
  });

  it('sends q and tag verbatim on the list read and keeps them out of the cursor', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 20 }));
      }),
    );

    await listDocuments({ q: '梯度 下降', tag: '考研数学', limit: 20 });

    const params = new URLSearchParams(captured[0].search);
    expect(params.get('q')).toBe('梯度 下降');
    expect(params.get('tag')).toBe('考研数学');
    expect(params.has('cursor')).toBe(false);
  });

  it('renames with a PATCH that carries only the contract field', async () => {
    server.use(
      mswHttp.patch('/api/v1/documents/:documentId', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(envelope(documentStatusData(DEMO_DOCUMENT_ID, { title: '第三章笔记' })));
      }),
    );

    const data = await renameDocument(DEMO_DOCUMENT_ID, '第三章笔记', 'rename-key');

    expect(captured[0].path).toBe(`/api/v1/documents/${DEMO_DOCUMENT_ID}`);
    expect(captured[0].idempotencyKey).toBe('rename-key');
    expect(captured[0].body).toEqual({ title: '第三章笔记' });
    expect(data.title).toBe('第三章笔记');
  });

  it('replaces the whole tag set with a PUT and keeps an empty array as 清空', async () => {
    server.use(
      mswHttp.put('/api/v1/documents/:documentId/tags', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(envelope(documentStatusData(DEMO_DOCUMENT_ID, { tags: [] })));
      }),
    );

    const data = await replaceDocumentTags(DEMO_DOCUMENT_ID, [], 'tags-key');

    expect(captured[0].path).toBe(`/api/v1/documents/${DEMO_DOCUMENT_ID}/tags`);
    expect(captured[0].body).toEqual({ tags: [] });
    expect(data.tags).toEqual([]);
  });

  it('resolves a soft delete on the bodyless 204', async () => {
    server.use(
      mswHttp.delete('/api/v1/documents/:documentId', async ({ request }) => {
        record(request, null);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(deleteDocument(DEMO_DOCUMENT_ID, 'delete-key')).resolves.toBeUndefined();

    expect(captured[0].path).toBe(`/api/v1/documents/${DEMO_DOCUMENT_ID}`);
    expect(captured[0].idempotencyKey).toBe('delete-key');
  });

  it('encodes the document id so a path segment cannot be injected', async () => {
    server.use(
      mswHttp.post('/api/v1/documents/:documentId/retry', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope(documentStatusData('a b/c', { status: 'PARSING' })), { status: 202 });
      }),
    );

    await retryDocumentParse('a b/c', 'retry-key');

    expect(captured[0].path).toBe('/api/v1/documents/a%20b%2Fc/retry');
  });

  it('retries and rebuilds without a request body but with the caller key', async () => {
    server.use(
      mswHttp.post('/api/v1/documents/:documentId/retry', async ({ request }) => {
        record(request, await request.text());
        return HttpResponse.json(envelope(documentStatusData(DEMO_DOCUMENT_ID, { status: 'PARSING' })), { status: 202 });
      }),
      mswHttp.post('/api/v1/documents/:documentId/reindex', async ({ request }) => {
        record(request, await request.text());
        return HttpResponse.json(envelope(documentStatusData(DEMO_DOCUMENT_ID, { pendingIndexVersion: 'v2' })), { status: 202 });
      }),
    );

    const retried = await retryDocumentParse(DEMO_DOCUMENT_ID, 'retry-key');
    const rebuilt = await reindexDocument(DEMO_DOCUMENT_ID, 'reindex-key');

    expect(captured[0].body).toBe('');
    expect(captured[0].idempotencyKey).toBe('retry-key');
    expect(retried.status).toBe('PARSING');
    expect(captured[1].path).toBe(`/api/v1/documents/${DEMO_DOCUMENT_ID}/reindex`);
    expect(captured[1].idempotencyKey).toBe('reindex-key');
    expect(rebuilt.pendingIndexVersion).toBe('v2');
  });

  it('turns a rejected rebuild into the ProblemDetail the server sent', async () => {
    server.use(
      mswHttp.post('/api/v1/documents/:documentId/reindex', () =>
        HttpResponse.json(
          problem({
            status: 409,
            code: 'DOC-CONFLICT-001',
            detail: '该文档已有重建任务在途。',
            instance: `/api/v1/documents/${DEMO_DOCUMENT_ID}/reindex`,
          }),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await expectApiError(reindexDocument(DEMO_DOCUMENT_ID, 'any-key'));

    expect(error.status).toBe(409);
    expect(error.code).toBe('DOC-CONFLICT-001');
  });

  it('submits the ascending part list on complete under its own key', async () => {
    const parts: CompletedPart[] = [
      { partNumber: 1, etag: '"5d41402abc4b2a76b9719d911017c592"' },
      { partNumber: 2, etag: '"7d793037a0760186574b0282f2f435e7"' },
    ];
    server.use(
      mswHttp.post('/api/v1/documents/:uploadId/complete', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(
          envelope({
            documentId: DEMO_DOCUMENT_ID,
            uploadId: DEMO_UPLOAD_ID,
            status: 'PARSING',
            parseRequestedAt: '2026-10-20T10:16:00Z',
            retryCount: 0,
            idempotentReplay: false,
          }),
          { status: 202 },
        );
      }),
    );

    await completeUpload(DEMO_UPLOAD_ID, parts, 'complete-key');

    const sent = captured[0];
    expect(sent.path).toBe(`/api/v1/documents/${DEMO_UPLOAD_ID}/complete`);
    expect(sent.idempotencyKey).toBe('complete-key');
    expect(sent.body).toEqual({ parts });
  });

  it('rewrites the presigned part url to a same-origin bucket path and carries no session token', async () => {
    server.use(
      mswHttp.put(PART_OBJECT_PATH, async ({ request }) => {
        record(request, null);
        return new HttpResponse(null, { status: 200, headers: { ETag: '"part-etag"' } });
      }),
    );

    const etag = await uploadPart(
      `http://127.0.0.1:9000${PART_OBJECT_PATH}?partNumber=1&uploadId=${DEMO_UPLOAD_ID}&X-Amz-Signature=deadbeef`,
      new Blob(['bytes']),
    );

    const sent = captured[0];
    expect(etag).toBe('"part-etag"');
    expect(sent.path).toBe(PART_OBJECT_PATH);
    // 签名参数必须原样带上，否则 MinIO 会以 SignatureDoesNotMatch 拒绝。
    expect(sent.search).toContain('partNumber=1');
    expect(sent.search).toContain('X-Amz-Signature=deadbeef');
    expect(sent.authorization).toBeNull();
    expect(sent.idempotencyKey).toBeNull();
  });

  it('refuses a presigned url that points outside the agreed bucket prefix', async () => {
    const error = await expectApiError(
      uploadPart(`http://127.0.0.1:9000/other-bucket/secret.txt?partNumber=1`, new Blob(['bytes'])),
    );

    expect(error.code).toBe('CLIENT-UPLOAD-TARGET-001');
    expect(captured).toHaveLength(0);
  });

  it('treats a 2xx part response without an ETag as a failed write', async () => {
    server.use(
      mswHttp.put(PART_OBJECT_PATH, () =>
        HttpResponse.text('<!doctype html><div id="app"></div>', {
          status: 200,
          headers: { 'Content-Type': 'text/html' },
        }),
      ),
    );

    const error = await expectApiError(
      uploadPart(`http://127.0.0.1:9000${PART_OBJECT_PATH}?partNumber=1`, new Blob(['bytes'])),
    );

    expect(error.code).toBe('CLIENT-UPLOAD-ETAG-001');
    expect(error.status).toBe(200);
  });

  it('maps a rejected part write to a status code only, never the storage error body', async () => {
    server.use(
      mswHttp.put(PART_OBJECT_PATH, () =>
        HttpResponse.text(
          '<Error><Code>SignatureDoesNotMatch</Code><Bucket>docqa-documents</Bucket><StringToSign>PUT</StringToSign></Error>',
          { status: 403, headers: { 'Content-Type': 'application/xml' } },
        ),
      ),
    );

    const error = await expectApiError(
      uploadPart(`http://127.0.0.1:9000${PART_OBJECT_PATH}?partNumber=1`, new Blob(['bytes'])),
    );

    expect(error.status).toBe(403);
    expect(error.detail).toContain('403');
    expect(error.detail).not.toContain('SignatureDoesNotMatch');
    expect(error.detail).not.toContain('docqa-documents');
  });

  it('reads the bucket prefix from the runtime config, not a baked constant (CT-7)', async () => {
    // 运行时把桶名改成 tenant-runtime-bucket：白名单必须随之变化，且默认桶前缀不再放行。
    window.__APP_CONFIG__ = { storageBucket: 'tenant-runtime-bucket' };
    try {
      server.use(
        mswHttp.put('/tenant-runtime-bucket/documents/owner/doc/source', () =>
          new HttpResponse(null, { status: 200, headers: { ETag: '"runtime-etag"' } }),
        ),
      );
      const etag = await uploadPart(
        `http://127.0.0.1:9000/tenant-runtime-bucket/documents/owner/doc/source?partNumber=1`,
        new Blob(['bytes']),
      );
      expect(etag).toBe('"runtime-etag"');

      const rejected = await expectApiError(
        uploadPart(
          `http://127.0.0.1:9000/docqa-documents/documents/owner/doc/source?partNumber=1`,
          new Blob(['bytes']),
        ),
      );
      expect(rejected.code).toBe('CLIENT-UPLOAD-TARGET-001');
    } finally {
      delete window.__APP_CONFIG__;
    }
  });

  it('surfaces the service ProblemDetail for a rejected init', async () => {
    server.use(
      mswHttp.post('/api/v1/documents/upload/init', () =>
        HttpResponse.json(
          problem({
            status: 415,
            code: 'DOC-VALIDATION-002',
            detail: '仅支持 PDF、PPTX、DOCX 和 TXT。',
            instance: '/api/v1/documents/upload/init',
          }),
          { status: 415, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await expectApiError(
      initUpload(
        { fileName: 'sheet.xlsx', sizeBytes: 10, contentType: 'application/pdf', md5: '0'.repeat(32) },
        'any-key',
      ),
    );

    expect(error.code).toBe('DOC-VALIDATION-002');
    expect(error.detail).toBe('仅支持 PDF、PPTX、DOCX 和 TXT。');
  });
});
