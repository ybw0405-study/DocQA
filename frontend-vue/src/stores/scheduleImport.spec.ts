import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useScheduleImportStore } from './scheduleImport';
import { useRemindersStore } from './reminders';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import { ICS_MAX_BYTES } from '../types/reminders';
import {
  callLog,
  DEMO_SCHEDULE_ECHO,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  envelope,
  problem,
  recordScheduleForm,
  scheduleImportData,
  scheduleItem,
  staleScheduleItem,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

const ICS = new File(['BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n'], 'course.ics');
const IMPORT_PATH = '/api/v1/reminders/ics/import';

function conflictResponse(): Response {
  return HttpResponse.json(
    problem({
      status: 409,
      code: 'REM-CONFLICT-001',
      detail: '同一 Idempotency-Key 已用于不同请求。',
      instance: IMPORT_PATH,
    }),
    { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** 冲突桩也要记录线上形态：否则「两次导入共用同一个键」就没有凭据可断言。 */
function conflictOnImport() {
  return mswHttp.post(IMPORT_PATH, async ({ request }) => {
    await recordScheduleForm(request);
    return conflictResponse();
  });
}

/** 走完「选文件 + 预览成功」这段公共前置，返回已可导入的状态。 */
async function previewed() {
  const schedule = useScheduleImportStore();
  schedule.selectFile(ICS);
  await schedule.runPreview();
  return schedule;
}

describe('scheduleImport store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('blocks the four local failures without sending anything', async () => {
    const schedule = useScheduleImportStore();

    await schedule.runPreview();
    expect(schedule.detail).toBe('请先选择 .ics 课表文件。');

    schedule.selectFile(new File([new Uint8Array(ICS_MAX_BYTES + 1)], 'big.ics'));
    await schedule.runPreview();
    // 超限 1 字节也要能读出「超了多少」：两侧都只显示取整后的 2.0 MiB 是一句自相矛盾的话。
    expect(schedule.detail).toBe(
      `课表文件不能超过 ${ICS_MAX_BYTES} 字节（2.0 MiB），当前 ${ICS_MAX_BYTES + 1} 字节。`,
    );

    schedule.selectFile(ICS);
    schedule.patchForm({ fallbackTimezone: '+08:00' });
    await schedule.runPreview();
    expect(schedule.detail).toContain('兜底时区格式不合法');

    schedule.patchForm({ fallbackTimezone: 'Asia/Shanghai' });
    schedule.patchForm({ leadMinutes: '20000' });
    await schedule.runPreview();
    expect(schedule.detail).toContain('整数');

    // 本地预检拦下的请求一次都不该出门：错误码与 trace 因此都是空的。
    expect(callLog.scheduleForms).toHaveLength(0);
    expect(schedule.code).toBeNull();
    expect(schedule.traceId).toBeNull();
  });

  it('keeps the preview trace apart from the failure trace', async () => {
    const schedule = await previewed();

    expect(schedule.preview?.itemCount).toBe(2);
    expect(schedule.previewTraceId).toBe(DEMO_TRACE_ID);
    expect(schedule.traceId).toBeNull();
    expect(schedule.canImport).toBe(true);
  });

  it('voids the decision when the file changes but not when the same file is picked again', async () => {
    const schedule = await previewed();

    schedule.selectFile(ICS);
    expect(schedule.preview).not.toBeNull();

    schedule.selectFile(new File(['BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'], 'other.ics'));
    expect(schedule.preview).toBeNull();
    expect(schedule.canImport).toBe(false);
  });

  it('reuses the failed import key on retry because a new key writes a whole new batch', async () => {
    server.use(conflictOnImport());
    const schedule = await previewed();

    await schedule.commitImport();
    const key = schedule.pendingKey;
    expect(key).not.toBeNull();
    expect(schedule.code).toBe('REM-CONFLICT-001');
    expect(schedule.traceId).toBe(DEMO_TRACE_ID);
    expect(schedule.result).toBeNull();

    await schedule.commitImport();
    expect(schedule.pendingKey).toBe(key);
    // 第 0 条是预览，两条导入必须共用同一个键。
    expect(callLog.scheduleForms.map((form) => form.key).slice(1)).toEqual([key, key]);

    // 但改了判定参数就得弃键：指纹变了，留着旧键只会撞上 409。
    schedule.patchForm({ leadMinutes: '20' });
    expect(schedule.pendingKey).toBeNull();
    expect(schedule.preview).toBeNull();
  });

  it('clears the key, keeps its own trace and refreshes the board after a real write', async () => {
    const schedule = await previewed();

    await schedule.commitImport();

    expect(schedule.pendingKey).toBeNull();
    expect(schedule.result?.createCount).toBe(1);
    expect(schedule.resultTraceId).toBe(DEMO_TRACE_ID);
    expect(schedule.traceId).toBeNull();

    // 导入改写的是看板上的课程行，落库后必须重取，否则用户看到的还是导入前的规则。
    const reminders = useRemindersStore();
    expect(reminders.listLoaded).toBe(true);
    expect(reminders.items).toHaveLength(2);
  });

  it('hands a replay back with counts but no items', async () => {
    server.use(
      mswHttp.post(IMPORT_PATH, () =>
        HttpResponse.json(
          envelope(scheduleImportData(DEMO_SCHEDULE_ECHO, [scheduleItem(), staleScheduleItem()], true)),
          { status: 200 },
        ),
      ),
    );
    const schedule = await previewed();

    await schedule.commitImport();

    expect(schedule.result?.idempotentReplay).toBe(true);
    expect(schedule.result?.items).toHaveLength(0);
    // 计数仍是首次结果，明细退回预览——空表会被读成「什么都没导入」。
    expect(schedule.result?.itemCount).toBe(2);
    expect(schedule.preview?.items).toHaveLength(2);
  });

  it('survives a failed preview without discarding the key kept from a failed import', async () => {
    server.use(conflictOnImport());
    const schedule = await previewed();
    await schedule.commitImport();
    const key = schedule.pendingKey;
    expect(key).not.toBeNull();

    server.use(
      mswHttp.post('/api/v1/reminders/ics/preview', () =>
        HttpResponse.json(
          problem({
            status: 422,
            code: 'REM-VALIDATION-001',
            detail: '第 12 行的 VEVENT 缺少 END:VEVENT。',
            instance: '/api/v1/reminders/ics/preview',
          }),
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    // 同一份文件直接重预览：唯一可能抹掉遗留键的路径只剩「预览失败」这一条。
    await schedule.runPreview();

    expect(schedule.code).toBe('REM-VALIDATION-001');
    expect(schedule.previewTraceId).toBeNull();
    // 预览失败不写库，也就没有理由作废那次未完成的导入键。
    expect(schedule.pendingKey).toBe(key);
  });
});
