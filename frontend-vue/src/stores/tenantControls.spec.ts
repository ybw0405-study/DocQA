import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useTenantControlsStore } from './tenantControls';
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

function failTenantRead(status: number, code: string, detail: string): void {
  server.use(
    mswHttp.get('/api/v1/admin/ai/controls/tenant', () =>
      HttpResponse.json(
        problem({ status, code, detail, instance: '/api/v1/admin/ai/controls/tenant' }),
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

describe('tenantControls store', () => {
  let store: ReturnType<typeof useTenantControlsStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useTenantControlsStore();
  });

  it('leaves an unread switch unknown rather than calling it off', async () => {
    expect(store.active).toBeNull();
    expect(store.loaded).toBe(false);

    await store.load();

    expect(store.active).toBe(false);
    expect(store.loaded).toBe(true);
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['GET']);
    expect(callLog.adminOps[0]?.path).toBe('/api/v1/admin/ai/controls/tenant');
  });

  it('adopts the write echo as the effective state without spending a second read', async () => {
    await store.load();
    const ok = await store.setTenantKillSwitch(true);

    expect(ok).toBe(true);
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['GET', 'PUT']);
    expect(store.data).toEqual({
      active: true,
      updatedAt: KILL_SWITCH_WRITE_AT,
      updatedBy: DEMO_USER_ID,
    });
    expect(store.detail).toBeNull();
  });

  it('keeps active=true when the platform switch stays on after turning the tenant key off', async () => {
    adminStubState.killSwitch = { active: true, updatedAt: KILL_SWITCH_WRITE_AT, updatedBy: DEMO_USER_ID };

    const ok = await store.setTenantKillSwitch(false);

    // 这不是写入失败：平台级一键全停仍在生效，回显的 OR 有效状态必须为 true。
    expect(ok).toBe(true);
    expect(store.active).toBe(true);
    expect(adminStubState.tenantKillSwitch.active).toBe(false);
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['PUT']);
  });

  it('re-reads after a 500 because the tenant key may already have flipped', async () => {
    adminStubState.tenantControlsAuditFails = true;

    const ok = await store.setTenantKillSwitch(true);

    expect(ok).toBe(false);
    expect(callLog.adminOps.map((entry) => entry.method)).toEqual(['PUT', 'GET']);
    expect(store.code).toBe('COM-INTERNAL-001');
    expect(store.detail).toContain('状态已变更');
    expect(store.active).toBe(true);
    expect(store.submitting).toBe(false);
  });

  it('keeps the last honest error when the confirming read also fails', async () => {
    adminStubState.tenantControlsAuditFails = true;
    failTenantRead(503, 'AGT-UPSTREAM-003', 'AI 能力当前不可用，请稍后重试。');

    const ok = await store.setTenantKillSwitch(true);

    expect(ok).toBe(false);
    expect(store.code).toBe('AGT-UPSTREAM-003');
    expect(store.data).toBeNull();
  });

  it('serialises writes so a second click cannot overlap the in-flight PUT', async () => {
    const first = store.setTenantKillSwitch(true);
    const second = await store.setTenantKillSwitch(false);
    await first;

    expect(second).toBe(false);
    expect(callLog.adminOps).toHaveLength(1);
  });

  it('carries the code and trace of an admin rejection without a local fallback', async () => {
    failTenantRead(403, 'USR-FORBIDDEN-001', '当前账号没有执行该操作的权限。');

    await store.load();

    expect(store.detail).toBe('当前账号没有执行该操作的权限。');
    expect(store.code).toBe('USR-FORBIDDEN-001');
    expect(store.traceId).toBe(DEMO_TRACE_ID);
    expect(store.active).toBeNull();
    expect(store.loading).toBe(false);
  });

  it('exposes no backoff field because no admin operation is rate-limited', () => {
    expect('retryAfterSeconds' in store.$state).toBe(false);
  });
});
