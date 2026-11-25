import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useRemindersStore } from './reminders';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import {
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  callLog,
  envelope,
  exhaustedReminders,
  problem,
  reminderCreateData,
  reminderListItem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

const searchLog: string[] = [];

/**
 * 看板请求的 keyset 假实现：首页给 1、2 并签发 PAGE2，PAGE2 给第 3 行并到末页，
 * 其余游标按服务端口径 400。同时记录查询串，用于断言过滤与翻页参数而不是内部对象。
 */
function recordListSearch(): void {
  searchLog.length = 0;
  server.use(
    mswHttp.get('/api/v1/reminders', ({ request }) => {
      const params = new URL(request.url).searchParams;
      searchLog.push(new URL(request.url).search);
      const cursor = params.get('cursor');
      if (cursor === null) {
        return HttpResponse.json(
          envelope({ items: [reminderListItem(1), reminderListItem(2)], nextCursor: 'PAGE2', limit: 20 }),
        );
      }
      if (cursor === 'PAGE2') {
        return HttpResponse.json(envelope({ items: [reminderListItem(3)], nextCursor: null, limit: 20 }));
      }
      return HttpResponse.json(
        problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法。', instance: '/api/v1/reminders' }),
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }),
  );
}

function createFailure(status: number, code: string, detail: string, headers: Record<string, string> = {}) {
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

/** 第一次被限流、第二次成功，用于验证同键重试最终仍能落库。 */
function createFailureThenSuccess(status: number, code: string, detail: string) {
  let served = 0;
  server.use(
    mswHttp.post('/api/v1/reminders', async ({ request }) => {
      callLog.reminderKeys.push(request.headers.get('Idempotency-Key') ?? '');
      const body = (await request.json()) as Record<string, unknown>;
      callLog.reminderBodies.push(body);
      served += 1;
      if (served === 1) {
        return HttpResponse.json(
          problem({ status, code, detail, instance: '/api/v1/reminders' }),
          { status, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }
      return HttpResponse.json(envelope(reminderCreateData(body)), { status: 201 });
    }),
  );
}

/** 契约把 200 保留给「同键重放，返回首次创建结果」。 */
function replayHandler(): void {
  server.use(
    mswHttp.post('/api/v1/reminders', async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(
        envelope({ ...reminderCreateData(body), idempotentReplay: true }),
        { status: 200 },
      );
    }),
  );
}

function fillDeadlineForm(target: ReturnType<typeof useRemindersStore>): void {
  target.patchForm({ kind: 'DEADLINE', timezone: 'UTC', title: '作业', dueAt: '2026-11-01T01:30' });
}

describe('reminders store', () => {
  let store: ReturnType<typeof useRemindersStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useRemindersStore();
  });

  it('loads the first page and groups it by status without reordering', async () => {
    await store.loadListFirst();

    expect(store.items.map((item) => item.reminderId)).toEqual([
      reminderListItem(1).reminderId,
      reminderListItem(2).reminderId,
    ]);
    expect(store.groups.map((group) => group.heading)).toEqual(['待触发', '已暂停，到点不会响']);
    expect(store.groups[0]?.items.map((item) => item.reminderId)).toEqual([reminderListItem(1).reminderId]);
    // 暂停行带着保留的时刻，但它不会响，所以不能出现在待触发组里。
    expect(store.groups[1]?.items[0]?.nextTriggerAt).not.toBeNull();
    expect(store.canLoadMore).toBe(true);
  });

  it('appends the cursor page and stops offering more once the server stops issuing one', async () => {
    await store.loadListFirst();
    await store.loadListMore();

    expect(store.items).toHaveLength(3);
    expect(store.nextCursor).toBeNull();
    expect(store.canLoadMore).toBe(false);

    await store.loadListMore();
    expect(store.items).toHaveLength(3);
  });

  it('returns the dashboard to the first page when the cursor is rejected and never reuses it', async () => {
    recordListSearch();
    await store.loadListFirst();
    await store.loadListMore();

    store.nextCursor = 'PAGE3';
    await store.loadListMore();

    // 回落的那次请求必须不带被拒游标；同时坏游标恢复要先释放 loading，否则会被自己挡住。
    expect(searchLog).toEqual(['?limit=20', '?cursor=PAGE2&limit=20', '?cursor=PAGE3&limit=20', '?limit=20']);
    expect(store.items).toHaveLength(2);
    expect(store.listLoading).toBe(false);
    expect(store.detail).toBe('提醒翻页游标已失效，看板已回到第一页。');
  });

  it('clears the already-appended rows and re-requests when the filter changes', async () => {
    recordListSearch();
    await store.loadListFirst();
    await store.loadListMore();
    expect(store.items).toHaveLength(3);

    await store.updateStatusFilter('ACTIVE');

    expect(store.statusFilter).toBe('ACTIVE');
    expect(searchLog[2]).toBe('?status=ACTIVE&limit=20');
    // 换了过滤条件的结果集就不能再留着旧过滤下拼好的行。
    expect(store.items).toHaveLength(2);
  });

  it('keeps the status filter on later pages so paging stays inside one result set', async () => {
    recordListSearch();
    await store.updateStatusFilter('PAUSED');
    await store.loadListMore();

    expect(searchLog).toEqual(['?status=PAUSED&limit=20', '?status=PAUSED&cursor=PAGE2&limit=20']);
  });

  it('treats an empty filter value as the all-status view rather than an empty parameter', async () => {
    recordListSearch();
    await store.updateStatusFilter('ACTIVE');
    await store.updateStatusFilter('');

    expect(store.statusFilter).toBeNull();
    expect(searchLog[1]).toBe('?limit=20');
  });

  it('sends the deadline shape with a UTC instant and none of the custom-rule fields', async () => {
    store.patchForm({
      kind: 'DEADLINE',
      timezone: 'Asia/Shanghai',
      title: '提交线性代数作业',
      dueAt: '2026-10-25T15:59',
      leadMinutes: '120',
    });

    await store.submitReminder();

    // 表单默认留着 RRULE 起点，但 DEADLINE 形态是 additionalProperties: false，多一个键就会被 400。
    expect(callLog.reminderBodies[0]).toEqual({
      kind: 'DEADLINE',
      title: '提交线性代数作业',
      timezone: 'Asia/Shanghai',
      dueAt: new Date('2026-10-25T15:59').toISOString(),
      leadMinutes: 120,
    });
    expect(store.createdSummary?.nextTriggerAt).toBe(
      new Date(new Date('2026-10-25T15:59').getTime() - 120 * 60_000).toISOString(),
    );
  });

  it('drops the leftover deadline fields once the kind switches to a recurring rule', async () => {
    store.patchForm({
      kind: 'DEADLINE',
      timezone: 'UTC',
      title: '每周组会复习',
      dueAt: '2026-10-25T15:59',
      leadMinutes: '120',
    });
    store.patchForm({ kind: 'CUSTOM', rrule: 'FREQ=WEEKLY;BYDAY=MO', dtstart: '2026-10-26T02:00' });

    await store.submitReminder();

    expect(callLog.reminderBodies[0]).toEqual({
      kind: 'CUSTOM',
      title: '每周组会复习',
      timezone: 'UTC',
      rrule: 'FREQ=WEEKLY;BYDAY=MO',
      dtstart: new Date('2026-10-26T02:00').toISOString(),
    });
  });

  it('omits an empty description instead of submitting an empty string into the fingerprint', async () => {
    store.patchForm({ kind: 'DEADLINE', timezone: 'UTC', title: '测验', description: ' ', dueAt: '2026-11-01T01:30' });
    await store.submitReminder();
    expect(callLog.reminderBodies[0]).not.toHaveProperty('description');

    store.patchForm({ description: '教学楼 A-203' });
    await store.submitReminder();
    expect(callLog.reminderBodies[1]).toHaveProperty('description', '教学楼 A-203');
  });

  it('refuses illegal input locally without a code, a trace or a single request', async () => {
    await store.submitReminder();
    expect(store.detail).toBe('请填写提醒标题。');

    store.patchForm({ title: '作业' });
    await store.submitReminder();
    expect(store.detail).toBe('请选择作业截止时间。');

    store.patchForm({ dueAt: '不是时间' });
    await store.submitReminder();
    expect(store.detail).toBe('请选择作业截止时间。');

    store.patchForm({ dueAt: '2026-11-01T01:30', leadMinutes: '20000' });
    await store.submitReminder();
    expect(store.detail).toBe('提前提醒分钟数必须是 0–10080 之间的整数。');

    store.patchForm({ kind: 'CUSTOM', timezone: 'Shang Hai', rrule: 'FREQ=WEEKLY;BYDAY=MO', dtstart: '2026-11-02T01:00' });
    await store.submitReminder();
    expect(store.detail).toBe('时区格式不合法，请使用 IANA 名称（例如 Asia/Shanghai 或 UTC）。');

    store.patchForm({ timezone: 'Asia/Shanghai', rrule: 'freq=weekly' });
    await store.submitReminder();
    expect(store.detail).toContain('RRULE 必须以 FREQ=');

    expect(callLog.reminderKeys).toEqual([]);
    expect(store.code).toBeNull();
    expect(store.traceId).toBeNull();
  });

  it('keeps the idempotency key after a rejected create and reuses it on the next attempt', async () => {
    createFailure(409, 'REM-CONFLICT-001', '同一 Idempotency-Key 已用于不同请求。');
    fillDeadlineForm(store);

    await store.submitReminder();
    await store.submitReminder();

    expect(callLog.reminderKeys).toHaveLength(2);
    expect(callLog.reminderKeys[0]).toBe(callLog.reminderKeys[1]);
    expect(store.pendingKey).toBe(callLog.reminderKeys[0]);
    expect(store.code).toBe('REM-CONFLICT-001');
    expect(store.traceId).toBe(DEMO_TRACE_ID);
  });

  it('mints a fresh key when any form field actually changes after a failure', async () => {
    createFailure(422, 'REM-VALIDATION-001', 'RRULE 或时区非法，请检查后重试。');
    fillDeadlineForm(store);
    await store.submitReminder();
    const firstKey = callLog.reminderKeys[0];
    expect(firstKey).toBeTruthy();

    // 与当前值完全相同的补丁不构成新尝试，键必须留着。
    store.patchForm({ title: '作业' });
    expect(store.pendingKey).toBe(firstKey);

    store.patchForm({ title: '作业（改）' });
    expect(store.pendingKey).toBeNull();

    await store.submitReminder();
    expect(callLog.reminderKeys[1]).not.toBe(firstKey);
  });

  it('releases the key and keeps a trace handle when the create is a replay', async () => {
    replayHandler();
    fillDeadlineForm(store);

    await store.submitReminder();

    expect(store.pendingKey).toBeNull();
    expect(store.creating).toBe(false);
    expect(store.createdSummary).toEqual({
      title: '作业',
      nextTriggerAt: new Date(new Date('2026-11-01T01:30').getTime() - 120 * 60_000).toISOString(),
      idempotentReplay: true,
      traceId: DEMO_TRACE_ID,
    });
    // 成功后回第一页：新行按 next_trigger_at 排序，只会漏读不会重复。
    expect(store.items).toHaveLength(2);
    expect(store.detail).toBeNull();
  });

  it('reuses one key until a rate-limited create finally lands', async () => {
    createFailureThenSuccess(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。');
    fillDeadlineForm(store);

    await store.submitReminder();
    // 桩没有给 Retry-After，前端也不自造等待时间。
    expect(store.retryAfterSeconds).toBeNull();
    await store.submitReminder();

    expect(callLog.reminderKeys[0]).toBe(callLog.reminderKeys[1]);
    expect(store.createdSummary?.title).toBe('作业');
    expect(store.items).toHaveLength(2);
  });
});

/**
 * 契约 2.3.0 的 `PATCH /api/v1/reminders/{reminderId}`。这里的桩按 `ReminderStatusService` 的判定顺序
 * 写态、看板 GET 读穿同一份态，所以「失败后重读看板」拿到的是上一次成功变更后的行，
 * 而不是每次都不变的夹具——那会让重读分支在前端测试里自相矛盾。
 */
describe('reminders store status machine', () => {
  let store: ReturnType<typeof useRemindersStore>;

  const ACTIVE_ROW = reminderListItem(1);
  const PAUSED_ROW = reminderListItem(2);

  beforeEach(async () => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useRemindersStore();
    await store.loadListFirst();
  });

  function rowOf(reminderId: string): ReturnType<typeof reminderListItem> {
    const row = store.items.find((item) => item.reminderId === reminderId);
    if (row === undefined) {
      throw new Error('看板里没有这一行。');
    }
    return row;
  }

  it('backfills only the four status fields and leaves the rest of the row alone', async () => {
    const ok = await store.updateStatus(ACTIVE_ROW.reminderId, 'PAUSED');

    expect(ok).toBe(true);
    const row = rowOf(ACTIVE_ROW.reminderId);
    expect(row.status).toBe('PAUSED');
    expect(row.version).toBe(2);
    expect(row.updatedAt).toBe('2026-10-31T12:00:00.000Z');
    // 暂停保留「本来下次是几点」，恢复时才按当前时刻重算。
    expect(row.nextTriggerAt).toBe(ACTIVE_ROW.nextTriggerAt);
    // 标题、规则、时区、排除日都不在 PATCH 的响应里，回填是「按共有字段改这一行」而不是整行替换。
    expect(row.title).toBe(ACTIVE_ROW.title);
    expect(row.rrule).toBe(ACTIVE_ROW.rrule);
    expect(row.timezone).toBe(ACTIVE_ROW.timezone);
    expect(row.exdates).toEqual(ACTIVE_ROW.exdates);
  });

  it('moves a completed reminder into the terminal group and never back', async () => {
    await store.updateStatus(ACTIVE_ROW.reminderId, 'COMPLETED');

    const row = rowOf(ACTIVE_ROW.reminderId);
    expect(row.status).toBe('COMPLETED');
    expect(row.nextTriggerAt).toBeNull();
    const group = store.groups.find((candidate) => candidate.items.some((item) => item.reminderId === ACTIVE_ROW.reminderId));
    expect(group?.heading).toBe('不再有下一次触发');
    // 分组按状态而不是「时刻是否为空」：暂停行带着保留的时刻，它有自己的组。
    expect(store.groups.map((candidate) => candidate.heading)).toEqual(['已暂停，到点不会响', '不再有下一次触发']);
  });

  it('lets the server recompute the next trigger on resume so missed occurrences are not replayed', async () => {
    const retained = rowOf(PAUSED_ROW.reminderId).nextTriggerAt;
    await store.updateStatus(PAUSED_ROW.reminderId, 'ACTIVE');

    const resumed = rowOf(PAUSED_ROW.reminderId);
    // 暂停期间保留的旧计划值必须被服务端重算结果换掉，否则恢复等于「按暂停前的时刻补发」。
    expect(resumed.nextTriggerAt).toBe('2026-12-31T01:00:00.000Z');
    expect(resumed.nextTriggerAt).not.toBe(retained);
    expect(store.groups[0]?.items.map((item) => item.reminderId)).toContain(PAUSED_ROW.reminderId);
  });

  it('refuses all three local gates without spending a request', async () => {
    const missing = await store.updateStatus('00000000-0000-0000-0000-0000000000ff', 'PAUSED');
    expect(missing).toBe(false);
    expect(store.detail).toBe('看板里已经没有这一行，请重新加载后再操作。');

    const sameState = await store.updateStatus(ACTIVE_ROW.reminderId, 'ACTIVE');
    expect(sameState).toBe(false);
    // 服务端对同态是 200 no-op，但界面上它不该被说成「终态不可回退」。
    expect(store.detail).toContain('已经是该状态');

    await store.updateStatus(ACTIVE_ROW.reminderId, 'CANCELLED');
    const terminal = await store.updateStatus(ACTIVE_ROW.reminderId, 'ACTIVE');
    expect(terminal).toBe(false);
    expect(store.detail).toContain('已完成或已取消的提醒不能回退');

    expect(store.code).toBeNull();
    expect(store.traceId).toBeNull();
    // 三条闸门都在请求发出之前，所以一次 PATCH 都不该出去。
    expect(callLog.statusCalls).toHaveLength(1);
  });

  it('clears the pending key and re-reads the dashboard on every flavour of 409', async () => {
    // 本地把行版本改成与服务端不符的值，模拟「别处改过」或「它刚触发过一次」。
    store.items = store.items.map((item) => (item.reminderId === ACTIVE_ROW.reminderId ? { ...item, version: 99 } : item));

    const ok = await store.updateStatus(ACTIVE_ROW.reminderId, 'PAUSED');

    expect(ok).toBe(false);
    expect(store.code).toBe('REM-CONFLICT-003');
    expect(store.traceId).toBe(DEMO_TRACE_ID);
    expect(store.pendingUpdate).toBeNull();
    // 重读后看板回到服务端的真实版本，而不是继续留着那个 99。
    expect(rowOf(ACTIVE_ROW.reminderId).version).toBe(1);
    // 中文 detail 本身带句号，拼接后不能出现两个连续句号。
    expect(store.detail).toBe('状态变更未生效：提醒已被更新，请刷新后重试。看板已按服务端最新状态重新读取，请据此再决定。');
    expect(store.detail).not.toContain('。。');
  });

  it('adds its own Chinese action note when the server refuses a resume in English', async () => {
    exhaustedReminders.add(PAUSED_ROW.reminderId);

    const ok = await store.updateStatus(PAUSED_ROW.reminderId, 'ACTIVE');

    expect(ok).toBe(false);
    expect(store.code).toBe('REM-CONFLICT-004');
    // 服务端原文是英文，且与非法边共用同一个 004：界面不能把原文当结论，也不能按文案分支。
    expect(store.detail).toContain('no occurrence at or after now');
    expect(store.detail).toContain('看板已按服务端最新状态重新读取');
    expect(rowOf(PAUSED_ROW.reminderId).status).toBe('PAUSED');
  });

  it('keeps the key across a non-409 failure and mints a fresh one once the version changes', async () => {
    server.use(
      mswHttp.patch('/api/v1/reminders/:reminderId', async ({ request }) => {
        callLog.statusCalls.push({
          reminderId: new URL(request.url).pathname.split('/').pop() ?? '',
          key: request.headers.get('Idempotency-Key'),
          body: (await request.json()) as { status?: string; version?: number },
        });
        return HttpResponse.json(
          problem({ status: 429, code: 'COM-RATE_LIMIT-001', detail: '请求过于频繁，请稍后重试。', instance: '/api/v1/reminders' }),
          { status: 429, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    await store.updateStatus(ACTIVE_ROW.reminderId, 'PAUSED');
    const first = store.pendingUpdate;
    expect(first).not.toBeNull();

    // 限流不是「这次变更不成立」，同键重试才能拿到首次结果，所以键必须留着。
    expect(store.retryAfterSeconds).toBeNull();
    await store.updateStatus(ACTIVE_ROW.reminderId, 'PAUSED');
    expect(callLog.statusCalls[1]?.key).toBe(first?.key);

    // 行版本一变就是另一个请求体，继续沿用旧键只会撞 409 REM-CONFLICT-001。
    store.items = store.items.map((item) => (item.reminderId === ACTIVE_ROW.reminderId ? { ...item, version: 42 } : item));
    await store.updateStatus(ACTIVE_ROW.reminderId, 'PAUSED');
    expect(callLog.statusCalls[2]?.key).not.toBe(first?.key);
    expect(callLog.statusCalls[2]?.body).toEqual({ status: 'PAUSED', version: 42 });
  });

  it('releases the row from a filtered board through the server rather than locally', async () => {
    await store.updateStatusFilter('ACTIVE');
    expect(store.items.map((item) => item.reminderId)).toEqual([ACTIVE_ROW.reminderId]);

    await store.updateStatus(ACTIVE_ROW.reminderId, 'PAUSED');

    // 按「进行中」过滤后把它暂停：回填会留下一行与筛选条件矛盾的记录，所以这里重取第一页让它按服务端口径消失。
    expect(store.items).toHaveLength(0);
    expect(store.detail).toBeNull();
  });

  it('holds one PATCH at a time so a second click never overlaps the first', async () => {
    const first = store.updateStatus(ACTIVE_ROW.reminderId, 'PAUSED');
    const second = await store.updateStatus(PAUSED_ROW.reminderId, 'ACTIVE');

    expect(second).toBe(false);
    expect(await first).toBe(true);
    // 被挡住的那一次根本不该发请求，否则两次的 version 归属就说不清。
    expect(callLog.statusCalls.map((call) => call.reminderId)).toEqual([ACTIVE_ROW.reminderId]);
    expect(store.updatingId).toBeNull();
  });
});
