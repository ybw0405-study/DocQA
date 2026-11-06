import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { buildScheduleImportForm, createReminder, importSchedule, listReminders, previewSchedule, updateReminderStatus } from './reminders';
import { ApiError } from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import {
  DEMO_REMINDER_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  envelope,
  problem,
  recordScheduleForm,
  reminderCreateData,
  reminderListItem,
  scheduleImportData,
  schedulePart,
  schedulePreviewData,
  type ScheduleFormCall,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

interface Captured {
  method: string;
  path: string;
  search: string;
  idempotencyKey: string | null;
  body: unknown;
}

const captured: Captured[] = [];

function record(request: Request, body: unknown): void {
  const url = new URL(request.url);
  captured.push({
    method: request.method,
    path: url.pathname,
    search: url.search,
    idempotencyKey: request.headers.get('Idempotency-Key'),
    body,
  });
}

async function expectApiError(action: Promise<unknown>): Promise<ApiError> {
  try {
    await action;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('预期抛出 ApiError，但调用成功了');
}

function problemResponse(status: number, code: string, detail: string, headers: Record<string, string> = {}) {
  return HttpResponse.json(
    problem({ status, code, detail, instance: '/api/v1/reminders' }),
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );
}

const DEADLINE_REQUEST = {
  kind: 'DEADLINE',
  title: '提交线性代数作业',
  timezone: 'Asia/Shanghai',
  dueAt: '2026-10-25T15:59:59Z',
  leadMinutes: 120,
} as const;

describe('reminders api', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    captured.length = 0;
    bindSessionToAuthStore();
    useAuthStore().token = 'demo-access-token';
  });

  it('sends the status filter and cursor exactly as asked and never a key on GET', async () => {
    server.use(
      mswHttp.get('/api/v1/reminders', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope({ items: [reminderListItem(1)], nextCursor: null, limit: 20 }));
      }),
    );

    const page = await listReminders({ status: 'ACTIVE', limit: 20 });
    await listReminders({ cursor: 'PAGE2', limit: 20 });

    expect(page.items).toHaveLength(1);
    expect(captured.map((entry) => entry.search)).toEqual(['?status=ACTIVE&limit=20', '?cursor=PAGE2&limit=20']);
    expect(captured.every((entry) => entry.idempotencyKey === null)).toBe(true);
  });

  it('omits the status parameter entirely for the all-status view instead of sending an empty value', async () => {
    server.use(
      mswHttp.get('/api/v1/reminders', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 20 }));
      }),
    );

    await listReminders({ status: null, limit: 20 });

    // 空值会变成 `status=`，服务端按非法枚举回 400，所以这里必须整段省略。
    expect(captured[0]?.search).toBe('?limit=20');
  });

  it('posts the deadline shape with the caller-chosen key and hands back the envelope traceId', async () => {
    server.use(
      mswHttp.post('/api/v1/reminders', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(envelope(reminderCreateData(DEADLINE_REQUEST)), { status: 201 });
      }),
    );

    const result = await createReminder(DEADLINE_REQUEST, 'CREATE-KEY');

    expect(captured[0]).toMatchObject({
      method: 'POST',
      path: '/api/v1/reminders',
      idempotencyKey: 'CREATE-KEY',
      body: { kind: 'DEADLINE', title: '提交线性代数作业', timezone: 'Asia/Shanghai', dueAt: '2026-10-25T15:59:59Z', leadMinutes: 120 },
    });
    // ReminderCreateData 里没有 traceId，只有信封有；报障对账要的就是它。
    expect(result.traceId).toBe(DEMO_TRACE_ID);
    expect(result.data.nextTriggerAt).toBe('2026-10-25T13:59:59.000Z');
  });

  it('keeps the idempotency conflict code and trace on a rejected create', async () => {
    server.use(
      mswHttp.post('/api/v1/reminders', () =>
        problemResponse(409, 'REM-CONFLICT-001', '同一 Idempotency-Key 已用于不同请求。'),
      ),
    );

    const error = await expectApiError(createReminder(DEADLINE_REQUEST, 'K'));

    expect(error.status).toBe(409);
    expect(error.code).toBe('REM-CONFLICT-001');
    expect(error.traceId).toBe(DEMO_TRACE_ID);
    expect(error.retryAfterSeconds).toBeNull();
  });

  it('keeps the rule validation code from the 422 the contract assigns to illegal RRULE', async () => {
    server.use(
      mswHttp.post('/api/v1/reminders', () =>
        problemResponse(422, 'REM-VALIDATION-001', 'RRULE 或时区非法，请检查后重试。'),
      ),
    );

    const error = await expectApiError(createReminder(
      { kind: 'CUSTOM', title: '高等数学课程', timezone: 'Asia/Shanghai', rrule: 'FREQ=WEEKLY;BYDAY=MO', dtstart: '2026-09-07T00:00:00Z' },
      'K',
    ));

    expect(error.status).toBe(422);
    expect(error.code).toBe('REM-VALIDATION-001');
  });

  it('carries the contract-declared Retry-After seconds only when the server issued them', async () => {
    server.use(
      mswHttp.post('/api/v1/reminders', () =>
        problemResponse(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。', { 'Retry-After': '30' }),
      ),
    );
    const limited = await expectApiError(createReminder(DEADLINE_REQUEST, 'K'));

    server.use(
      mswHttp.post('/api/v1/reminders', () =>
        problemResponse(429, 'COM-RATE_LIMIT-001', '请求过于频繁，请稍后重试。'),
      ),
    );
    const vague = await expectApiError(createReminder(DEADLINE_REQUEST, 'K'));

    expect(limited.retryAfterSeconds).toBe(30);
    expect(vague.retryAfterSeconds).toBeNull();
  });
});

/**
 * 契约 2.3.0 的状态变更入口。断言的重点不是「能不能改状态」，而是三件容易写错的事：
 * 请求体的键集合（`additionalProperties: false`，多带一个字段就是 400）、幂等键由调用方给定、
 * 以及**同态提交是 200 no-op**——把它当成 409 来写客户端会把「连点两次暂停」显示成失败。
 */
describe('reminder status update api (PATCH)', () => {
  function statusHandler(data: Record<string, unknown>, init: { status?: number; headers?: Record<string, string> } = {}) {
    server.use(
      mswHttp.patch('/api/v1/reminders/:reminderId', async ({ request, params }) => {
        const body = (await request.json()) as Record<string, unknown>;
        const url = new URL(request.url);
        captured.push({
          method: request.method,
          // 断言桩实际匹配到的路径段，而不是把 reminderId 再拼一遍：编码错误只有在这里才看得见。
          path: url.pathname,
          search: url.search,
          idempotencyKey: request.headers.get('Idempotency-Key'),
          body: { ...body, __params: String(params.reminderId) },
        });
        return HttpResponse.json(envelope(data), init);
      }),
    );
  }

  beforeEach(() => {
    setActivePinia(createPinia());
    captured.length = 0;
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('sends exactly the two contract keys with the caller-chosen key on the encoded path', async () => {
    statusHandler({
      reminderId: DEMO_REMINDER_ID,
      status: 'PAUSED',
      nextTriggerAt: '2026-10-21T13:59:59.000Z',
      version: 2,
      updatedAt: '2026-10-31T12:00:00.000Z',
      idempotentReplay: false,
    });

    const data = await updateReminderStatus(DEMO_REMINDER_ID, { status: 'PAUSED', version: 1 }, 'STATUS-KEY');

    expect(captured[0]?.method).toBe('PATCH');
    expect(captured[0]?.path).toBe(`/api/v1/reminders/${DEMO_REMINDER_ID}`);
    // 状态、期望版本、幂等键就是这次变更的全部输入；reminderId 只出现在路径里。
    expect(Object.keys(captured[0]?.body as Record<string, unknown>).sort()).toEqual(['__params', 'status', 'version']);
    expect(captured[0]?.idempotencyKey).toBe('STATUS-KEY');
    expect(data.status).toBe('PAUSED');
    expect(data.version).toBe(2);
  });

  it('percent-encodes a reminder id so it cannot escape its own path segment', async () => {
    statusHandler({
      reminderId: '../x',
      status: 'PAUSED',
      nextTriggerAt: null,
      version: 1,
      updatedAt: '2026-10-31T12:00:00.000Z',
      idempotentReplay: false,
    });

    await updateReminderStatus('../x', { status: 'PAUSED', version: 0 }, 'K');

    expect(captured[0]?.path).toBe('/api/v1/reminders/..%2Fx');
  });

  it('treats a same-state submit as a plain 200 with the row echoed back un-bumped', async () => {
    // 服务端这里是 no-op：不校验 version、不推进版本，`idempotentReplay` 仍然是 false。
    statusHandler({
      reminderId: DEMO_REMINDER_ID,
      status: 'ACTIVE',
      nextTriggerAt: '2026-10-21T13:59:59.000Z',
      version: 7,
      updatedAt: '2026-10-30T10:17:12.000Z',
      idempotentReplay: false,
    });

    const data = await updateReminderStatus(DEMO_REMINDER_ID, { status: 'ACTIVE', version: 3 }, 'K');

    expect(data.version).toBe(7);
    expect(data.idempotentReplay).toBe(false);
  });

  it.each([
    { status: 409, code: 'REM-CONFLICT-001', detail: '同一 Idempotency-Key 已用于不同请求。' },
    { status: 409, code: 'REM-CONFLICT-003', detail: '提醒已被更新，请刷新后重试。' },
    { status: 409, code: 'REM-CONFLICT-004', detail: '该提醒当前状态不允许此变更。' },
    { status: 404, code: 'REM-NOT_FOUND-001', detail: '提醒不存在。' },
  ])('keeps $code and the trace for an HTTP $status rejection', async ({ status, code, detail }) => {
    server.use(
      mswHttp.patch('/api/v1/reminders/:reminderId', () =>
        problemResponse(status, code, detail, { 'Retry-After': '30' }),
      ),
    );

    const error = await expectApiError(updateReminderStatus(DEMO_REMINDER_ID, { status: 'CANCELLED', version: 1 }, 'K'));

    expect(error.status).toBe(status);
    expect(error.code).toBe(code);
    expect(error.detail).toBe(detail);
    expect(error.traceId).toBe(DEMO_TRACE_ID);
    // 契约只在 429 上声明 Retry-After：409 里出现这个头也不能让前端造出等待时间。
    expect(error.retryAfterSeconds).toBeNull();
  });

  it('carries the resume failure verbatim so the view can add its own Chinese action note', async () => {
    // 后端这一支传的是英文 detail，且与非法边共用 004：界面必须按动作而不是按文案分支。
    server.use(
      mswHttp.patch('/api/v1/reminders/:reminderId', () =>
        problemResponse(409, 'REM-CONFLICT-004', 'the rule has no occurrence at or after now, so the reminder cannot be resumed'),
      ),
    );

    const error = await expectApiError(updateReminderStatus(DEMO_REMINDER_ID, { status: 'ACTIVE', version: 1 }, 'K'));

    expect(error.detail).toContain('no occurrence at or after now');
  });
});

const ICS_FILE = new File(['BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n'], 'course.ics', { type: 'text/plain' });

const SCHEDULE_FORM = { file: ICS_FILE, fallbackTimezone: 'Asia/Shanghai', leadMinutes: 10 };

interface CapturedForm {
  contentType: string | null;
  call: ScheduleFormCall;
}

describe('schedule import api (multipart)', () => {
  const forms: CapturedForm[] = [];

  async function capture(request: Request): Promise<ScheduleFormCall> {
    const call = await recordScheduleForm(request);
    forms.push({ contentType: request.headers.get('content-type'), call });
    return call;
  }

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    forms.length = 0;
    server.use(
      mswHttp.post('/api/v1/reminders/ics/preview', async ({ request }) => {
        await capture(request);
        return HttpResponse.json(envelope(schedulePreviewData()));
      }),
      mswHttp.post('/api/v1/reminders/ics/import', async ({ request }) => {
        await capture(request);
        return HttpResponse.json(envelope(scheduleImportData()), { status: 201 });
      }),
    );
  });

  it('appends the scalars as strings so they are never treated as message bodies', () => {
    const data = buildScheduleImportForm(SCHEDULE_FORM);

    // 两个标量写成 Blob 会让 Spring 为它们挑选 HttpMessageConverter，实测直接 500。
    expect(Array.from(data.keys())).toEqual(['file', 'fallbackTimezone', 'leadMinutes']);
    expect(data.get('file')).toBeInstanceOf(Blob);
    expect(typeof data.get('fallbackTimezone')).toBe('string');
    expect(typeof data.get('leadMinutes')).toBe('string');
  });

  it('sends the file part as calendar text and the two parameters without a part type', async () => {
    const result = await previewSchedule(SCHEDULE_FORM);
    const call = forms[0]?.call;
    if (call === undefined) {
      throw new Error('桩没有记录到 multipart 请求，Part 形态无从断言。');
    }

    expect(call.parts.map((part) => part.name).sort()).toEqual(['fallbackTimezone', 'file', 'leadMinutes']);
    expect(schedulePart(call, 'file')).toMatchObject({
      kind: 'file',
      contentType: 'text/calendar',
      value: 'course.ics',
    });
    expect(schedulePart(call, 'fallbackTimezone')).toMatchObject({
      kind: 'string',
      contentType: null,
      value: 'Asia/Shanghai',
    });
    expect(schedulePart(call, 'leadMinutes')).toMatchObject({
      kind: 'string',
      contentType: null,
      value: '10',
    });
    expect(result.data.items).toHaveLength(2);
  });

  it('lets the browser own the request Content-Type because the boundary lives in it', async () => {
    await importSchedule(SCHEDULE_FORM, 'IMPORT-KEY');

    // 手写 `multipart/form-data` 会丢掉 boundary，服务端连信封都解不开，只能拿到 415。
    const contentType = forms[0]?.contentType ?? '';
    expect(contentType).toContain('multipart/form-data');
    expect(contentType).toContain('boundary=');
  });

  it('keeps the caller-chosen key on the import while each preview stays independent', async () => {
    await previewSchedule(SCHEDULE_FORM);
    await previewSchedule(SCHEDULE_FORM);
    await importSchedule(SCHEDULE_FORM, 'IMPORT-KEY');

    const keys = forms.map((form) => form.call.key);
    // 预览这一路契约上「接受但不消费」幂等键：拦截器补的随机键只是统一形态。
    expect(keys[0]).not.toBeNull();
    expect(keys[0]).not.toBe(keys[1]);
    expect(keys[2]).toBe('IMPORT-KEY');
  });

  it.each([
    { status: 400, code: 'COM-VALIDATION-001', detail: '缺少必需的 file Part。' },
    { status: 413, code: 'REM-VALIDATION-002', detail: '课表文件不能超过 2 MiB。' },
    { status: 415, code: 'REM-VALIDATION-003', detail: '课表文件必须是 ICS 文本（text/calendar 或 application/octet-stream）。' },
    { status: 422, code: 'REM-VALIDATION-001', detail: '第 12 行的 VEVENT 缺少 END:VEVENT。' },
    { status: 409, code: 'REM-CONFLICT-002', detail: '同一课程的导入请求正在进行，请稍后重试。' },
  ])('keeps $code for an import rejected with HTTP $status', async ({ status, code, detail }) => {
    server.use(mswHttp.post('/api/v1/reminders/ics/import', () => problemResponse(status, code, detail)));

    const error = await expectApiError(importSchedule(SCHEDULE_FORM, 'K'));

    expect(error.status).toBe(status);
    expect(error.code).toBe(code);
    expect(error.detail).toBe(detail);
    expect(error.traceId).toBe(DEMO_TRACE_ID);
  });
});
