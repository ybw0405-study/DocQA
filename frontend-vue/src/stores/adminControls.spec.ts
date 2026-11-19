import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useAdminControlsStore } from './adminControls';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import {
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  DEMO_USER_ID,
  KILL_SWITCH_WRITE_AT,
  adminStubState,
  callLog,
  problem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

function failControlsRead(status: number, code: string, detail: string): void {
  server.use(
    mswHttp.get('/api/v1/admin/ai/controls', () =>
      HttpResponse.json(
        problem({ status, code, detail, instance: '/api/v1/admin/ai/controls' }),
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

describe('adminControls store', () => {
  let store: ReturnType<typeof useAdminControlsStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useAdminControlsStore();
  });

  it('leaves an unread switch unknown rather than calling it off', async () => {
    expect(store.killSwitchActive).toBeNull();
    expect(store.loaded).toBe(false);

    await store.load();

    expect(store.killSwitchActive).toBe(false);
    expect(store.loaded).toBe(true);
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['GET']);
  });

  it('adopts the write response as the state without spending a second read', async () => {
    await store.load();
    const ok = await store.setKillSwitch(true);

    expect(ok).toBe(true);
    // 契约的 PUT 回显写入后的完整状态，采纳它就是采纳服务端事实，不是乐观合入。
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['GET', 'PUT']);
    expect(store.data?.killSwitch).toEqual({
      active: true,
      updatedAt: KILL_SWITCH_WRITE_AT,
      updatedBy: DEMO_USER_ID,
    });
    expect(store.detail).toBeNull();
  });

  it('re-reads after a 500 because the switch may already have flipped', async () => {
    adminStubState.controlsAuditFails = true;

    const ok = await store.setKillSwitch(true);

    // 先写 Redis 再插审计：这条 500 说的是审计失败，界面不能把它呈现成「什么都没发生」。
    expect(ok).toBe(false);
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['PUT', 'GET']);
    expect(store.code).toBe('COM-INTERNAL-001');
    expect(store.detail).toContain('开关状态已变更');
    expect(store.killSwitchActive).toBe(true);
    expect(store.submitting).toBe(false);
  });

  it('keeps the last honest error when the confirming read also fails', async () => {
    adminStubState.controlsAuditFails = true;
    failControlsRead(503, 'AGT-UPSTREAM-003', 'AI 能力当前不可用，请稍后重试。');

    const ok = await store.setKillSwitch(true);

    // 重读失败时屏幕上留下的必须是「确认失败」这条，而不是一个来路不明的旧值。
    expect(ok).toBe(false);
    expect(store.code).toBe('AGT-UPSTREAM-003');
    expect(store.data).toBeNull();
  });

  it('serialises writes so a second click cannot overlap the in-flight PUT', async () => {
    const first = store.setKillSwitch(true);
    const second = await store.setKillSwitch(false);
    await first;

    expect(second).toBe(false);
    expect(callLog.adminOps).toHaveLength(1);
  });

  it('reports unpublished ceilings as unknown instead of zero', async () => {
    adminStubState.limitsKnown = false;

    await store.load();

    expect(store.limitsKnown).toBe(false);
    expect(store.data?.experimentBudget.callLimit).toBeNull();
    expect(store.data?.experimentBudget.tokensUsed).toBe(2096);
  });

  it('carries the code and trace of an admin rejection without a local fallback', async () => {
    failControlsRead(403, 'USR-FORBIDDEN-001', '当前账号没有执行该操作的权限。');

    await store.load();

    expect(store.detail).toBe('当前账号没有执行该操作的权限。');
    expect(store.code).toBe('USR-FORBIDDEN-001');
    expect(store.traceId).toBe(DEMO_TRACE_ID);
    expect(store.killSwitchActive).toBeNull();
    expect(store.loading).toBe(false);
  });

  it('exposes no backoff field because no admin operation is rate-limited', () => {
    // 管理面八个操作的响应集合里没有 429，管理面因此不能凭空造一个等待窗口。
    expect('retryAfterSeconds' in store.$state).toBe(false);
  });
});
