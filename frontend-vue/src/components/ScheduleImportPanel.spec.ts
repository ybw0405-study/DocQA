import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import type { ComponentPublicInstance } from 'vue';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import ScheduleImportPanel from './ScheduleImportPanel.vue';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { useScheduleImportStore } from '../stores/scheduleImport';
import { ICS_MAX_BYTES } from '../types/reminders';
import {
  DEMO_SCHEDULE_ECHO,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  callLog,
  envelope,
  problem,
  scheduleImportData,
  scheduleItem,
  schedulePreviewData,
} from '../../tests/fixtures';
import { server } from '../../tests/server';

const PREVIEW_PATH = '/api/v1/reminders/ics/preview';
const IMPORT_PATH = '/api/v1/reminders/ics/import';
const ICS = new File(['BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n'], 'course.ics');

async function pickFile<V extends ComponentPublicInstance>(
  wrapper: VueWrapper<V>,
  file: File = ICS,
): Promise<void> {
  const input = wrapper.get('input[type="file"]');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
}

async function previewed<V extends ComponentPublicInstance>(wrapper: VueWrapper<V>): Promise<void> {
  await pickFile(wrapper);
  await wrapper.get('[data-testid="ics-preview"]').trigger('click');
  await vi.waitFor(() => expect(useScheduleImportStore().preview).not.toBeNull());
}

async function imported<V extends ComponentPublicInstance>(wrapper: VueWrapper<V>): Promise<void> {
  await previewed(wrapper);
  await wrapper.get('[data-testid="ics-import"]').trigger('click');
  await vi.waitFor(() => expect(useScheduleImportStore().result).not.toBeNull());
}

function previewRespondingWith(data: unknown): void {
  server.use(mswHttp.post(PREVIEW_PATH, () => HttpResponse.json(envelope(data))));
}

/** 让指定路径只回一个 ProblemDetail：状态码与业务码必须原样走到界面上。 */
function failsAt(path: string, status: number, code: string, detail: string): void {
  server.use(
    mswHttp.post(path, () =>
      HttpResponse.json(
        problem({ status, code, detail, instance: path }),
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

describe('ScheduleImportPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('explains a missing file instead of a dead button and gates import behind the preview', async () => {
    const wrapper = mount(ScheduleImportPanel);

    // 没选文件也能点预览：原因要显示出来，否则用户只看到一个不动的按钮。
    await wrapper.get('[data-testid="ics-preview"]').trigger('click');
    expect(wrapper.get('[data-testid="ics-error"]').text()).toContain('请先选择');
    expect(callLog.scheduleForms).toHaveLength(0);
    expect(wrapper.get('[data-testid="ics-import"]').attributes('disabled')).toBeDefined();

    await pickFile(wrapper);
    expect(wrapper.text()).toContain('必须先预览判定');

    await wrapper.get('[data-testid="ics-preview"]').trigger('click');
    await vi.waitFor(() => expect(wrapper.get('[data-testid="ics-import"]').attributes('disabled')).toBeUndefined());
    expect(wrapper.text()).not.toContain('必须先预览判定');
  });

  it('renders the four-count summary and one row per event decision', async () => {
    const wrapper = mount(ScheduleImportPanel);
    await previewed(wrapper);

    expect(wrapper.text()).toContain('预览判定');
    expect(wrapper.text()).toContain('共 2 个事件');
    expect(wrapper.text()).toContain('新建 1');
    expect(wrapper.text()).toContain('无变化 0');
    expect(wrapper.text()).toContain('旧版忽略 1');
    expect(wrapper.find('.schedule-count--warn').exists()).toBe(true);
    expect(wrapper.text()).toContain('有 1 个事件的文件版本比库内更旧');

    const rows = wrapper.findAll('.schedule-table tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.text()).toContain('线性代数');
    expect(rows[0]?.text()).toContain('3 → —');
    expect(rows[1]?.text()).toContain('离散数学');
    expect(rows[1]?.text()).toContain('1 → 4');
    expect(rows[1]?.text()).toContain('旧版忽略');
  });

  it('marks an event with no future trigger instead of leaving a bare dash', async () => {
    previewRespondingWith(
      schedulePreviewData(DEMO_SCHEDULE_ECHO, [scheduleItem({ nextTriggerAt: null, followingTriggerAt: null })]),
    );
    const wrapper = mount(ScheduleImportPanel);
    await previewed(wrapper);

    expect(wrapper.text()).toContain('有 1 个事件算不出未来触发时刻');
    expect(wrapper.find('.schedule-table tbody tr')?.text()).toContain('不触发');
  });

  it('never sends a file the contract already rejects with 413', async () => {
    const wrapper = mount(ScheduleImportPanel);
    await pickFile(wrapper, new File([new Uint8Array(ICS_MAX_BYTES + 1)], 'big.ics'));

    await wrapper.get('[data-testid="ics-preview"]').trigger('click');

    expect(wrapper.get('[data-testid="ics-error"]').text()).toContain('不能超过');
    // 本地按契约的 x-max-bytes 预拦，一个注定 413 的两兆请求不该真的出门。
    expect(callLog.scheduleForms).toHaveLength(0);
  });

  it('switches the stage to the authoritative import decision', async () => {
    const wrapper = mount(ScheduleImportPanel);
    await imported(wrapper);

    expect(wrapper.text()).toContain('导入后的判定');
    expect(wrapper.text()).toContain('共 2 个事件');
    expect(wrapper.get('[data-testid="ics-result"]').text()).toContain('服务端已按判定写入');
    expect(wrapper.get('[data-testid="ics-result"]').text()).toContain(DEMO_TRACE_ID);
  });

  it('keeps the preview rows visible when a replay returns counts without items', async () => {
    server.use(
      mswHttp.post(IMPORT_PATH, () =>
        HttpResponse.json(envelope(scheduleImportData(DEMO_SCHEDULE_ECHO, undefined, true)), { status: 200 }),
      ),
    );
    const wrapper = mount(ScheduleImportPanel);
    await imported(wrapper);

    expect(wrapper.get('[data-testid="ics-result"]').text()).toContain('同一幂等键的重放结果');
    expect(wrapper.text()).toContain('导入后的判定');
    // 重放没有明细，表格退回预览行：一张空表会被读成「什么都没导入」。
    expect(wrapper.findAll('.schedule-table tbody tr')).toHaveLength(2);
  });

  it('presents the conflict code and trace and offers a same-key retry', async () => {
    failsAt(IMPORT_PATH, 409, 'REM-CONFLICT-002', '同一课程的导入请求正在进行，请稍后重试。');
    const wrapper = mount(ScheduleImportPanel);
    await previewed(wrapper);

    await wrapper.get('[data-testid="ics-import"]').trigger('click');

    await vi.waitFor(() => expect(wrapper.find('[data-testid="ics-error"]').exists()).toBe(true));
    const error = wrapper.get('[data-testid="ics-error"]');
    expect(error.text()).toContain('REM-CONFLICT-002');
    expect(error.text()).toContain(DEMO_TRACE_ID);
    expect(error.attributes('role')).toBe('alert');
    expect(wrapper.get('[data-testid="ics-import"]').text()).toBe('重试同一请求');
  });

  it('presents the 415 the server raises for a non-calendar media type', async () => {
    failsAt(
      PREVIEW_PATH,
      415,
      'REM-VALIDATION-003',
      '课表文件必须是 ICS 文本（text/calendar 或 application/octet-stream）。',
    );
    const wrapper = mount(ScheduleImportPanel);
    await pickFile(wrapper);

    await wrapper.get('[data-testid="ics-preview"]').trigger('click');

    await vi.waitFor(() => expect(wrapper.find('[data-testid="ics-error"]').exists()).toBe(true));
    expect(wrapper.get('[data-testid="ics-error"]').text()).toContain('REM-VALIDATION-003');
    expect(wrapper.find('.schedule-table').exists()).toBe(false);
  });

  it('renders an untrusted summary and uid as escaped text', async () => {
    previewRespondingWith(
      schedulePreviewData(DEMO_SCHEDULE_ECHO, [
        scheduleItem({ summary: '<img src=x onerror=alert(1)>', uid: '<script>steal()</script>' }),
      ]),
    );
    const wrapper = mount(ScheduleImportPanel);
    await previewed(wrapper);

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');
    expect(wrapper.text()).toContain('<script>steal()</script>');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('script').exists()).toBe(false);
  });
});
