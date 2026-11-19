import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';
import AdminTenantControlsView from './AdminTenantControlsView.vue';
import { bindSessionToAuthStore } from '../../stores/sessionBridge';
import { useTenantControlsStore } from '../../stores/tenantControls';
import { useAuthStore } from '../../stores/auth';
import {
  DEMO_TOKEN,
  DEMO_USER_ID,
  KILL_SWITCH_WRITE_AT,
  adminStubState,
  callLog,
  problem,
} from '../../../tests/fixtures';
import { server } from '../../../tests/server';
import { formatDateTime } from '../../utils/display';

let mounted: ViewWrapper | null = null;

async function mountView() {
  const wrapper = mount(AdminTenantControlsView);
  mounted = wrapper;
  await vi.waitFor(() => expect(useTenantControlsStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

function stateOf(view: ViewWrapper): Map<string, string> {
  const entries = new Map<string, string>();
  for (const row of view.findAll('.admin-state > div')) {
    entries.set((row.find('dt')?.text() ?? '').trim(), (row.find('dd')?.text() ?? '').trim());
  }
  return entries;
}

function byLabel(view: ViewWrapper, label: string) {
  return view.findAll('button').find((button) => button.text() === label);
}

function methods(): string[] {
  return callLog.adminOps.map((entry) => entry.method);
}

function failTenantRead(detail: string): void {
  server.use(
    mswHttp.get('/api/v1/admin/ai/controls/tenant', () =>
      HttpResponse.json(
        problem({ status: 500, code: 'COM-INTERNAL-001', detail, instance: '/api/v1/admin/ai/controls/tenant' }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

async function enableWithConfirmation(view: ViewWrapper): Promise<void> {
  await byLabel(view, '开启本租户 Kill Switch')?.trigger('click');
  await byLabel(view, '确认开启')?.trigger('click');
}

describe('AdminTenantControlsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  afterEach(async () => {
    await vi.waitFor(() => {
      const store = useTenantControlsStore();
      expect(store.loading || store.submitting).toBe(false);
    });
    mounted?.unmount();
    mounted = null;
  });

  it('keeps a failed read as an error and refuses to offer a write entry', async () => {
    failTenantRead('本租户熔断状态读取失败。');
    const view = await mountView();

    expect(view.find('[role="alert"]').text()).toContain('本租户熔断状态读取失败。');
    expect(view.find('[role="alert"]').text()).toContain('COM-INTERNAL-001');
    expect(stateOf(view).get('有效状态')).toBe('未读取');
    expect(byLabel(view, '开启本租户 Kill Switch')).toBeUndefined();
    expect(view.text()).toContain('不提供写入入口');
  });

  it('explains the OR semantics and that the tenant is fixed by the JWT', async () => {
    const view = await mountView();

    expect(view.text()).toContain('由服务端从已验签 JWT 取得');
    expect(view.text()).toContain('按 OR 叠加');
    expect(view.text()).toContain('无法判断当前是哪个键在生效');
    expect(view.text()).toContain('治理」页控制');
    expect(stateOf(view).get('有效状态')).toBe('已关闭');
    expect(stateOf(view).get('本租户键最近写入时刻')).toBe('—');
  });

  it('holds the first click at a confirmation instead of a write', async () => {
    const view = await mountView();

    await byLabel(view, '开启本租户 Kill Switch')?.trigger('click');

    expect(methods()).toEqual(['GET']);
    expect(view.text()).toContain('本租户所有新的问答请求返回 503 AGT-UPSTREAM-003');
    expect(byLabel(view, '确认开启')).toBeDefined();
  });

  it('writes nothing when the confirmation is cancelled', async () => {
    const view = await mountView();

    await byLabel(view, '开启本租户 Kill Switch')?.trigger('click');
    await byLabel(view, '取消')?.trigger('click');

    expect(methods()).toEqual(['GET']);
    expect(adminStubState.tenantKillSwitch.active).toBe(false);
  });

  it('adopts the write echo without spending a second read', async () => {
    const view = await mountView();

    await enableWithConfirmation(view);
    await vi.waitFor(() => expect(useTenantControlsStore().active).toBe(true));

    expect(methods()).toEqual(['GET', 'PUT']);
    expect(callLog.adminOps[1]?.body).toEqual({ killSwitchActive: true });
    const state = stateOf(view);
    expect(state.get('有效状态')).toBe('已开启');
    expect(state.get('本租户键最近写入时刻')).toBe(formatDateTime(KILL_SWITCH_WRITE_AT));
    expect(state.get('本租户键最近写入者')).toBe(DEMO_USER_ID);
  });

  it('does not claim service recovered when the platform switch keeps the effective state on', async () => {
    adminStubState.killSwitch = { active: true, updatedAt: KILL_SWITCH_WRITE_AT, updatedBy: DEMO_USER_ID };
    const view = await mountView();

    expect(stateOf(view).get('有效状态')).toBe('已开启');
    await byLabel(view, '关闭本租户键')?.trigger('click');
    await vi.waitFor(() => expect(useTenantControlsStore().submitting).toBe(false));

    // 回显仍是 active=true：界面必须明确说「平台级一键全停仍在生效」，而不是「已恢复服务」。
    expect(useTenantControlsStore().active).toBe(true);
    expect(view.text()).toContain('平台级一键全停仍在生效');
    expect(view.text()).toContain('本页无法关闭它');
    expect(view.text()).not.toContain('已关闭 Kill Switch，下方回显');
    expect(adminStubState.tenantKillSwitch.active).toBe(false);
  });

  it('keeps the 500 wording and confirms the switch with a read', async () => {
    adminStubState.tenantControlsAuditFails = true;
    const view = await mountView();

    await enableWithConfirmation(view);
    await vi.waitFor(() => expect(useTenantControlsStore().submitting).toBe(false));

    expect(methods()).toEqual(['GET', 'PUT', 'GET']);
    expect(view.find('[role="alert"]').text()).toContain('管理审计事件写入失败');
    expect(view.text()).toContain('本次写入未回执');
    expect(stateOf(view).get('有效状态')).toBe('已开启');
  });

  it('closes the tenant key from the enabled state without a confirmation', async () => {
    adminStubState.tenantKillSwitch = { active: true, updatedAt: KILL_SWITCH_WRITE_AT, updatedBy: DEMO_USER_ID };
    const view = await mountView();

    await byLabel(view, '关闭本租户键')?.trigger('click');
    await vi.waitFor(() => expect(useTenantControlsStore().active).toBe(false));

    expect(byLabel(view, '确认关闭')).toBeUndefined();
    expect(methods()).toEqual(['GET', 'PUT']);
    expect(callLog.adminOps[1]?.body).toEqual({ killSwitchActive: false });
    expect(view.text()).toContain('已关闭本租户 Kill Switch');
  });
});
