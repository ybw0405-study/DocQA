import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';
import AdminUsersView from './AdminUsersView.vue';
import { bindSessionToAuthStore } from '../../stores/sessionBridge';
import { useAdminUsersStore } from '../../stores/adminUsers';
import { useAuthStore } from '../../stores/auth';
import {
  DEMO_TARGET_USER_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  DEMO_USER_ID,
  callLog,
  envelope,
  problem,
  userSummaryRow,
} from '../../../tests/fixtures';
import { server } from '../../../tests/server';
import { EMPTY_STATE_TEXT, formatDateTime } from '../../utils/display';

let mounted: ViewWrapper | null = null;

async function mountView() {
  const wrapper = mount(AdminUsersView);
  mounted = wrapper;
  await vi.waitFor(() => expect(useAdminUsersStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

/** 展开行与数据行都是 `tbody tr`：按 class 摘掉它们，行索引才能稳定指向账号。 */
function dataRows(view: ViewWrapper) {
  return view.findAll('tbody tr').filter((row) => !row.classes().includes('users-table__expanded'));
}

function cells(view: ViewWrapper, index: number) {
  return dataRows(view)[index]?.findAll('td') ?? [];
}

function buttonsIn(view: ViewWrapper, index: number) {
  const row = cells(view, index);
  return row[row.length - 1]?.findAll('button') ?? [];
}

function buttonIn(view: ViewWrapper, index: number, label: string) {
  return buttonsIn(view, index).find((button) => button.text() === label);
}

function byLabel(view: ViewWrapper, label: string) {
  return view.findAll('button').find((button) => button.text() === label);
}

function confirmBlock(view: ViewWrapper) {
  return view.find('.admin-confirm');
}

function methods(): string[] {
  return callLog.userOps.map((entry) => entry.method);
}

function writeBodies(): unknown[] {
  return callLog.userOps.filter((entry) => entry.method !== 'GET').map((entry) => entry.body);
}

function checked(view: ViewWrapper): boolean[] {
  return view.findAll('input[type="checkbox"]').map((box) => (box.element as HTMLInputElement).checked);
}

function selects(view: ViewWrapper) {
  return view.findAll('select');
}

/** 视图的事件处理器不会被 await：给在途请求一个宏任务窗口，「零请求」这条断言才有意义。 */
async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

function emptyUsers(): void {
  server.use(
    mswHttp.get('/api/v1/users', () =>
      HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 20 })),
    ),
  );
}

function failRoleSave(): void {
  server.use(
    mswHttp.put('/api/v1/users/:userId/roles', () =>
      HttpResponse.json(
        problem({
          status: 422,
          code: 'USR-VALIDATION-001',
          detail: '服务端拒绝这次角色替换。',
          instance: '/api/v1/users',
        }),
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

describe('AdminUsersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    // 两条自锁护栏的判据只有「这一行是不是调用者自己」，而这个身份只来自 `/auth/me` 的 `userId`。
    useAuthStore().userId = DEMO_USER_ID;
  });

  afterEach(async () => {
    await vi.waitFor(() => {
      const store = useAdminUsersStore();
      expect(store.loading || store.submitting).toBe(false);
    });
    mounted?.unmount();
    mounted = null;
  });

  it('renders each column from contract fields and marks the caller’s own row', async () => {
    const view = await mountView();
    const rows = dataRows(view);
    expect(rows).toHaveLength(2);

    const first = cells(view, 0);
    expect(first[0]?.text()).toContain('demo');
    expect(first[0]?.text()).toContain(DEMO_USER_ID);
    expect(first[0]?.text()).toContain('当前登录的账号');
    expect(first[1]?.text()).toBe('正常');
    expect(first[2]?.text()).toBe('普通用户、管理员');
    expect(first[3]?.text()).toContain(formatDateTime(userSummaryRow(1).createdAt));
    expect(first[4]?.text()).toContain(formatDateTime(userSummaryRow(1).lastLoginAt));

    // 第二行不是自己：没有那枚标记，动作列才是完整的两颗活按钮。
    const second = cells(view, 1);
    expect(second[0]?.text()).not.toContain('当前登录的账号');
    expect(buttonsIn(view, 1).map((button) => button.text())).toEqual(['改角色', '停用']);
  });

  it('reads a never-logged-in account as a dash and a disabled chip, not as a missing row', async () => {
    const view = await mountView();
    await byLabel(view, '加载更多')?.trigger('click');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(4));

    const wang = cells(view, 3);
    expect(wang[0]?.text()).toContain('wang.lei');
    expect(wang[1]?.text()).toBe('已停用');
    expect(wang[1]?.find('span').classes()).toContain('governance__chip--warn');
    expect(wang[4]?.text()).toBe('—');
    expect(wang[4]?.text()).not.toContain('null');
  });

  it('reports the loaded row count without ever claiming a total', async () => {
    const view = await mountView();

    // 游标还在时页脚只有「加载更多」：那一行行数脚注与它是 v-if/v-else-if 的一对。
    expect(byLabel(view, '加载更多')).toBeDefined();
    await byLabel(view, '加载更多')?.trigger('click');
    await vi.waitFor(() => expect(view.text()).toContain('本页已加载 4 行'));

    // 契约不返回 total：翻到底后界面手里仍然只有「已加载多少」这一个本地事实。
    expect(view.text()).toContain('契约不返回总数');
    expect(byLabel(view, '加载更多')).toBeUndefined();
  });

  it('sends each filter as its own query parameter and clears both on demand', async () => {
    const view = await mountView();
    await selects(view)[0]?.setValue('DISABLED');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(1));

    expect(callLog.userOps.at(-1)?.search).toBe('?status=DISABLED&limit=20');

    await byLabel(view, '清空条件')?.trigger('click');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(2));

    expect(selects(view).map((select) => (select.element as HTMLSelectElement).value)).toEqual(['', '']);
    expect(callLog.userOps.at(-1)?.search).toBe('?limit=20');

    await selects(view)[1]?.setValue('ADMIN');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(2));

    // 「持有该角色即命中」：demo 与 li.na 都含 ADMIN，而 li.na 在第一页之外，所以这里仍是两行。
    expect(callLog.userOps.at(-1)?.search).toBe('?role=ADMIN&limit=20');
    expect(dataRows(view)[0]?.text()).toContain('demo');
    expect(dataRows(view)[1]?.text()).toContain('li.na');
  });

  it('distinguishes "no accounts at all" from "nothing under these conditions"', async () => {
    emptyUsers();
    const view = await mountView();

    expect(view.find('table').exists()).toBe(false);
    expect(view.text()).toContain(EMPTY_STATE_TEXT.usersNone);
    expect(view.text()).not.toContain(EMPTY_STATE_TEXT.usersFiltered);
  });

  it('names the filter as the reason when the conditions exclude every row', async () => {
    const view = await mountView();
    await selects(view)[0]?.setValue('DISABLED');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(1));

    await selects(view)[1]?.setValue('ADMIN');
    // 表格在重查途中也会消失，只有「加载完成且零行」才是空态而不是骨架屏。
    await vi.waitFor(() => {
      const store = useAdminUsersStore();
      expect(store.loaded && store.items.length === 0).toBe(true);
    });

    expect(view.find('table').exists()).toBe(false);
    expect(view.text()).toContain(EMPTY_STATE_TEXT.usersFiltered);
  });

  it('locks the administrator role on the caller’s own row instead of trusting the server to be polite', async () => {
    const view = await mountView();
    await buttonIn(view, 0, '改角色')?.trigger('click');

    const boxes = view.findAll('input[type="checkbox"]');
    expect(boxes).toHaveLength(2);
    expect(checked(view)).toEqual([true, true]);
    expect(boxes[1]?.attributes('disabled')).toBeDefined();
    expect(boxes[0]?.attributes('disabled')).toBeUndefined();
    expect(view.text()).toContain('不能摘掉自己的管理员角色：那会让管理面对你自己关闭。');

    await flush();
    expect(methods()).toEqual(['GET']);
  });

  it('locks the last remaining role and unlocks it again once a second one is ticked', async () => {
    const view = await mountView();
    await buttonIn(view, 1, '改角色')?.trigger('click');

    // zhang.wei 只有普通用户一个角色：取消它就是把账号写成零角色，服务端按 422 拒绝。
    expect(view.findAll('input[type="checkbox"]')[0]?.attributes('disabled')).toBeDefined();
    expect(view.text()).toContain('至少要为账号保留一个角色：空集合会被服务端按 422 拒绝。');

    await view.findAll('input[type="checkbox"]')[1]?.setValue(true);
    await vi.waitFor(() =>
      expect(view.findAll('input[type="checkbox"]')[0]?.attributes('disabled')).toBeUndefined(),
    );

    await byLabel(view, '保存角色')?.trigger('click');
    await vi.waitFor(() => expect(useAdminUsersStore().items[1]?.roles).toEqual(['USER', 'ADMIN']));

    // 整集替换：两个勾到的角色一起发出去，顺序按枚举而不是按点击顺序。
    expect(writeBodies()).toEqual([{ roles: ['USER', 'ADMIN'] }]);
    expect(view.find('[role="status"]').text()).toContain('已把 zhang.wei 的角色整集替换为「普通用户、管理员」');
    expect(view.find('[role="status"]').text()).toContain('角色变更不吊销已签发的访问令牌');
  });

  it('keeps the draft from leaking across a cancelled edit', async () => {
    const view = await mountView();
    await buttonIn(view, 1, '改角色')?.trigger('click');
    await view.findAll('input[type="checkbox"]')[1]?.setValue(true);
    await byLabel(view, '取消')?.trigger('click');
    await flush();

    expect(view.find('.users-table__expanded').exists()).toBe(false);
    expect(methods()).toEqual(['GET']);

    // 重新打开必须从服务端那一行起稿，而不是接着上一次没提交的草稿。
    await buttonIn(view, 1, '改角色')?.trigger('click');
    expect(checked(view)).toEqual([true, false]);
  });

  it('requires an explicit confirmation before the disable write and says what it costs', async () => {
    const view = await mountView();
    await buttonIn(view, 1, '停用')?.trigger('click');
    await flush();

    expect(confirmBlock(view).exists()).toBe(true);
    expect(view.text()).toContain('吊销该账号的全部刷新令牌家族');
    expect(view.text()).toContain('重新启用不会复活这些已吊销的凭据');
    expect(methods()).toEqual(['GET']);

    await byLabel(view, '取消')?.trigger('click');
    await flush();
    expect(confirmBlock(view).exists()).toBe(false);
    expect(methods()).toEqual(['GET']);

    await buttonIn(view, 1, '停用')?.trigger('click');
    await confirmBlock(view).findAll('button')[0]?.trigger('click');
    await vi.waitFor(() => expect(useAdminUsersStore().items[1]?.status).toBe('DISABLED'));

    expect(writeBodies()).toEqual([{ status: 'DISABLED' }]);
    expect(view.find('[role="status"]').text()).toContain('已停用 zhang.wei');
    expect(view.find('[role="status"]').text()).toContain('该账号即刻失去登录能力');
  });

  it('offers the disable action for the caller as an inert button with the reason beside it', async () => {
    const view = await mountView();
    const selfStop = buttonIn(view, 0, '停用');

    expect(buttonsIn(view, 0).map((button) => button.text())).toEqual(['改角色', '停用']);
    expect(selfStop?.attributes('disabled')).toBeDefined();
    expect(cells(view, 0)[5]?.text()).toContain('停用不适用于当前登录的账号：服务端会拒绝这条请求。');

    await selfStop?.trigger('click');
    await flush();
    expect(methods()).toEqual(['GET']);
    expect(confirmBlock(view).exists()).toBe(false);
  });

  it('re-enables a disabled account without a confirmation step', async () => {
    const view = await mountView();
    await byLabel(view, '加载更多')?.trigger('click');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(4));

    await buttonIn(view, 3, '启用')?.trigger('click');
    await vi.waitFor(() => expect(useAdminUsersStore().items[3]?.status).toBe('ACTIVE'));

    // 启用的影响面只是「可以重新登录」，已吊销的凭据不会复活，所以这里不值得一次二次确认。
    expect(confirmBlock(view).exists()).toBe(false);
    expect(writeBodies()).toEqual([{ status: 'ACTIVE' }]);
    expect(view.find('[role="status"]').text()).toContain('已启用 wang.lei');
    expect(view.find('[role="status"]').text()).toContain('此前已吊销的凭据不会复活');
  });

  it('explains that a filtered row left the list rather than vanished', async () => {
    const view = await mountView();
    await selects(view)[0]?.setValue('ACTIVE');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(2));

    await buttonIn(view, 1, '停用')?.trigger('click');
    await confirmBlock(view).findAll('button')[0]?.trigger('click');
    await vi.waitFor(() => expect(dataRows(view)).toHaveLength(1));

    // 服务端按每一页重新求值条件，改完不匹配这一条件的行确实出局了。
    expect(dataRows(view)[0]?.text()).toContain(DEMO_USER_ID);
    expect(view.find('[role="status"]').text()).toContain('这一行已不再匹配当前筛选条件');
  });

  it('surfaces a server policy rejection without pretending the row already changed', async () => {
    failRoleSave();
    const view = await mountView();
    await buttonIn(view, 1, '改角色')?.trigger('click');
    await view.findAll('input[type="checkbox"]')[1]?.setValue(true);

    await byLabel(view, '保存角色')?.trigger('click');
    await vi.waitFor(() => expect(view.find('[role="alert"]').exists()).toBe(true));

    expect(view.find('[role="alert"]').text()).toContain('服务端拒绝这次角色替换。');
    expect(view.find('[role="alert"]').text()).toContain(`（code USR-VALIDATION-001）`);
    expect(view.find('[role="alert"]').text()).toContain(`（trace ${DEMO_TRACE_ID}）`);
    // 失败时编辑器不关、行不改：管理员要看到自己刚勾了什么，也要看到服务端那一行还是原样。
    expect(view.find('.users-table__expanded').exists()).toBe(true);
    expect(useAdminUsersStore().items[1]?.userId).toBe(DEMO_TARGET_USER_ID);
    expect(useAdminUsersStore().items[1]?.roles).toEqual(['USER']);
    expect(view.find('[role="status"]').exists()).toBe(false);
  });
});
