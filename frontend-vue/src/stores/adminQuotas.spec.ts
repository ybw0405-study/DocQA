import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useAdminQuotasStore } from './adminQuotas';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import { QUOTA_LIMIT_MAX } from '../types/admin';
import {
  DEMO_TARGET_USER_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  KILL_SWITCH_WRITE_AT,
  adminStubState,
  aiQuotaItem,
  callLog,
  envelope,
  problem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

const OTHER_USER_ID = '0192f3a4-9d4e-7f32-8a01-1234567890a2';

const searches: string[] = [];

/**
 * 记录查询串的假实现必须复刻默认桩的分页形态（一页两行 + `PAGE2` 锚点），
 * 否则翻页与游标拒绝这两条路径会被静默抹掉，用例只剩第一次请求。
 */
function recordQuotaSearch(): void {
  searches.length = 0;
  server.use(
    mswHttp.get('/api/v1/admin/ai/quotas', ({ request }) => {
      const url = new URL(request.url);
      searches.push(url.search);
      const cursor = url.searchParams.get('cursor');
      if (cursor === null) {
        return HttpResponse.json(
          envelope({ items: [aiQuotaItem(1), aiQuotaItem(2)], nextCursor: 'PAGE2', limit: 50 }),
        );
      }
      return HttpResponse.json(
        problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法或已失效。', instance: '/api/v1/admin/ai/quotas' }),
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }),
  );
}

function failQuotaSave(status: number, code: string, detail: string): void {
  server.use(
    mswHttp.put('/api/v1/admin/ai/quotas/:userId', () =>
      HttpResponse.json(
        problem({ status, code, detail, instance: '/api/v1/admin/ai/quotas' }),
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

describe('adminQuotas store', () => {
  let store: ReturnType<typeof useAdminQuotasStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useAdminQuotasStore();
    searches.length = 0;
  });

  it('loads the newest override rows and offers the next keyset page', async () => {
    await store.loadFirst();

    expect(store.items.map((item) => item.userId)).toEqual([DEMO_TARGET_USER_ID, OTHER_USER_ID]);
    expect(store.nextCursor).toBe('PAGE2');
    expect(store.canLoadMore).toBe(true);
    expect(callLog.adminOps.map((entry) => entry.search)).toEqual(['?limit=50']);
  });

  it('appends the cursor page and stops once the server stops issuing one', async () => {
    await store.loadFirst();
    await store.loadMore();

    expect(store.items).toHaveLength(3);
    expect(store.nextCursor).toBeNull();

    await store.loadMore();
    expect(store.items).toHaveLength(3);
  });

  it('returns to the first page when the cursor is rejected', async () => {
    await store.loadFirst();
    recordQuotaSearch();
    store.nextCursor = 'PAGE3';

    await store.loadMore();

    // keyset 锚定 `updated_at DESC, user_id DESC`：有人改过配额就可能让锚点失效，停在坏游标上等于看不到剩余行。
    expect(searches).toEqual(['?cursor=PAGE3&limit=50', '?limit=50']);
    expect(store.items).toHaveLength(2);
    expect(store.nextCursor).toBe('PAGE2');
    expect(store.detail).toBe('配额翻页游标已失效，列表已回到第一页。');
    expect(store.loading).toBe(false);
  });

  it('re-reads the list after a save instead of splicing the row locally', async () => {
    await store.loadFirst();

    const ok = await store.saveQuota(OTHER_USER_ID, { dailyQuestionLimit: 55, dailyTokenLimit: null });

    expect(ok).toBe(true);
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['GET', 'PUT', 'GET']);
    expect(callLog.adminOps[1]?.body).toEqual({ dailyQuestionLimit: 55, dailyTokenLimit: null });
    const row = store.items.find((item) => item.userId === OTHER_USER_ID);
    // 列表里的这一行来自重取，本地没有把它「预测成新的样子」。
    expect(row?.dailyQuestionLimit).toBe(55);
    expect(row?.dailyTokenLimit).toBeNull();
    expect(row?.updatedAt).toBe(KILL_SWITCH_WRITE_AT);
  });

  it('keeps two nulls as an explicit no-ceiling instead of dropping the keys', async () => {
    await store.loadFirst();

    const ok = await store.saveQuota(DEMO_TARGET_USER_ID, { dailyQuestionLimit: null, dailyTokenLimit: null });

    expect(ok).toBe(true);
    expect(callLog.adminOps[1]?.body).toEqual({ dailyQuestionLimit: null, dailyTokenLimit: null });
    const row = store.items.find((item) => item.userId === DEMO_TARGET_USER_ID);
    expect(row?.dailyQuestionLimit).toBeNull();
  });

  it('refuses an out-of-range ceiling locally without spending a request', async () => {
    const zero = await store.saveQuota(DEMO_TARGET_USER_ID, { dailyQuestionLimit: 0, dailyTokenLimit: 10 });
    const huge = await store.saveQuota(DEMO_TARGET_USER_ID, {
      dailyQuestionLimit: QUOTA_LIMIT_MAX + 1,
      dailyTokenLimit: null,
    });
    const fractional = await store.saveQuota(DEMO_TARGET_USER_ID, {
      dailyQuestionLimit: 1.5,
      dailyTokenLimit: null,
    });

    expect([zero, huge, fractional]).toEqual([false, false, false]);
    expect(callLog.adminOps).toEqual([]);
    expect(store.detail).toContain(String(QUOTA_LIMIT_MAX));
    expect(store.code).toBeNull();
  });

  it('surfaces a missing target user and leaves the loaded list untouched', async () => {
    adminStubState.missingQuotaUsers.add('ghost-user');
    await store.loadFirst();

    const ok = await store.saveQuota('ghost-user', { dailyQuestionLimit: 10, dailyTokenLimit: null });

    expect(ok).toBe(false);
    expect(store.code).toBe('USR-NOT_FOUND-001');
    expect(store.traceId).toBe(DEMO_TRACE_ID);
    expect(store.items).toHaveLength(2);
    expect(store.submitting).toBe(false);
  });

  it('keeps the server 400 wording when a quota write is rejected', async () => {
    failQuotaSave(400, 'COM-VALIDATION-001', 'dailyQuestionLimit 与 dailyTokenLimit 必须同时出现，可为 null。');
    await store.loadFirst();

    const ok = await store.saveQuota(DEMO_TARGET_USER_ID, { dailyQuestionLimit: 10, dailyTokenLimit: 10 });

    expect(ok).toBe(false);
    expect(store.detail).toBe('dailyQuestionLimit 与 dailyTokenLimit 必须同时出现，可为 null。');
    expect(store.items).toHaveLength(2);
  });

  it('serialises writes so a second submit cannot overlap the in-flight PUT', async () => {
    await store.loadFirst();
    const first = store.saveQuota(DEMO_TARGET_USER_ID, { dailyQuestionLimit: 20, dailyTokenLimit: null });
    const second = await store.saveQuota(DEMO_TARGET_USER_ID, { dailyQuestionLimit: 30, dailyTokenLimit: null });
    await first;

    expect(second).toBe(false);
    expect(callLog.adminOps.filter((entry) => entry.method === 'PUT')).toHaveLength(1);
  });
});
