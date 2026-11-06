import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useAdminUsersStore } from './adminUsers';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import {
  DEMO_TARGET_USER_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  DEMO_USER_ID,
  callLog,
  envelope,
  problem,
  userSummaryRow,
  usersStubState,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

const searches: string[] = [];

/**
 * 记录查询串的假实现必须复刻默认桩的分页形态（一页两行 + `PAGE2` 锚点），
 * 但把「带上任何游标」一律判成失效，否则坏游标这条路径会被静默抹掉。
 */
function recordUserSearch(): void {
  searches.length = 0;
  server.use(
    mswHttp.get('/api/v1/users', ({ request }) => {
      const url = new URL(request.url);
      searches.push(url.search);
      if (url.searchParams.get('cursor') === null) {
        return HttpResponse.json(
          envelope({ items: [userSummaryRow(1), userSummaryRow(2)], nextCursor: 'PAGE2', limit: 20 }),
        );
      }
      return HttpResponse.json(
        problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法或已失效。', instance: '/api/v1/users' }),
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }),
  );
}

function failUserList(): void {
  server.use(
    mswHttp.get('/api/v1/users', () =>
      HttpResponse.json(
        problem({ status: 503, code: 'COM-INTERNAL-001', detail: '服务暂不可用。', instance: '/api/v1/users' }),
        { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

describe('adminUsers store', () => {
  let store: ReturnType<typeof useAdminUsersStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useAdminUsersStore();
    searches.length = 0;
  });

  it('loads the newest rows with the contract default limit and offers the next page', async () => {
    await store.loadFirst();

    expect(callLog.userOps.map((entry) => entry.search)).toEqual(['?limit=20']);
    expect(store.items.map((item) => item.userId)).toEqual([DEMO_USER_ID, DEMO_TARGET_USER_ID]);
    expect(store.nextCursor).toBe('PAGE2');
    expect(store.canLoadMore).toBe(true);
    expect(store.hasFilter).toBe(false);
    expect([store.loading, store.loaded]).toEqual([false, true]);
  });

  it('appends the cursor page and stops trying once the server stops issuing one', async () => {
    await store.loadFirst();
    await store.loadMore();

    expect(store.items).toHaveLength(4);
    expect(store.nextCursor).toBeNull();

    await store.loadMore();
    expect(callLog.userOps).toHaveLength(2);
    expect(store.items).toHaveLength(4);
  });

  it('returns to the first page when a carried cursor is rejected', async () => {
    recordUserSearch();
    await store.loadFirst();

    await store.loadMore();

    // 停在坏游标上只会永远看不到更旧的行：回到第一页并把这条判断说出口，比静默空转更诚实。
    expect(searches).toEqual(['?limit=20', '?cursor=PAGE2&limit=20', '?limit=20']);
    expect(store.nextCursor).toBe('PAGE2');
    expect(store.detail).toBe('用户翻页游标已失效，列表已回到第一页。');
    expect(store.code).toBeNull();
    expect(store.loading).toBe(false);
  });

  it('treats a filter change as a fresh first page rather than a third appended page', async () => {
    await store.loadFirst();
    const ok = await store.applyFilters({ status: 'DISABLED' });

    expect(ok).toBe(true);
    expect(store.hasFilter).toBe(true);
    // 换条件后已拼接的后续行必须清空，否则界面上会是两套条件的混合结果。
    expect(store.items.map((item) => item.username)).toEqual(['wang.lei']);
    expect(store.nextCursor).toBeNull();
    expect(callLog.userOps.map((entry) => entry.search)).toEqual(['?limit=20', '?status=DISABLED&limit=20']);
  });

  it('reads a role filter as "holds that role" and clears conditions on an empty input', async () => {
    await store.applyFilters({ role: 'ADMIN' });
    expect(store.items.map((item) => item.username)).toEqual(['demo', 'li.na']);

    await store.applyFilters({});
    expect(store.hasFilter).toBe(false);
    expect([store.filterStatus, store.filterRole]).toEqual([null, null]);
    expect(callLog.userOps.map((entry) => entry.search)).toEqual(['?role=ADMIN&limit=20', '?limit=20']);
  });

  it('refuses an empty role set locally without spending a request', async () => {
    const ok = await store.saveRoles(DEMO_TARGET_USER_ID, []);

    // schema 层空数组合法、由服务端按策略 422，但这条挡住的是「零角色账号根本不该存在」，
    // 所以本地先挡一次；挡住的表述只能是「不会被接受」，不能写成「前端已防止」。
    expect(ok).toBe(false);
    expect(callLog.userOps).toEqual([]);
    expect(store.detail).toBe('至少要为账号保留一个角色，空集合不会被服务端接受。');
    expect(store.code).toBeNull();
    expect(store.traceId).toBeNull();
  });

  it('merges the echoed row in place and does not refetch the page to confirm it', async () => {
    await store.loadFirst();
    const ok = await store.saveRoles(DEMO_TARGET_USER_ID, ['USER', 'ADMIN']);

    expect(ok).toBe(true);
    const reads = callLog.userOps.filter((entry) => entry.method === 'GET');
    // 排序键 created_at 不可变，写成功后这一行的位置不变：重取整页只会丢掉管理员刚翻到的位置。
    expect(reads).toHaveLength(1);
    expect(store.items.map((item) => item.userId)).toEqual([DEMO_USER_ID, DEMO_TARGET_USER_ID]);
    expect(store.items[1]?.roles).toEqual(['USER', 'ADMIN']);
    expect(usersStubState.roleOverrides.get(DEMO_TARGET_USER_ID)).toEqual(['USER', 'ADMIN']);
  });

  it('drops a row that no longer matches the active filter after a successful write', async () => {
    await store.applyFilters({ status: 'ACTIVE' });
    expect(store.items).toHaveLength(2);

    const ok = await store.setStatus(DEMO_TARGET_USER_ID, 'DISABLED');

    // 服务端在每一页重新求值条件，所以这一行确实出局了；视图据 `goneNote` 说明它为什么不见了。
    expect(ok).toBe(true);
    expect(store.items.map((item) => item.userId)).toEqual([DEMO_USER_ID]);
    expect(usersStubState.statusOverrides.get(DEMO_TARGET_USER_ID)).toBe('DISABLED');
  });

  it('leaves the loaded row untouched when the server rejects the write', async () => {
    await store.loadFirst();
    const ok = await store.setStatus(DEMO_USER_ID, 'DISABLED');

    expect(ok).toBe(false);
    expect(store.detail).toBe('不能停用当前调用者自身的账号。');
    expect(store.code).toBe('USR-VALIDATION-001');
    expect(store.traceId).toBe(DEMO_TRACE_ID);
    expect(store.items[0]?.status).toBe('ACTIVE');
    expect(usersStubState.statusOverrides.size).toBe(0);
  });

  it('clears a previous failure once a read succeeds', async () => {
    failUserList();
    await store.loadFirst();
    expect(store.code).toBe('COM-INTERNAL-001');

    server.resetHandlers();
    await store.loadFirst();

    // 上一次失败的红条不许在成功读取之后继续挂着：那会把「已经能看到列表」说成还是坏的。
    expect([store.detail, store.code, store.traceId]).toEqual([null, null, null]);
    expect(store.items).toHaveLength(2);
    expect(store.nextCursor).toBe('PAGE2');
  });

  it('serialises writes so a second submit cannot overlap the in-flight PUT', async () => {
    await store.loadFirst();
    const first = store.saveRoles(DEMO_TARGET_USER_ID, ['USER']);
    const second = await store.saveRoles(DEMO_TARGET_USER_ID, ['USER', 'ADMIN']);
    await first;

    // 角色是整集替换，两次并发提交会互相覆盖成后写那一套；契约上每次点击又是一个新幂等键，
    // 服务端不会把第二次的请求体认成第一次的重放，所以这把本地锁是承重的。
    expect(second).toBe(false);
    expect(callLog.userOps.filter((entry) => entry.method === 'PUT')).toHaveLength(1);
  });
});
