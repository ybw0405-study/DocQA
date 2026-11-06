import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';
import AdminQuotasView from './AdminQuotasView.vue';
import { bindSessionToAuthStore } from '../../stores/sessionBridge';
import { useAdminQuotasStore } from '../../stores/adminQuotas';
import { useAuthStore } from '../../stores/auth';
import {
  DEMO_TARGET_USER_ID,
  DEMO_TOKEN,
  DEMO_USER_ID,
  KILL_SWITCH_WRITE_AT,
  callLog,
  envelope,
  aiQuotaItem,
} from '../../../tests/fixtures';
import { server } from '../../../tests/server';
import { EMPTY_STATE_TEXT, formatDateTime } from '../../utils/display';

let mounted: ViewWrapper | null = null;

async function mountView() {
  const wrapper = mount(AdminQuotasView);
  mounted = wrapper;
  await vi.waitFor(() => expect(useAdminQuotasStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

/** 操作列只有一个「编辑」按钮；状态无关，因此直接取每行最后一格。 */
function rowActions(view: ViewWrapper, index = 0) {
  const cells = view.findAll('tbody tr')[index]?.findAll('td') ?? [];
  return cells[cells.length - 1]?.findAll('button') ?? [];
}

function byLabel(view: ViewWrapper, label: string) {
  return view.findAll('button').find((button) => button.text() === label);
}

function methods(): string[] {
  return callLog.adminOps.map((entry) => entry.method);
}

function writeBody(): unknown {
  return callLog.adminOps.find((entry) => entry.method === 'PUT')?.body;
}

/** 打开第一行的编辑行：提交是全量替换，草稿起始值就是这一行的服务端事实。 */
async function openFirstEdit(view: ViewWrapper): Promise<void> {
  await rowActions(view, 0).find((button) => button.text() === '编辑')?.trigger('click');
}

function draftValues(view: ViewWrapper): string[] {
  return view.findAll('input[type="text"]').map((input) => (input.element as HTMLInputElement).value);
}

/** 视图的事件处理器不会被 await：给在途请求一个宏任务窗口，「零请求」这条断言才有意义。 */
async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

function emptyQuotas(): void {
  server.use(
    mswHttp.get('/api/v1/admin/ai/quotas', () =>
      HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 50 })),
    ),
  );
}

describe('AdminQuotasView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  afterEach(async () => {
    await vi.waitFor(() => {
      const store = useAdminQuotasStore();
      expect(store.loading || store.submitting).toBe(false);
    });
    mounted?.unmount();
    mounted = null;
  });

  it('renders the first page with a null ceiling read as 不设上限', async () => {
    const view = await mountView();
    const rows = view.findAll('tbody tr');

    expect(rows).toHaveLength(2);
    const cells = rows[0]?.findAll('td') ?? [];
    expect(cells[0]?.text()).toContain('zhang.wei');
    expect(cells[0]?.text()).toContain(DEMO_TARGET_USER_ID);
    expect(cells[1]?.text()).toBe('30');
    expect(cells[2]?.text()).toBe('60000');
    expect(cells[3]?.text()).toContain(formatDateTime(aiQuotaItem(1).updatedAt));
    expect(cells[3]?.text()).toContain(DEMO_USER_ID);
    // 第二行只有问答维度有上限：token 维度必须是「不设上限」，不能显示成 0。
    const second = rows[1]?.findAll('td') ?? [];
    expect(second[1]?.text()).toBe('100');
    expect(second[2]?.text()).toBe('不设上限');
  });

  it('prefills the edit row from the server value instead of a blank draft', async () => {
    const view = await mountView();
    await openFirstEdit(view);

    // 提交是全量替换：草稿若不从服务端值起填，一次只改一个维度的编辑会顺手清掉另一个维度。
    expect(draftValues(view)).toEqual(['30', '60000']);
    expect(view.text()).toContain('两个上限都会随请求发出');
  });

  it('sends both ceilings in one write and re-reads the first page', async () => {
    const view = await mountView();
    await openFirstEdit(view);

    await view.findAll('input[type="text"]')[0]?.setValue('12');
    await byLabel(view, '保存')?.trigger('click');
    await vi.waitFor(() => expect(useAdminQuotasStore().items[0]?.dailyQuestionLimit).toBe(12));

    expect(writeBody()).toEqual({ dailyQuestionLimit: 12, dailyTokenLimit: 60000 });
    expect(methods()).toEqual(['GET', 'PUT', 'GET']);
    // 写入后回到第一页而不是接着游标翻页：排序键是最近写入时刻，刚改过的行本来就该重排。
    expect(callLog.adminOps.filter((entry) => entry.method === 'GET').map((entry) => entry.search)).toEqual([
      '?limit=50',
      '?limit=50',
    ]);
    expect(view.text()).toContain(formatDateTime(KILL_SWITCH_WRITE_AT));
    expect(byLabel(view, '保存')).toBeUndefined();
  });

  it('clears the override when both drafts are left blank', async () => {
    const view = await mountView();
    await openFirstEdit(view);
    const drafts = view.findAll('input[type="text"]');

    await drafts[0]?.setValue('');
    await drafts[1]?.setValue('');
    await byLabel(view, '保存')?.trigger('click');
    await vi.waitFor(() => expect(useAdminQuotasStore().items[0]?.dailyTokenLimit).toBeNull());

    // 两个 null 必须原样发出：省略字段在服务端是 400，就地删行又是前端自造语义。
    expect(writeBody()).toEqual({ dailyQuestionLimit: null, dailyTokenLimit: null });
    expect(view.find('[role="status"]').text()).toContain('个性化覆盖已清除');
    expect(view.findAll('tbody tr')[0]?.findAll('td')[2]?.text()).toBe('不设上限');
  });

  it('blocks a draft that cannot be read as an integer without spending a request', async () => {
    const view = await mountView();
    await openFirstEdit(view);
    const question = view.findAll('input[type="text"]')[0];

    await question?.setValue('12.5');
    expect(view.find('.field-error').text()).toContain('的整数，留空表示该维度不设上限');
    expect(byLabel(view, '保存')?.attributes('disabled')).toBeDefined();

    await question?.setValue(String(Number.MAX_SAFE_INTEGER));
    expect(byLabel(view, '保存')?.attributes('disabled')).toBeDefined();

    await byLabel(view, '保存')?.trigger('click');
    await flush();
    expect(methods()).toEqual(['GET']);
  });

  it('appends the cursor page and then reports the loaded row count', async () => {
    const view = await mountView();

    await byLabel(view, '加载更多')?.trigger('click');
    await vi.waitFor(() => expect(view.findAll('tbody tr')).toHaveLength(3));

    expect(view.findAll('tbody tr')[2]?.text()).toContain('—');
    expect(view.text()).toContain('本页已加载 3 行');
    expect(view.text()).toContain('契约不返回总数');
    expect(byLabel(view, '加载更多')).toBeUndefined();
    expect(methods()).toEqual(['GET', 'GET']);
  });

  it('reads an empty override list as 缺行即不设上限 rather than a lookup failure', async () => {
    emptyQuotas();
    const view = await mountView();

    expect(view.find('table').exists()).toBe(false);
    expect(view.text()).toContain(EMPTY_STATE_TEXT.quotasNone);
    expect(view.text()).not.toContain('本页已加载');
  });
});
