import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';

import AdminModelConnectionsView from './AdminModelConnectionsView.vue';
import { bindSessionToAuthStore } from '../../stores/sessionBridge';
import { useAdminModelConnectionsStore } from '../../stores/adminModelConnections';
import { useAuthStore } from '../../stores/auth';
import {
  DEMO_TOKEN,
  callLog,
  modelConnectionItem,
  modelConnectionsStubState,
  problem,
} from '../../../tests/fixtures';
import { server } from '../../../tests/server';
import { EMPTY_STATE_TEXT } from '../../utils/display';

let mounted: ViewWrapper | null = null;

async function mountView() {
  const wrapper = mount(AdminModelConnectionsView);
  mounted = wrapper;
  await vi.waitFor(() => expect(useAdminModelConnectionsStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

function byLabel(view: ViewWrapper, label: string) {
  return view.findAll('button').find((button) => button.text() === label);
}

/** 预设项的按钮文本含厂商/模型/提示，按标签文本定位而不是整段精确匹配。 */
function presetButton(view: ViewWrapper, label: string) {
  return view
    .findAll('.model-wizard__item')
    .find((button) => (button.find('.model-wizard__label').text() ?? '').startsWith(label));
}

function rows(view: ViewWrapper) {
  return view.findAll('tbody tr');
}

function cellText(row: ReturnType<typeof rows>[number], index: number): string {
  return row.findAll('td')[index]?.text() ?? '';
}

const SECRET = 'sk-view-test-0123456789';

describe('AdminModelConnectionsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    modelConnectionsStubState.rows = [modelConnectionItem()];
  });

  afterEach(async () => {
    await vi.waitFor(() => {
      const store = useAdminModelConnectionsStore();
      expect(store.loading || store.submitting || store.presetsLoading).toBe(false);
    });
    mounted?.unmount();
    mounted = null;
  });

  it('lists connections and expresses credentials only as configured/not configured', async () => {
    const view = await mountView();

    expect(rows(view)).toHaveLength(1);
    expect(cellText(rows(view)[0], 3)).toBe('已配置');
    expect(view.text()).not.toContain('secret');
    expect(view.find('caption').text()).toContain('不显示任何密钥材料');
  });

  it('shows the empty state instead of implying there is no model at all', async () => {
    modelConnectionsStubState.rows = [];
    const view = await mountView();

    expect(view.text()).toContain(EMPTY_STATE_TEXT.modelConnectionsNone);
    expect(view.text()).toContain('当前没有生效连接');
  });

  it('keeps a failed read as an error and does not render a table', async () => {
    server.use(
      mswHttp.get('/api/v1/admin/ai/model-connections', () =>
        HttpResponse.json(
          problem({
            status: 500,
            code: 'COM-INTERNAL-001',
            detail: '模型连接读取失败。',
            instance: '/api/v1/admin/ai/model-connections',
          }),
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const view = await mountView();

    expect(view.find('[role="alert"]').text()).toContain('模型连接读取失败。');
    expect(view.find('table').exists()).toBe(false);
  });

  it('opens the wizard with the server-side catalog and prefills the chosen preset', async () => {
    const view = await mountView();

    await byLabel(view, '新建连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().presets).not.toBeNull());

    expect(view.text()).toContain('常用推荐');
    expect(view.text()).toContain('DeepSeek');
    await presetButton(view, 'DeepSeek')?.trigger('click');

    const inputs = view.findAll('input');
    const baseUrl = inputs.find((input) => input.attributes('maxlength') === '512');
    expect(baseUrl?.element.value).toBe('https://api.deepseek.com/v1');
    expect(view.text()).toContain('去申请 Key');
  });

  it('creates, probes and activates through the wizard, then refreshes the list', async () => {
    modelConnectionsStubState.rows = [];
    const view = await mountView();

    await byLabel(view, '新建连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().presets).not.toBeNull());
    await presetButton(view, 'DeepSeek')?.trigger('click');

    const secretInput = view.findAll('input').find((input) => input.attributes('type') === 'password');
    await secretInput?.setValue(SECRET);
    await byLabel(view, '测试并连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().submitting).toBe(false));

    const methods = callLog.adminOps.map((entry) => `${entry.method} ${entry.path}`);
    expect(methods.some((entry) => entry.endsWith('/credential'))).toBe(true);
    expect(methods.some((entry) => entry.endsWith('/probe'))).toBe(true);
    expect(methods.some((entry) => entry.endsWith('/activation'))).toBe(true);
    expect(rows(view)).toHaveLength(1);
    expect(view.text()).toContain('生效中');
    expect(view.html()).not.toContain(SECRET);
  });

  it('blocks an empty API Key before any write leaves the browser', async () => {
    const view = await mountView();

    await byLabel(view, '新建连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().presets).not.toBeNull());
    await presetButton(view, 'DeepSeek')?.trigger('click');
    await byLabel(view, '测试并连接')?.trigger('click');

    expect(view.find('[role="alert"]').text()).toContain('请填写 API Key');
    const writes = callLog.adminOps.filter((entry) => entry.method !== 'GET');
    expect(writes).toEqual([]);
  });

  it('keeps the key masked by default and clears it after a submit', async () => {
    modelConnectionsStubState.rows = [];
    const view = await mountView();

    await byLabel(view, '新建连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().presets).not.toBeNull());
    await presetButton(view, 'DeepSeek')?.trigger('click');

    const secretInput = view.findAll('input').find((input) => input.attributes('type') === 'password');
    expect(secretInput?.attributes('autocomplete')).toBe('off');
    await secretInput?.setValue(SECRET);
    await byLabel(view, '显示密钥')?.trigger('click');
    expect(view.findAll('input').some((input) => input.attributes('type') === 'text' && input.element.value === SECRET)).toBe(true);

    await byLabel(view, '测试并连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().submitting).toBe(false));
    expect(view.html()).not.toContain(SECRET);
  });

  it('warns that a loopback preset needs a server-side opt-in', async () => {
    const view = await mountView();

    await byLabel(view, '新建连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().presets).not.toBeNull());
    await presetButton(view, '本地 Ollama')?.trigger('click');

    expect(view.text()).toContain('DOCQA_LLM_ALLOW_PRIVATE');
    expect(view.text()).toContain('EGRESS_BLOCKED');
  });

  it('still offers a custom endpoint when the catalog cannot be read', async () => {
    modelConnectionsStubState.presetsFail = true;
    const view = await mountView();

    await byLabel(view, '新建连接')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().presetsLoading).toBe(false));

    expect(view.text()).toContain('厂商预设读取失败');
    await byLabel(view, '自定义 OpenAI 兼容端点')?.trigger('click');
    expect(view.findAll('input').length).toBeGreaterThan(2);
  });

  it('requires a two-step confirmation before deleting', async () => {
    const view = await mountView();

    await byLabel(view, '删除')?.trigger('click');
    expect(view.text()).toContain('确认删除');
    expect(modelConnectionsStubState.rows).toHaveLength(1);

    await byLabel(view, '确认删除')?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().submitting).toBe(false));
    expect(modelConnectionsStubState.rows).toHaveLength(0);
  });

  it('disables activation for a disabled connection', async () => {
    modelConnectionsStubState.rows = [modelConnectionItem({ enabled: false, active: false })];
    const view = await mountView();

    const button = byLabel(view, '设为生效');
    expect(button?.attributes('disabled')).toBeDefined();
    expect(view.text()).toContain('已停用');
  });

  it('switches the active connection from the list without a restart', async () => {
    modelConnectionsStubState.rows = [
      modelConnectionItem({ id: 'a', active: true }),
      modelConnectionItem({ id: 'b', name: '备用连接', active: false }),
    ];
    const view = await mountView();

    const activateButtons = view.findAll('button').filter((button) => button.text() === '设为生效');
    await activateButtons[0]?.trigger('click');
    await vi.waitFor(() => expect(useAdminModelConnectionsStore().submitting).toBe(false));

    expect(useAdminModelConnectionsStore().activeConnection?.id).toBe('b');
    expect(view.text()).toContain('无需重启');
  });
});
