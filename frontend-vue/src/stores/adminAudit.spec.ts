import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useAdminAuditStore } from './adminAudit';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import { DEMO_TARGET_USER_ID, DEMO_TOKEN, DEMO_TRACE_ID, DEMO_USER_ID, callLog } from '../../tests/fixtures';

describe('adminAudit store', () => {
  let store: ReturnType<typeof useAdminAuditStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useAdminAuditStore();
  });

  it('loads the newest two events with nothing but the page size', async () => {
    await store.loadFirst();

    expect(callLog.adminOps.map((entry) => entry.search)).toEqual(['?limit=100']);
    expect(store.events).toHaveLength(2);
    expect(store.nextCursor).toBe('PAGE2');
    expect(store.hasFilter).toBe(false);
  });

  it('appends the older page and keeps the severity as the server wrote it', async () => {
    await store.loadFirst();
    await store.loadMore();

    expect(store.events).toHaveLength(3);
    expect(store.nextCursor).toBeNull();
    // 第 3 行是 WARN：管理面不能把治理事件的级别读成统一的 INFO。
    expect(store.events[2]?.severity).toBe('WARN');
    expect(store.events[2]?.eventType).toBe('ADMIN_AI_QUOTA_CLEARED');

    await store.loadMore();
    expect(store.events).toHaveLength(3);
  });

  it('filters by trace id and reports only the rows the server returned', async () => {
    const ok = await store.applyFilters({ traceId: DEMO_TRACE_ID });

    expect(ok).toBe(true);
    expect(callLog.adminOps.map((entry) => entry.search)).toEqual([`?traceId=${DEMO_TRACE_ID}&limit=100`]);
    expect(store.events.map((event) => event.eventId)).toEqual([
      '0192f3a4-b2c3-7d44-8e02-1234567890ab',
      '0192f3a4-b2c3-7d44-8e02-1234567890ac',
    ]);
    expect(store.hasFilter).toBe(true);
  });

  it('trims a condition and omits a blank one instead of sending an empty filter', async () => {
    await store.applyFilters({ traceId: ' ', userId: ` ${DEMO_TARGET_USER_ID} ` });

    expect(callLog.adminOps.map((entry) => entry.search)).toEqual([`?userId=${DEMO_TARGET_USER_ID}&limit=100`]);
    expect(store.filterTraceId).toBeNull();
    expect(store.filterUserId).toBe(DEMO_TARGET_USER_ID);
  });

  it('refuses a malformed trace id locally without spending a request', async () => {
    const ok = await store.applyFilters({ traceId: 'NOT-A-TRACE-ID' });

    expect(ok).toBe(false);
    expect(callLog.adminOps).toEqual([]);
    expect(store.detail).toBe('traceId 必须是 32 位小写十六进制。');
    expect(store.code).toBeNull();
  });

  it('refuses an event type that cannot match the contract pattern', async () => {
    const ok = await store.applyFilters({ eventType: 'rag_retrieved' });

    expect(ok).toBe(false);
    expect(callLog.adminOps).toEqual([]);
    expect(store.detail).toContain('eventType');
  });

  it('drops appended rows when the filter changes', async () => {
    await store.loadFirst();
    await store.loadMore();
    expect(store.events).toHaveLength(3);

    await store.applyFilters({ eventType: 'RAG_RETRIEVED' });

    // 条件是每页重新求值的，换条件就不能再留着混合视图下拼好的行。
    expect(store.events).toHaveLength(1);
    expect(store.events[0]?.traceId).toBe(DEMO_TRACE_ID);
    expect(store.nextCursor).toBeNull();
  });

  it('returns to the first page when the cursor is rejected', async () => {
    await store.loadFirst();
    store.nextCursor = 'PAGE3';

    await store.loadMore();

    expect(callLog.adminOps.map((entry) => entry.search)).toEqual([
      '?limit=100',
      '?cursor=PAGE3&limit=100',
      '?limit=100',
    ]);
    expect(store.events).toHaveLength(2);
    expect(store.detail).toBe('审计翻页游标已失效，列表已回到第一页。');
    expect(store.loading).toBe(false);
  });

  it('leaves a too-wide window to the server instead of guessing the limit locally', async () => {
    const ok = await store.applyFilters({
      from: '2026-01-01T00:00:00Z',
      to: '2026-02-10T00:00:00Z',
    });

    // 31 天只写在契约的 description 里，本地预拦得靠猜；猜出来的上限不是契约，服务端那条 400 才是。
    expect(ok).toBe(true);
    expect(store.detail).toBe('时间窗跨度超过 31 天。');
    expect(store.code).toBe('COM-VALIDATION-001');
    expect(store.filterFrom).toBe('2026-01-01T00:00:00Z');
  });

  it('carries the operator identity of an event row only from contract fields', async () => {
    await store.applyFilters({ eventType: 'ADMIN_AI_QUOTA_UPDATED' });

    // 管理面出现的每一个 id 都是契约字段本身：traceId、userId、payload.targetUserId。
    expect(store.events).toHaveLength(1);
    expect(store.events[0]?.userId).toBe(DEMO_USER_ID);
    expect(store.events[0]?.payload).toEqual({ targetUserId: DEMO_TARGET_USER_ID });
  });
});
