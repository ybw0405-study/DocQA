import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import NotificationPanel from './NotificationPanel.vue';
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
  notificationData,
  problem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

async function mountPanel() {
  const wrapper = mount(NotificationPanel);
  const notifications = useNotificationsStore();
  await vi.waitFor(() => expect(notifications.listLoaded).toBe(true));
  return { wrapper, notifications };
}

/** `findAll` 与 `get` 返回的包装类型并不互通，这里只依赖两者共有的 element 与 trigger。 */
interface BoxLike {
  element: Element;
  trigger(event: string): Promise<unknown>;
}

async function setBox(box: BoxLike | undefined, checked: boolean): Promise<void> {
  if (box === undefined) {
    throw new Error('找不到该勾选框。');
  }
  // 组件读的是 target.checked，所以直接把 DOM 状态写进去再派发 change，比合成 click 更贴近真实事件序列。
  (box.element as HTMLInputElement).checked = checked;
  await box.trigger('change');
}

function failedRead() {
  server.use(
    mswHttp.post('/api/v1/notifications/read', () =>
      HttpResponse.json(
        problem({ status: 401, code: 'USR-AUTH-001', detail: '令牌无效。', instance: '/api/v1/notifications/read' }),
        { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

/** 看板行的提前量是显示层换算的唯一来源：把它单独摆出来，面板才有「上课时刻」可说。 */
async function loadCourseBoardRow() {
  server.use(
    mswHttp.get('/api/v1/reminders', () =>
      HttpResponse.json(envelope({ items: [courseReminderListItem()], nextCursor: null, limit: 20 })),
    ),
  );
  await useRemindersStore().loadListFirst();
}

describe('NotificationPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('renders one row per delivered trigger with the unread badge only where readAt is null', async () => {
    const { wrapper, notifications } = await mountPanel();
    const rows = wrapper.findAll('.notifications__item');

    expect(rows).toHaveLength(2);
    expect(notifications.items[1]?.readAt).not.toBeNull();
    expect(rows[0]?.text()).toContain('线性代数');
    expect(rows[0]?.text()).toContain('课程提醒');
    expect(rows[0]?.text()).toContain('未读');
    // 已读行仍然留在列表里：一条通知是一次已经响过的触发，不是待办。
    expect(rows[1]?.text()).toContain('已读');
    expect(rows[1]?.text()).toContain('第三章习题');
    expect(wrapper.text()).toContain('本页未读 1 条');
  });

  it('renders untrusted title and description as escaped text', async () => {
    server.use(
      mswHttp.get('/api/v1/notifications', () =>
        HttpResponse.json(
          envelope({
            items: [{
              ...notificationData(1),
              title: '<img src=x onerror=alert(1)>',
              description: '<b>bold-desc</b>',
            }],
            nextCursor: null,
            limit: 20,
          }),
        ),
      ),
    );
    const { wrapper } = await mountPanel();

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');
    expect(wrapper.text()).toContain('<b>bold-desc</b>');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('b').exists()).toBe(false);
  });

  it('converts the trigger instant into a class start only when the board row is loaded', async () => {
    await loadCourseBoardRow();
    const { wrapper } = await mountPanel();
    const first = wrapper.findAll('.notifications__item')[0];

    // 契约的 plannedTriggerAt 是提前提醒的时刻，上课时刻要用看板行的 leadMinutes 换算（D-72）。
    expect(first?.text()).toContain(
      new Date('2026-10-26T01:05:00Z').toLocaleString('zh-CN', { hour12: false }),
    );
    expect(first?.text()).toContain('提前 15 分钟');
  });

  it('falls back to the raw trigger instant instead of guessing a lead', async () => {
    const { wrapper } = await mountPanel();
    const first = wrapper.findAll('.notifications__item')[0];

    // 看板行不在已加载页时不猜提前量，措辞必须与换算过的口径明确不同。
    expect(first?.text()).toContain(
      `触发时刻 ${new Date('2026-10-26T00:50:00Z').toLocaleString('zh-CN', { hour12: false })}`,
    );
    expect(first?.text()).not.toContain('上课 ');
  });

  it('counts the selection over unread rows only and clears it once the mark lands', async () => {
    const { wrapper, notifications } = await mountPanel();
    const boxes = wrapper.findAll('.notifications__pick input');

    // 已读行的勾选框是禁用的：对它提交只会换来一次 updated=0。
    expect(boxes[0]?.attributes('disabled')).toBeUndefined();
    expect(boxes[1]?.attributes('disabled')).toBeDefined();
    expect(wrapper.get('[data-testid="notifications-mark-selected"]').text()).toBe('标记所选（0）');
    expect(wrapper.get('[data-testid="notifications-mark-selected"]').attributes('disabled')).toBeDefined();

    await setBox(boxes[0], true);
    expect(wrapper.get('[data-testid="notifications-mark-selected"]').text()).toBe('标记所选（1）');
    await wrapper.get('[data-testid="notifications-mark-selected"]').trigger('click');
    await vi.waitFor(() => expect(notifications.lastUpdated).toBe(1));

    expect(callLog.notificationReads).toEqual([
      { all: false, ids: [String(notificationData(1).notificationId)] },
    ]);
    // 勾选只存 ID：重取第一页后已经不存在的行不该留下幽灵勾选。
    // 面板是在 `markRead()` resolve 之后才清空勾选的，也就是重取完成后，因此这里等的同样是渲染。
    await vi.waitFor(() =>
      expect(wrapper.get('[data-testid="notifications-mark-selected"]').text()).toBe('标记所选（0）'),
    );
  });

  it('spells out what a zero-row mark actually means', async () => {
    const { wrapper, notifications } = await mountPanel();

    await wrapper.get('[data-testid="notifications-mark-all"]').trigger('click');
    await vi.waitFor(() => expect(notifications.lastUpdated).toBe(2));
    // 第二次点击必须等第一次标记彻底结束：`marking` 在途时 store 直接拒绝，
    // 而 `lastUpdated` 在 POST 成功时就已置好、其后的重取第一页仍算在途，抢先点只会丢一次请求。
    await vi.waitFor(() => expect(notifications.marking).toBe(false));
    expect(wrapper.get('[data-testid="notifications-note"]').text()).toContain('已标记 2 条为已读');
    expect(wrapper.get('[data-testid="notifications-note"]').text()).toContain('未读数只统计已加载页');

    await wrapper.get('[data-testid="notifications-mark-all"]').trigger('click');
    await vi.waitFor(() => expect(notifications.lastUpdated).toBe(0));

    // updated=0 有两种成因（越权或列表过期），不能含糊成「已经全部读完了」。
    expect(wrapper.get('[data-testid="notifications-note"]').text()).toContain(
      '服务端没有把任何一条改为已读',
    );
  });

  it('sends all=true without ids for mark-all-read', async () => {
    const { wrapper, notifications } = await mountPanel();

    await wrapper.get('[data-testid="notifications-mark-all"]').trigger('click');
    await vi.waitFor(() => expect(notifications.lastUpdated).toBe(2));

    // 「全部未读」不依赖已加载页的 id，所以这一批是 2 条而不是可见的 1 条。
    expect(callLog.notificationReads).toEqual([{ all: true }]);
    // `lastUpdated` 是 POST 那一步的结果，而「本页未读」来自其后的重取第一页，等的是后者。
    await vi.waitFor(() => expect(wrapper.text()).toContain('本页未读 0 条'));
  });

  it('re-requests through the server when the unread-only filter changes and drops the selection', async () => {
    const { wrapper, notifications } = await mountPanel();
    await setBox(wrapper.findAll('.notifications__pick input')[0], true);

    await setBox(wrapper.get('[data-testid="notifications-unread-only"]'), true);
    await vi.waitFor(() => expect(notifications.unreadOnly).toBe(true));

    expect(notifications.items.every((item) => item.readAt === null)).toBe(true);
    expect(wrapper.text()).toContain('过滤条件每页重新求值');
    // 换过滤就是换结果集，旧勾选在新集合里没有对应行。
    expect(wrapper.get('[data-testid="notifications-mark-selected"]').text()).toBe('标记所选（0）');
  });

  it('keeps the code and trace visible when a mark is rejected', async () => {
    failedRead();
    const { wrapper } = await mountPanel();

    await setBox(wrapper.findAll('.notifications__pick input')[0], true);
    await wrapper.get('[data-testid="notifications-mark-selected"]').trigger('click');

    await vi.waitFor(() => expect(wrapper.find('[data-testid="notifications-error"]').exists()).toBe(true));
    const alert = wrapper.get('[data-testid="notifications-error"]');
    expect(alert.text()).toContain('令牌无效。');
    expect(alert.text()).toContain('code USR-AUTH-001');
    expect(alert.text()).toContain(`trace ${DEMO_TRACE_ID}`);
  });

  it('offers the next page only while the server keeps issuing a cursor', async () => {
    const { wrapper, notifications } = await mountPanel();
    expect(wrapper.text()).toContain('加载更多通知');

    await wrapper.findAll('button').find((button) => button.text() === '加载更多通知')?.trigger('click');
    await vi.waitFor(() => expect(notifications.items).toHaveLength(3));

    expect(wrapper.findAll('button').some((button) => button.text() === '加载更多通知')).toBe(false);
    // 第三行是没加载过的那条 CUSTOM 通知：它落在看板行之外，所以只说触发时刻。
    expect(wrapper.text()).toContain('每周错题复盘');
  });

  /**
   * 首屏失败时 `fetchFirstPage` 的 finally 仍会把 `listLoaded` 置真（加载骨架必须收起）。
   * 只判 `listLoaded && items.length === 0` 就会在失败页同时渲染「还没有通知」与错误条，
   * 等于把「取数失败」说成「成功但没有数据」（S10-M3）。
   */
  it('shows the failure without an empty state when the first page cannot be loaded', async () => {
    server.use(
      mswHttp.get('/api/v1/notifications', () =>
        HttpResponse.json(
          problem({
            status: 500,
            code: 'SYS-INTERNAL-001',
            detail: '通知列表暂时不可用。',
            instance: '/api/v1/notifications',
          }),
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const { wrapper, notifications } = await mountPanel();

    expect(notifications.listLoaded).toBe(true);
    expect(notifications.items).toHaveLength(0);
    expect(wrapper.find('[data-testid="notifications-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="notifications-error"]').text()).toContain('通知列表暂时不可用。');
    expect(wrapper.text()).not.toContain('还没有通知');
  });
});
