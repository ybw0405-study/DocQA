import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useNotificationsStore } from './notifications';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import { NOTIFICATION_IDS_MAX } from '../types/notifications';
import {
  DEMO_NOTIFICATION_READ_AT,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  callLog,
  envelope,
  notificationData,
  problem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

const searchLog: string[] = [];

/**
 * 记录查询串的假实现必须**复刻默认桩的分页形态**：把它写成「一页给到底、没有游标」，
 * 翻页与游标拒绝两条路径就会被静默抹掉，用例只剩第一次请求。
 * `rejectAnyCursor` 用来把「两次读之间锚点失效」这一支显式摆出来。
 */
function recordSearch(options: { rejectAnyCursor?: boolean } = {}): void {
  searchLog.length = 0;
  server.use(
    mswHttp.get('/api/v1/notifications', ({ request }) => {
      const url = new URL(request.url);
      searchLog.push(url.search);
      const unreadOnly = url.searchParams.get('unreadOnly') === 'true';
      const cursor = url.searchParams.get('cursor');
      const visible = [notificationData(1), notificationData(2), notificationData(3)]
        .filter((item) => !unreadOnly || item.readAt === null);
      if (cursor === null) {
        return HttpResponse.json(envelope({
          items: visible.slice(0, 2),
          nextCursor: visible.length > 2 ? 'PAGE2' : null,
          limit: 20,
        }));
      }
      if (!options.rejectAnyCursor && cursor === 'PAGE2') {
        return HttpResponse.json(envelope({ items: visible.slice(2), nextCursor: null, limit: 20 }));
      }
      return HttpResponse.json(
        problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法。', instance: '/api/v1/notifications' }),
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }),
  );
}

function readFailure(status: number, code: string, detail: string) {
  server.use(
    mswHttp.post('/api/v1/notifications/read', () =>
      HttpResponse.json(
        problem({ status, code, detail, instance: '/api/v1/notifications/read' }),
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

describe('notifications store', () => {
  let store: ReturnType<typeof useNotificationsStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useNotificationsStore();
    searchLog.length = 0;
  });

  it('loads the newest two rows and counts unread inside the loaded pages only', async () => {
    await store.loadListFirst();

    expect(store.items.map((item) => item.notificationId)).toEqual([
      notificationData(1).notificationId,
      notificationData(2).notificationId,
    ]);
    expect(store.nextCursor).toBe('PAGE2');
    // 第 2 行是已读样本：未读数必须是「已加载页里 readAt 为 null 的行数」，不是全量未读数。
    expect(store.unreadCount).toBe(1);
    expect(store.canLoadMore).toBe(true);
  });

  it('appends the cursor page and stops offering more once the server stops issuing one', async () => {
    await store.loadListFirst();
    await store.loadListMore();

    expect(store.items).toHaveLength(3);
    expect(store.unreadCount).toBe(2);
    expect(store.nextCursor).toBeNull();

    await store.loadListMore();
    expect(store.items).toHaveLength(3);
  });

  it('returns to the first page when the cursor is rejected instead of staying on a dead anchor', async () => {
    recordSearch({ rejectAnyCursor: true });
    await store.loadListFirst();
    store.nextCursor = 'PAGE3';

    await store.loadListMore();

    // 两次读之间有新通知落库就会让 keyset 锚点失效，停在坏游标上等于永远看不到剩余未读。
    expect(searchLog).toEqual([
      '?unreadOnly=false&limit=20',
      '?unreadOnly=false&cursor=PAGE3&limit=20',
      '?unreadOnly=false&limit=20',
    ]);
    expect(store.items).toHaveLength(2);
    expect(store.listLoading).toBe(false);
    expect(store.detail).toBe('通知翻页游标已失效，列表已回到第一页。');
  });

  it('drops appended rows and re-requests when the unread-only filter changes', async () => {
    recordSearch();
    await store.loadListFirst();
    await store.loadListMore();

    await store.setUnreadOnly(true);

    expect(store.unreadOnly).toBe(true);
    expect(searchLog[2]).toBe('?unreadOnly=true&limit=20');
    // 过滤条件是服务端每页重新求值的，换过滤就不能再留着混合视图下拼好的行。
    expect(store.items).toHaveLength(2);
    expect(store.items.every((item) => item.readAt === null)).toBe(true);

    await store.setUnreadOnly(true);
    expect(searchLog).toHaveLength(3);
  });

  it('refuses an empty selection locally without spending a request', async () => {
    const ok = await store.markRead([]);

    expect(ok).toBe(false);
    expect(store.detail).toBe('请先勾选要标记已读的通知。');
    expect(callLog.notificationReads).toEqual([]);
    expect(store.marking).toBe(false);
  });

  it('refuses an over-limit batch locally because the contract caps ids at maxItems', async () => {
    const ids = Array.from({ length: NOTIFICATION_IDS_MAX + 1 }, (_, i) => `n-${String(i)}`);

    const ok = await store.markRead(ids);

    expect(ok).toBe(false);
    expect(store.detail).toContain(String(NOTIFICATION_IDS_MAX));
    expect(callLog.notificationReads).toEqual([]);
  });

  it('marks a batch read and then re-reads the server truth instead of stamping readAt locally', async () => {
    await store.loadListFirst();
    const firstId = String(notificationData(1).notificationId);

    const ok = await store.markRead([firstId]);

    expect(ok).toBe(true);
    expect(callLog.notificationReads).toEqual([{ all: false, ids: [firstId] }]);
    expect(store.lastUpdated).toBe(1);
    // 收敛操作：成功后重新取第一页，面板上每一个字段都来自服务端，前端不预测哪一行会变灰。
    expect(store.items.find((item) => item.notificationId === firstId)?.readAt).toBe(DEMO_NOTIFICATION_READ_AT);
    // 首页两行这时都已是已读（第 2 行本来就是），未读数跟着服务端的行重算而不是本地减一。
    expect(store.unreadCount).toBe(0);
  });

  it('does not depend on already-loaded ids for mark-all-read', async () => {
    const ok = await store.markAllRead();

    expect(ok).toBe(true);
    // 「全部未读」由服务端按用户求值，未加载页的未读项同样会被标记，这是勾选提交替代不了它的原因。
    expect(callLog.notificationReads).toEqual([{ all: true }]);
    expect(store.items.every((item) => item.readAt !== null)).toBe(true);
    expect(store.unreadCount).toBe(0);
  });

  it('reports zero rows flipped as a legitimate outcome rather than a failure', async () => {
    await store.markAllRead();
    await store.markRead([String(notificationData(3).notificationId)]);

    // 全部已读之后逐条提交必然 updated=0：0 不是错误，但也不能说成「已标记 0 条为已读」而不加解释。
    expect(store.lastUpdated).toBe(0);
    expect(store.detail).toBeNull();
    expect(store.code).toBeNull();
  });

  it('keeps the code and trace from a rejected mark and leaves the list untouched', async () => {
    readFailure(400, 'COM-VALIDATION-001', 'all=false 时 ids 至少需要一项。');
    await store.loadListFirst();

    const ok = await store.markRead([String(notificationData(1).notificationId)]);

    expect(ok).toBe(false);
    expect(store.detail).toBe('all=false 时 ids 至少需要一项。');
    expect(store.code).toBe('COM-VALIDATION-001');
    expect(store.traceId).toBe(DEMO_TRACE_ID);
    expect(store.lastUpdated).toBeNull();
    expect(store.marking).toBe(false);
  });

  it('serialises marks so a second click cannot overlap the in-flight one', async () => {
    await store.loadListFirst();
    const first = store.markRead([String(notificationData(1).notificationId)]);
    const second = await store.markAllRead();

    expect(second).toBe(false);
    await first;
    // 被挡住的那一次根本不该发请求，否则两次标记的 updated 计数就分不清归属。
    expect(callLog.notificationReads).toHaveLength(1);
  });

  it('exposes no backoff field because neither notification endpoint is rate-limited', async () => {
    readFailure(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。');

    const ok = await store.markAllRead();

    // 契约把通知的两个接口放在限流之外，面板因此不能凭空造一个等待窗口。
    expect(ok).toBe(false);
    expect('retryAfterSeconds' in store.$state).toBe(false);
    expect(store.code).toBe('COM-RATE_LIMIT-001');
  });
});
