import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useDocumentsStore } from './documents';
import type { DocumentsFilter } from './documents';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import { DEMO_TOKEN, callLog, documentListItem, documentStatusData, envelope, problem } from '../../tests/fixtures';
import { server } from '../../tests/server';
import type { DocumentListItemData } from '../types/documents';

const FIRST_ID = '00000000-0000-0000-0000-000000000001';
const SECOND_ID = '00000000-0000-0000-0000-000000000002';
const FAILED_ID = '00000000-0000-0000-0000-000000000003';

function item(index: number, status: DocumentListItemData['status'] = 'INDEXED') {
  return {
    ...documentListItem(index),
    status,
    retryCount: status === 'FAILED' ? 3 : 0,
    errorCode: status === 'FAILED' ? 'DOC-VALIDATION-003' : null,
    chunkCount: status === 'INDEXED' ? 12 : 0,
  };
}

function filters(overrides: Partial<DocumentsFilter> = {}): DocumentsFilter {
  return { status: null, q: null, tag: null, ...overrides };
}

/** 只回一行 FAILED 的列表桩，用于状态过滤下的重试断言。 */
function failedListHandler(onRead?: () => void) {
  return mswHttp.get('/api/v1/documents', ({ request }) => {
    onRead?.();
    const status = new URL(request.url).searchParams.get('status');
    return HttpResponse.json(
      envelope({
        items: status === 'FAILED' ? [item(3, 'FAILED')] : [item(1)],
        nextCursor: null,
        limit: 20,
      }),
    );
  });
}

describe('documents store', () => {
  let documents: ReturnType<typeof useDocumentsStore>;

  beforeEach(async () => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    documents = useDocumentsStore();
    await documents.loadFirst();
  });

  it('takes the page and the cursor exactly as the server issued them', () => {
    expect(documents.items.map((doc) => doc.documentId)).toEqual([FIRST_ID, SECOND_ID]);
    expect(documents.nextCursor).toBe('PAGE2');
    expect(documents.canLoadMore).toBe(true);
    expect(documents.loaded).toBe(true);
    expect(documents.detail).toBeNull();
  });

  it('appends the next page and reports the end of the list', async () => {
    await documents.loadMore();

    expect(documents.items).toHaveLength(3);
    expect(documents.items[2].status).toBe('FAILED');
    expect(documents.nextCursor).toBeNull();
    expect(documents.canLoadMore).toBe(false);
  });

  it('does not send a second page request after the list ended', async () => {
    const requests: string[] = [];
    server.use(
      mswHttp.get('/api/v1/documents', async ({ request }) => {
        requests.push(new URL(request.url).searchParams.get('cursor') ?? 'first');
        return HttpResponse.json(envelope({ items: [item(1)], nextCursor: null, limit: 20 }));
      }),
    );

    await documents.loadFirst();
    await documents.loadMore();

    expect(requests).toEqual(['first']);
  });

  it('refuses to reuse a cursor the server rejected and falls back to the first page', async () => {
    const cursors: (string | null)[] = [];
    let firstPageRequests = 0;
    server.use(
      mswHttp.get('/api/v1/documents', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        cursors.push(cursor);
        if (cursor === null) {
          firstPageRequests += 1;
          return HttpResponse.json(
            envelope({
              items: [item(1), item(2)],
              nextCursor: firstPageRequests === 1 ? 'STALE' : null,
              limit: 20,
            }),
          );
        }
        return HttpResponse.json(
          problem({ status: 400, code: 'COM-VALIDATION-001', detail: '请求参数校验失败。', instance: '/api/v1/documents' }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    await documents.loadFirst();
    await documents.loadMore();

    expect(cursors).toEqual([null, 'STALE', null]);
    expect(documents.nextCursor).toBeNull();
    expect(documents.canLoadMore).toBe(false);
    expect(documents.detail).toBe('翻页游标已失效，列表已回到第一页。');
    expect(documents.items).toHaveLength(2);
  });

  it('sends the selected status filter and keeps it across reloads', async () => {
    server.use(failedListHandler());

    await documents.setFilters(filters({ status: 'FAILED' }));
    expect(documents.items.map((doc) => doc.documentId)).toEqual([FAILED_ID]);
    expect(documents.hasFilter).toBe(true);

    await documents.loadFirst();
    expect(documents.items).toHaveLength(1);
  });

  it('trims the search box and omits a blank condition instead of sending an empty q', async () => {
    const sent: string[] = [];
    server.use(
      mswHttp.get('/api/v1/documents', ({ request }) => {
        const params = new URL(request.url).searchParams;
        sent.push(`${params.has('q') ? params.get('q') : 'no-q'}|${params.has('tag') ? params.get('tag') : 'no-tag'}`);
        return HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 20 }));
      }),
    );

    await documents.setFilters(filters({ q: ' 梯度下降 ', tag: '考研数学' }));
    await documents.setFilters(filters({ q: ' ', tag: '' }));

    expect(sent).toEqual(['梯度下降|考研数学', 'no-q|no-tag']);
    expect(documents.filter.q).toBeNull();
    expect(documents.hasFilter).toBe(false);
  });

  it('drops the accumulated pages and the cursor whenever a condition changes', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', ({ request }) => {
        const params = new URL(request.url).searchParams;
        if (params.get('tag') === '错题整理') {
          return HttpResponse.json(envelope({ items: [item(2)], nextCursor: null, limit: 20 }));
        }
        const firstPage = !params.has('cursor');
        return HttpResponse.json(
          envelope({
            items: firstPage ? [item(1), item(2)] : [item(3)],
            nextCursor: firstPage ? 'PAGE2' : null,
            limit: 20,
          }),
        );
      }),
    );

    await documents.loadFirst();
    await documents.loadMore();
    expect(documents.items).toHaveLength(3);
    callLog.documentOps.length = 0;

    await documents.setFilters(filters({ tag: '错题整理' }));

    expect(documents.items).toHaveLength(1);
    expect(documents.nextCursor).toBeNull();
  });

  it('applies the newest condition and discards the superseded in-flight response', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', async ({ request }) => {
        const tag = new URL(request.url).searchParams.get('tag');
        if (tag === '慢条件') {
          await new Promise((resolve) => setTimeout(resolve, 40));
          return HttpResponse.json(envelope({ items: [item(2)], nextCursor: null, limit: 20 }));
        }
        return HttpResponse.json(envelope({ items: [item(1)], nextCursor: 'PAGE2', limit: 20 }));
      }),
    );

    // 第一次提交故意不 await：它还在途时第二次条件切换必须照样生效。
    const slow = documents.setFilters(filters({ tag: '慢条件' }));
    await documents.setFilters(filters({ tag: '快条件' }));

    expect(documents.items.map((doc) => doc.documentId)).toEqual([FIRST_ID]);
    expect(documents.nextCursor).toBe('PAGE2');

    await slow;
    // 迟到的过期响应不能改写列表与游标，也不能把已经结束的加载状态重新置真。
    expect(documents.items.map((doc) => doc.documentId)).toEqual([FIRST_ID]);
    expect(documents.nextCursor).toBe('PAGE2');
    expect(documents.loading).toBe(false);
  });

  it('keeps the ProblemDetail code and traceId visible for an operator report', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', () =>
        HttpResponse.json(
          problem({ status: 500, code: 'COM-INTERNAL-001', detail: '服务暂时不可用。', instance: '/api/v1/documents' }),
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    await documents.loadFirst();

    expect(documents.detail).toBe('服务暂时不可用。');
    expect(documents.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
    // 刷新失败只说明「这次没取到」，不把上一屏已由服务端确认的行抹掉；换条件才清空，见下一条对照用例。
    expect(documents.items).toHaveLength(2);
    expect(documents.loading).toBe(false);
  });

  it('leaves an empty list instead of the previous condition rows when a filter change fails', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', () =>
        HttpResponse.json(
          problem({ status: 500, code: 'COM-INTERNAL-001', detail: '服务暂时不可用。', instance: '/api/v1/documents' }),
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    await documents.setFilters(filters({ status: 'FAILED' }));

    expect(documents.items).toHaveLength(0);
    expect(documents.detail).toBe('服务暂时不可用。');
  });

  it('holds a single request when the reload is triggered twice at once', async () => {
    let inFlight = 0;
    let peak = 0;
    server.use(
      mswHttp.get('/api/v1/documents', async () => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 5));
        inFlight -= 1;
        return HttpResponse.json(envelope({ items: [item(1)], nextCursor: null, limit: 20 }));
      }),
    );

    await Promise.all([documents.loadFirst(), documents.loadFirst()]);

    expect(peak).toBe(1);
  });

  it('renames through PATCH with only the contract field and backfills the row', async () => {
    const ok = await documents.rename(FIRST_ID, ' 第三章笔记 ');

    expect(ok).toBe(true);
    expect(callLog.documentOps).toHaveLength(1);
    expect(callLog.documentOps[0].method).toBe('PATCH');
    expect(callLog.documentOps[0].body).toEqual({ title: '第三章笔记' });
    expect(callLog.documentOps[0].key).toMatch(/^[0-9a-f-]{36}$/);
    expect(documents.items[0].title).toBe('第三章笔记');
    // 状态视图没有列表行的 contentType/sizeBytes，回填必须是合并而不是整行替换。
    expect(documents.items[0].sizeBytes).toBe(1025);
  });

  it('rejects a blank or over-long title without spending a request', async () => {
    expect(await documents.rename(FIRST_ID, ' ')).toBe(false);
    expect(documents.detail).toContain('不能为空');
    expect(await documents.rename(FIRST_ID, '标'.repeat(121))).toBe(false);
    expect(documents.detail).toContain('120');

    expect(callLog.documentOps).toHaveLength(0);
    expect(documents.traceId).toBeNull();
  });

  it('normalizes tags before submitting them and keeps what the server echoes', async () => {
    const ok = await documents.replaceTags(FIRST_ID, [' 考研 数学 ', '错题整理']);

    expect(ok).toBe(true);
    expect(callLog.documentOps[0].method).toBe('PUT');
    expect(callLog.documentOps[0].path).toBe(`/api/v1/documents/${FIRST_ID}/tags`);
    expect(callLog.documentOps[0].body).toEqual({ tags: ['考研 数学', '错题整理'] });
    expect(documents.items[0].tags).toEqual(['考研 数学', '错题整理']);
  });

  it('refuses a tag set that the contract would reject instead of silently fixing it', async () => {
    expect(await documents.replaceTags(FIRST_ID, ['考研数学', ' 考研数学 '])).toBe(false);
    expect(documents.detail).toContain('重复');
    expect(await documents.replaceTags(FIRST_ID, Array.from({ length: 21 }, (_, i) => `标签${i}`))).toBe(false);
    expect(documents.detail).toContain('20');
    expect(await documents.replaceTags(FIRST_ID, [' '])).toBe(false);
    expect(documents.detail).toContain('空白');

    expect(callLog.documentOps).toHaveLength(0);
  });

  it('removes the row when the delete resolves with 204', async () => {
    const ok = await documents.remove(FIRST_ID);

    expect(ok).toBe(true);
    expect(callLog.documentOps[0].method).toBe('DELETE');
    expect(callLog.documentOps[0].path).toBe(`/api/v1/documents/${FIRST_ID}`);
    expect(documents.items.map((doc) => doc.documentId)).toEqual([SECOND_ID]);
  });

  it('refuses to retry a document that is not FAILED or DEAD', async () => {
    expect(await documents.retryParse(FIRST_ID)).toBe(false);

    expect(callLog.documentOps).toHaveLength(0);
    expect(documents.detail).toContain('死信');
  });

  it('retries a failed document and refetches the page when the filter no longer matches', async () => {
    let listReads = 0;
    server.use(failedListHandler(() => { listReads += 1; }));
    await documents.setFilters(filters({ status: 'FAILED' }));
    expect(listReads).toBe(1);
    callLog.documentOps.length = 0;

    const ok = await documents.retryParse(FAILED_ID);

    expect(ok).toBe(true);
    expect(callLog.documentOps[0].path).toBe(`/api/v1/documents/${FAILED_ID}/retry`);
    // 状态过滤下回填会留下一行与筛选条件不符的记录，所以按服务端口径重新拉一页。
    expect(listReads).toBe(2);
  });

  it('backfills the retried row in place when no status filter is active', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', () =>
        HttpResponse.json(envelope({ items: [item(3, 'FAILED')], nextCursor: null, limit: 20 })),
      ),
    );
    await documents.loadFirst();
    callLog.documentOps.length = 0;

    await documents.retryParse(FAILED_ID);

    expect(documents.items[0].status).toBe('PARSING');
    expect(documents.items[0].retryCount).toBe(0);
  });

  it('keeps an INDEXED document searchable while the rebuild is in flight', async () => {
    const ok = await documents.reindex(FIRST_ID);

    expect(ok).toBe(true);
    expect(callLog.documentOps[0].path).toBe(`/api/v1/documents/${FIRST_ID}/reindex`);
    expect(documents.items[0].status).toBe('INDEXED');
    expect(documents.items[0].indexVersion).toBe('v1');
  });

  it('refuses to rebuild a document that is not indexed', async () => {
    server.use(failedListHandler());
    await documents.setFilters(filters({ status: 'FAILED' }));

    expect(await documents.reindex(FAILED_ID)).toBe(false);
    expect(callLog.documentOps).toHaveLength(0);
    expect(documents.detail).toContain('已可检索');
  });

  it('serializes row operations so only one write is in flight at a time', async () => {
    let inFlight = 0;
    let peak = 0;
    server.use(
      mswHttp.patch('/api/v1/documents/:documentId', async ({ request }) => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 10));
        inFlight -= 1;
        callLog.documentOps.push({ method: 'PATCH', path: new URL(request.url).pathname, key: null, body: null });
        return HttpResponse.json(envelope(documentStatusData('00000000-0000-0000-0000-000000000001', { title: '并发测试' })));
      }),
    );

    const results = await Promise.all([documents.rename(FIRST_ID, '第一个'), documents.rename(SECOND_ID, '第二个')]);

    expect(peak).toBe(1);
    expect(results).toEqual([true, false]);
    expect(documents.busyId).toBeNull();
  });

  it('opens the index details on demand and closes them on the second click', async () => {
    const requested: string[] = [];
    server.use(
      mswHttp.get('/api/v1/documents/:documentId/status', ({ request, params }) => {
        requested.push(String(params.documentId));
        void request;
        return HttpResponse.json(
          envelope(documentStatusData(String(params.documentId), { vectorConsistency: 'DIVERGED', pendingIndexVersion: 'v2' })),
        );
      }),
    );

    await documents.toggleStatusView(FIRST_ID);
    expect(documents.statusView?.vectorConsistency).toBe('DIVERGED');
    expect(documents.statusView?.pendingIndexVersion).toBe('v2');
    expect(requested).toEqual([FIRST_ID]);

    await documents.toggleStatusView(FIRST_ID);
    expect(documents.statusView).toBeNull();
    expect(requested).toHaveLength(1);
  });

  it('refreshes an open detail panel from the write response instead of re-fetching', async () => {
    let statusReads = 0;
    server.use(
      mswHttp.get('/api/v1/documents/:documentId/status', ({ params }) => {
        statusReads += 1;
        return HttpResponse.json(envelope(documentStatusData(String(params.documentId), { title: '初始标题' })));
      }),
    );

    await documents.toggleStatusView(FIRST_ID);
    expect(statusReads).toBe(1);

    await documents.rename(FIRST_ID, '面板同步');

    expect(statusReads).toBe(1);
    expect(documents.statusViewId).toBe(FIRST_ID);
    expect(documents.statusView?.title).toBe('面板同步');
  });

  it('closes the detail panel of the document it just deleted', async () => {
    await documents.toggleStatusView(FIRST_ID);

    await documents.remove(FIRST_ID);

    expect(documents.statusView).toBeNull();
    expect(documents.statusViewId).toBeNull();
  });

  it('surfaces a 409 conflict and leaves the row untouched', async () => {
    server.use(
      mswHttp.post('/api/v1/documents/:documentId/reindex', () =>
        HttpResponse.json(
          problem({
            status: 409,
            code: 'DOC-CONFLICT-001',
            detail: '该文档已有重建任务在途。',
            instance: '/api/v1/documents/x/reindex',
          }),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const ok = await documents.reindex(FIRST_ID);

    expect(ok).toBe(false);
    expect(documents.detail).toBe('该文档已有重建任务在途。');
    expect(documents.items[0].indexVersion).toBe('v1');
    expect(documents.busyId).toBeNull();
  });
});
