import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { mount } from '@vue/test-utils';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import RemindersView from './RemindersView.vue';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { useNotificationsStore } from '../stores/notifications';
import { useRemindersStore } from '../stores/reminders';
import {
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  callLog,
  courseReminderListItem,
  envelope,
  problem,
  reminderListItem,
  terminalReminderListItem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

async function mountView() {
  const wrapper = mount(RemindersView);
  const store = useRemindersStore();
  await vi.waitFor(() => expect(store.listLoaded).toBe(true));
  return { wrapper, store };
}

function createFailures(status: number, code: string, detail: string, headers: Record<string, string> = {}) {
  server.use(
    mswHttp.post('/api/v1/reminders', async ({ request }) => {
      callLog.reminderKeys.push(request.headers.get('Idempotency-Key') ?? '');
      callLog.reminderBodies.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json(
        problem({ status, code, detail, instance: '/api/v1/reminders' }),
        { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
      );
    }),
  );
}

/** 默认桩忽略 status；过滤用例必须让「服务端真的过滤」成为可证伪的事实。 */
function statusAwareListHandler(searches: (string | null)[]) {
  server.use(
    mswHttp.get('/api/v1/reminders', ({ request }) => {
      const status = new URL(request.url).searchParams.get('status');
      searches.push(status);
      const items = [reminderListItem(1), reminderListItem(2), reminderListItem(3)].filter(
        (item) => status === null || item.status === status,
      );
      return HttpResponse.json(envelope({ items, nextCursor: null, limit: 20 }));
    }),
  );
}

function fillDeadlineForm(store: ReturnType<typeof useRemindersStore>): void {
  store.patchForm({
    kind: 'DEADLINE',
    timezone: 'Asia/Shanghai',
    title: '提交线性代数作业',
    dueAt: '2026-11-01T01:30',
    leadMinutes: '120',
  });
}

describe('RemindersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('groups by status so a paused row is never shown as pending a trigger', async () => {
    const { wrapper } = await mountView();
    const groups = wrapper.findAll('.reminders__group');

    expect(groups).toHaveLength(2);
    expect(groups[0]?.text()).toContain('待触发');
    // D-59：暂停行仍带着原计划时刻，按「时刻是否为空」分组就会把它挂进待触发。
    expect(groups[1]?.text()).toContain('已暂停，到点不会响');
    expect(groups[1]?.text()).not.toContain('提醒 1');
    // 暂停行照原样带着「本来下次是几点」和排除日，界面不替它编造也不会让它响。
    const pausedRow = groups[1]?.findAll('.reminders__item')[0];
    expect(pausedRow?.text()).toContain('排除日 1 天');
    expect(pausedRow?.text()).toContain(
      new Date('2026-10-22T13:59:59Z').toLocaleString('zh-CN', { hour12: false }),
    );

    const firstRow = wrapper.findAll('.reminders__item')[0];
    expect(firstRow?.text()).toContain('提醒 1');
    expect(firstRow?.text()).toContain('截止提醒');
    expect(firstRow?.text()).toContain('进行中');
    expect(firstRow?.text()).toContain('FREQ=DAILY;COUNT=1');
    expect(firstRow?.text()).toContain('第三章习题');
    expect(firstRow?.text()).toContain(
      new Date('2026-10-21T13:59:59Z').toLocaleString('zh-CN', { hour12: false }),
    );
  });

  it('shows a dash instead of a fake time for the no-next-trigger group', async () => {
    // 2.3.0 起 null 只出现在终态行上（暂停保留原计划时刻），所以这一组由终态样本承载。
    server.use(
      mswHttp.get('/api/v1/reminders', () =>
        HttpResponse.json(envelope({ items: [reminderListItem(1), terminalReminderListItem()], nextCursor: null, limit: 20 })),
      ),
    );
    const { wrapper } = await mountView();
    const groups = wrapper.findAll('.reminders__group');
    const stopped = groups[1]?.findAll('.reminders__item')[0];

    expect(groups[1]?.text()).toContain('不再有下一次触发');
    expect(stopped?.text()).toContain('已完成');
    // 上次触发有值、下次触发为 null，两个位置不能互换。
    expect(stopped?.findAll('.reminders__times span')[1]?.text()).toBe('—');
    expect(stopped?.findAll('.reminders__times span')[3]?.text()).toBe(
      new Date('2026-10-20T12:00:00Z').toLocaleString('zh-CN', { hour12: false }),
    );
    // 终态没有出边（D-58），这一行只能给说明、不能再给一个必然 409 的按钮。
    expect(stopped?.findAll('button')).toHaveLength(0);
  });

  it('renders untrusted title, description and RRULE as escaped text', async () => {
    server.use(
      mswHttp.get('/api/v1/reminders', () =>
        HttpResponse.json(
          envelope({
            items: [{
              ...reminderListItem(1),
              title: '<img src=x onerror=alert(1)>',
              description: '<b>bold-desc</b>',
              rrule: '<i>FREQ=DAILY</i>',
            }],
            nextCursor: null,
            limit: 20,
          }),
        ),
      ),
    );
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');
    expect(wrapper.text()).toContain('<b>bold-desc</b>');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('b').exists()).toBe(false);
  });

  it('switches the field set with the reminder kind instead of submitting one merged form', async () => {
    const { wrapper, store } = await mountView();

    expect(wrapper.find('[data-testid="due-at"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="rrule"]').exists()).toBe(false);

    const radios = wrapper.findAll('input[type="radio"]');
    expect(radios).toHaveLength(2);
    // 单选钮是 `:checked` 单向绑定，jsdom 下的合成 click 不会走到 @change，直接派发 change 事件。
    await radios[1]?.setValue('CUSTOM');

    expect(store.form.kind).toBe('CUSTOM');
    expect(wrapper.find('[data-testid="due-at"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="rrule"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="dt-start"]').exists()).toBe(true);
  });

  it('re-requests the dashboard through the server when the status filter changes', async () => {
    const searches: (string | null)[] = [];
    statusAwareListHandler(searches);
    const { wrapper, store } = await mountView();
    expect(searches).toEqual([null]);

    await wrapper.get('[data-testid="filter"]').setValue('ACTIVE');
    await vi.waitFor(() => expect(searches).toEqual([null, 'ACTIVE']));

    // 过滤是服务端行为：本地过滤只能过滤当前页，会产生假的空态。
    expect(store.items.map((item) => item.status)).toEqual(['ACTIVE', 'ACTIVE']);
    expect(wrapper.findAll('.reminders__item')).toHaveLength(2);
  });

  it('refuses an illegal form locally before any request leaves', async () => {
    const { wrapper, store } = await mountView();
    store.patchForm({
      kind: 'CUSTOM',
      title: '每周错题复盘',
      timezone: 'Asia/Shanghai',
      rrule: 'freq=weekly',
      dtstart: '2026-11-02T01:00',
    });

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').text()).toContain('RRULE 必须以 FREQ='));

    expect(callLog.reminderKeys).toHaveLength(0);
    expect(wrapper.find('[role="alert"]').text()).not.toContain('code');
  });

  it('shows the created reminder with its server-computed next trigger and trace', async () => {
    const { wrapper, store } = await mountView();
    fillDeadlineForm(store);
    await nextTick();

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.createdSummary).not.toBeNull());
    // 按钮文案读的是 `creating`，而它在创建成功后的「回第一页」结束时才归位；
    // `createdSummary` 在那次重取之前就已有值，只等它就会拿着在途状态去断渲染结果。
    await vi.waitFor(() => expect(store.creating).toBe(false));
    await nextTick();

    const created = wrapper.get('.reminders__created').text();
    expect(created).toContain('已创建「提交线性代数作业」');
    expect(created).toContain('服务端已写入新规则');
    expect(created).toContain(`trace ${DEMO_TRACE_ID}`);
    expect(wrapper.get('[data-testid="submit"]').text()).toBe('创建提醒');
    expect(store.pendingKey).toBeNull();
  });

  it('labels the button as a same-key retry after a rejected create', async () => {
    createFailures(409, 'REM-CONFLICT-001', '同一 Idempotency-Key 已用于不同请求。');
    const { wrapper, store } = await mountView();
    fillDeadlineForm(store);
    await nextTick();

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.code).toBe('REM-CONFLICT-001'));
    // `code` 由 catch 分支写入，`creating` 在其后的 finally 归位，两者之间按钮仍是「创建中…」。
    await vi.waitFor(() => expect(store.creating).toBe(false));
    await nextTick();

    expect(wrapper.get('[data-testid="submit"]').text()).toBe('重试同一请求');
    expect(wrapper.find('[role="alert"]').text()).toContain('code REM-CONFLICT-001');
    expect(wrapper.find('[role="alert"]').text()).toContain(`trace ${DEMO_TRACE_ID}`);

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(callLog.reminderKeys).toHaveLength(2));
    expect(callLog.reminderKeys[0]).toBe(callLog.reminderKeys[1]);
  });

  it('holds the create button for the server-suggested backoff', async () => {
    createFailures(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '30' });
    const { wrapper, store } = await mountView();
    fillDeadlineForm(store);
    await nextTick();

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.retryAfterSeconds).toBe(30));
    await nextTick();

    expect(wrapper.get('[data-testid="submit"]').attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toMatch(/限流等待 \d+ 秒。/);
    wrapper.unmount();
  });

  it('re-arms the backoff gate when the view is mounted again inside the wait window', async () => {
    createFailures(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '30' });
    const { wrapper, store } = await mountView();
    fillDeadlineForm(store);
    await nextTick();

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.retryAfterSeconds).toBe(30));
    wrapper.unmount();

    // 等待秒数存在 store 里、跨路由保留，切走再切回不能把门控清成可直接提交。
    const remounted = await mountView();
    expect(remounted.wrapper.get('[data-testid="submit"]').attributes('disabled')).toBeDefined();
    expect(remounted.wrapper.text()).toMatch(/限流等待 \d+ 秒。/);
    remounted.wrapper.unmount();
  });

  it('offers the next dashboard page only while the server keeps issuing a cursor', async () => {
    const { wrapper, store } = await mountView();
    expect(wrapper.text()).toContain('加载更多提醒');

    await wrapper.findAll('button').find((button) => button.text() === '加载更多提醒')?.trigger('click');
    await vi.waitFor(() => expect(store.items).toHaveLength(3));

    expect(wrapper.findAll('button').some((button) => button.text() === '加载更多提醒')).toBe(false);
  });

  it('offers one button per legal edge and never a button for the state the row is already in', async () => {
    const { wrapper } = await mountView();
    const first = wrapper.findAll('.reminders__item')[0];
    if (first === undefined) {
      throw new Error('看板没有渲染出第一行。');
    }

    // 第 1 行是 ACTIVE：三个出边都在，同态按钮不存在（服务端对它是 200 no-op，界面上不该被点）。
    expect(first.find('[data-testid="status-PAUSED"]').exists()).toBe(true);
    expect(first.find('[data-testid="status-COMPLETED"]').exists()).toBe(true);
    expect(first.find('[data-testid="status-CANCELLED"]').exists()).toBe(true);
    expect(first.find('[data-testid="status-ACTIVE"]').exists()).toBe(false);
    expect(first.get('[data-testid="status-PAUSED"]').text()).toBe('暂停');
    // 动词取自目标状态，所以 ACTIVE 读作「恢复」而不是「设为进行中」。
    expect(wrapper.findAll('.reminders__item')[1]?.get('[data-testid="status-ACTIVE"]').text()).toBe('恢复');
  });

  it('asks twice before a terminal transition and lets the first click be abandoned', async () => {
    const { wrapper } = await mountView();
    const complete = wrapper.get('[data-testid="status-COMPLETED"]');

    // D-58：终态没有出边，误点只能靠重建一条挽回，所以「不可回退」与确认必须同屏。
    expect(complete.text()).toBe('完成（不可回退）');
    await complete.trigger('click');
    expect(wrapper.get('[data-testid="status-COMPLETED"]').text()).toBe('确认完成？');
    expect(callLog.statusCalls).toEqual([]);

    await wrapper.get('[data-testid="status-cancel"]').trigger('click');
    expect(wrapper.get('[data-testid="status-COMPLETED"]').text()).toBe('完成（不可回退）');
    expect(wrapper.find('[data-testid="status-cancel"]').exists()).toBe(false);
    expect(callLog.statusCalls).toEqual([]);
  });

  it('leaves a terminal row with an explanation instead of another action', async () => {
    const { wrapper, store } = await mountView();
    const reminderId = store.items[0]?.reminderId ?? '';

    await wrapper.get('[data-testid="status-COMPLETED"]').trigger('click');
    await wrapper.get('[data-testid="status-COMPLETED"]').trigger('click');
    await vi.waitFor(() => expect(store.items[0]?.status).toBe('COMPLETED'));

    const row = wrapper.findAll('.reminders__item').find((item) => item.text().includes('提醒 1'));
    expect(row?.text()).toContain('终态，不可回退');
    expect(row?.findAll('button')).toHaveLength(0);
    // 完成是终态、没有出边（D-58），这一行因此必须落到终态组；第 2 行仍在自己的暂停组里。
    const group = store.groups.find((candidate) => candidate.items.some((item) => item.reminderId === reminderId));
    expect(group?.heading).toBe('不再有下一次触发');
  });

  it('shows the class start time only on course rows because only they carry a real lead', async () => {
    server.use(
      mswHttp.get('/api/v1/reminders', () =>
        HttpResponse.json(
          envelope({
            items: [courseReminderListItem(), reminderListItem(1)],
            nextCursor: null,
            limit: 20,
          }),
        ),
      ),
    );
    const { wrapper } = await mountView();
    const rows = wrapper.findAll('.reminders__item');

    // 触发时刻是「上课 − 提前量」，界面上两个时刻都要出现，否则「提前 15 分钟」无从核对。
    const course = rows[0];
    expect(course?.text()).toContain('课程提醒');
    expect(course?.text()).toContain(
      new Date('2026-10-26T01:05:00Z').toLocaleString('zh-CN', { hour12: false }),
    );
    expect(course?.text()).toContain('提前 15 分钟');
    // DEADLINE 行的提前量已并进 dtstart、读侧恒为 0，换算出来的「上课时刻」只会是触发时刻本身，属于假信息。
    expect(rows[1]?.text()).not.toContain('上课 ');
  });

  it('mounts the notification panel on the same page as the board', async () => {
    const { wrapper } = await mountView();
    const notifications = useNotificationsStore();
    await vi.waitFor(() => expect(notifications.listLoaded).toBe(true));

    // 通知是「已经响过的提醒」，与看板同页才能就地标记已读并按 reminderId 对照提前量。
    expect(wrapper.find('.notifications').exists()).toBe(true);
    expect(wrapper.find('[data-testid="notifications-unread-only"]').exists()).toBe(true);
    expect(notifications.items).toHaveLength(2);
  });

  it('derives the request body from the visible inputs rather than a locally minted id', async () => {
    const { wrapper, store } = await mountView();
    fillDeadlineForm(store);
    await wrapper.get('[data-testid="description"]').setValue('教学楼 A-203');
    await nextTick();

    await wrapper.get('[data-testid="submit"]').trigger('click');
    await vi.waitFor(() => expect(store.createdSummary).not.toBeNull());

    expect(callLog.reminderBodies[0]).toEqual({
      kind: 'DEADLINE',
      title: '提交线性代数作业',
      timezone: 'Asia/Shanghai',
      dueAt: new Date('2026-11-01T01:30').toISOString(),
      leadMinutes: 120,
      description: '教学楼 A-203',
    });
  });
});
