import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';
import AdminControlsView from './AdminControlsView.vue';
import { bindSessionToAuthStore } from '../../stores/sessionBridge';
import { useAdminControlsStore } from '../../stores/adminControls';
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

/** `loaded` 在 store 的 finally 里置真，成功与失败两条路径都等到它才算首屏稳定。 */
async function mountView() {
  const wrapper = mount(AdminControlsView);
  mounted = wrapper;
  await vi.waitFor(() => expect(useAdminControlsStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

/** 治理面板的两块 `dl` 都是 dt/dd 一对一行；按标签取值比按序号取值抗改动。 */
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

function failControlsRead(detail: string): void {
  server.use(
    mswHttp.get('/api/v1/admin/ai/controls', () =>
      HttpResponse.json(
        problem({ status: 500, code: 'COM-INTERNAL-001', detail, instance: '/api/v1/admin/ai/controls' }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
     ),
   ),
 );
}

async function enableWithConfirmation(view: ViewWrapper): Promise<void> {
  await byLabel(view, '开启 Kill Switch')?.trigger('click');
  await byLabel(view, '确认开启')?.trigger('click');
}

describe('AdminControlsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  // 视图的事件处理器不会被 await：收尾先把在途读写等完，再卸载，否则这条请求会打进下一条用例的桩里。
  afterEach(async () => {
    await vi.waitFor(() => {
      const store = useAdminControlsStore();
      expect(store.loading || store.submitting).toBe(false);
    });
    mounted?.unmount();
    mounted = null;
  });

  it('keeps a failed read as an error and refuses to offer a write entry', async () => {
    failControlsRead('治理状态读取失败。');
    const view = await mountView();

    expect(view.find('[role="alert"]').text()).toContain('治理状态读取失败。');
    expect(view.find('[role="alert"]').text()).toContain('COM-INTERNAL-001');
    expect(stateOf(view).get('当前状态')).toBe('未读取');
    expect(byLabel(view, '开启 Kill Switch')).toBeUndefined();
    expect(view.text()).toContain('不提供写入入口');
    // 「Python 尚未发布上限」是一条因果结论，只能建立在实际读到过的账本上；读失败时它没有依据。
    expect(view.text()).not.toContain('Python 尚未发布上限');
  });

  it('renders absent write metadata as a dash rather than a guess', async () => {
    const view = await mountView();
    const state = stateOf(view);

    expect(state.get('当前状态')).toBe('已关闭');
    expect(state.get('最近写入时刻')).toBe('—');
    expect(state.get('最近写入者')).toBe('—');
    expect(view.text()).toContain('重新读取可恢复元数据');
    expect(byLabel(view, '开启 Kill Switch')).toBeDefined();
  });

  it('holds the first click at a confirmation instead of a write', async () => {
    const view = await mountView();

    await byLabel(view, '开启 Kill Switch')?.trigger('click');

    expect(methods()).toEqual(['GET']);
    expect(view.text()).toContain('503 AGT-UPSTREAM-003');
    expect(view.text()).toContain('不是「瞬时全停」');
    expect(byLabel(view, '确认开启')).toBeDefined();
  });

  it('writes nothing when the confirmation is cancelled', async () => {
    const view = await mountView();

    await byLabel(view, '开启 Kill Switch')?.trigger('click');
    await byLabel(view, '取消')?.trigger('click');

    expect(methods()).toEqual(['GET']);
    expect(byLabel(view, '确认开启')).toBeUndefined();
    expect(adminStubState.killSwitch.active).toBe(false);
  });

  it('adopts the write echo without spending a second read', async () => {
    const view = await mountView();

    await enableWithConfirmation(view);
    await vi.waitFor(() => expect(useAdminControlsStore().killSwitchActive).toBe(true));

    // PUT 的回显就是写入后的完整状态，采纳它是采纳服务端事实；再补一次 GET 就是假装不信任契约。
    expect(methods()).toEqual(['GET', 'PUT']);
    expect(callLog.adminOps[1]?.body).toEqual({ killSwitchActive: true });
    const state = stateOf(view);
    expect(state.get('当前状态')).toBe('已开启');
    expect(state.get('最近写入时刻')).toBe(formatDateTime(KILL_SWITCH_WRITE_AT));
    expect(state.get('最近写入者')).toBe(DEMO_USER_ID);
    expect(view.text()).toContain('服务端写入后回显的状态');
    expect(byLabel(view, '关闭 Kill Switch')).toBeDefined();
  });

  it('keeps the 500 wording and confirms the switch with a read', async () => {
    adminStubState.controlsAuditFails = true;
    const view = await mountView();

    await enableWithConfirmation(view);
    await vi.waitFor(() => expect(useAdminControlsStore().submitting).toBe(false));

    // 500 的含义是「开关已生效、审计写入失败」，所以第三次读是必须的，不是重复请求。
    expect(methods()).toEqual(['GET', 'PUT', 'GET']);
    expect(view.find('[role="alert"]').text()).toContain('管理审计事件写入失败');
    expect(view.text()).toContain('本次写入未回执');
    expect(stateOf(view).get('当前状态')).toBe('已开启');
  });

  it('reports an unpublished ceiling as 上限未发布 rather than 0', async () => {
    adminStubState.limitsKnown = false;
    const view = await mountView();
    const state = stateOf(view);

    expect(state.get('本轮调用次数')).toBe('22');
    expect(state.get('调用上限')).toBe('上限未发布');
    expect(state.get('token 上限')).toBe('上限未发布');
    expect(view.text()).toContain('不可知不是 0');
  });

  it('closes the switch from the enabled state without a confirmation', async () => {
    adminStubState.killSwitch = { active: true, updatedAt: KILL_SWITCH_WRITE_AT, updatedBy: DEMO_USER_ID };
    const view = await mountView();

    await byLabel(view, '关闭 Kill Switch')?.trigger('click');
    await vi.waitFor(() => expect(useAdminControlsStore().killSwitchActive).toBe(false));

    // 恢复服务不需要二次确认： 要求明示影响面的只有开启分支。
    expect(byLabel(view, '确认关闭')).toBeUndefined();
    expect(methods()).toEqual(['GET', 'PUT']);
    expect(callLog.adminOps[1]?.body).toEqual({ killSwitchActive: false });
    expect(view.text()).toContain('已关闭 Kill Switch');
  });
});
