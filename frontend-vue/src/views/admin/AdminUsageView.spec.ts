import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';
import AdminUsageView from './AdminUsageView.vue';
import { bindSessionToAuthStore } from '../../stores/sessionBridge';
import { useAdminUsageStore } from '../../stores/adminUsage';
import { useAuthStore } from '../../stores/auth';
import { DEMO_TOKEN, envelope, usageSummaryData } from '../../../tests/fixtures';
import { server } from '../../../tests/server';
import { EMPTY_STATE_TEXT, LATENCY_NOTE, USAGE_STATUS_LABELS, formatDateTime } from '../../utils/display';

let mounted: ViewWrapper | null = null;

async function mountView() {
  const wrapper = mount(AdminUsageView);
  mounted = wrapper;
  await vi.waitFor(() => expect(useAdminUsageStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

/** 记录每次用量查询的查询串，用来核对「空窗口不发条件」。 */
function recordingUsage(summary: unknown = usageSummaryData()): URLSearchParams[] {
  const sent: URLSearchParams[] = [];
  server.use(
    mswHttp.get('/api/v1/admin/ai/usage', ({ request }) => {
      sent.push(new URL(request.url).searchParams);
      return HttpResponse.json(envelope(summary));
    }),
  );
  return sent;
}

function stateOf(view: ViewWrapper): Map<string, string> {
  const entries = new Map<string, string>();
  for (const row of view.findAll('.admin-state > div')) {
    entries.set((row.find('dt')?.text() ?? '').trim(), (row.find('dd')?.text() ?? '').trim());
  }
  return entries;
}

/** 比例条的宽度：按表取，避免把三张表的条混成一条序列。 */
function fillsIn(view: ViewWrapper, tableIndex: number): string[] {
  const table = view.findAll('table')[tableIndex];
  if (table === undefined) {
    return [];
  }
  return table.findAll('.ratio-bar__fill').map((fill) => (fill.element as HTMLElement).style.width);
}

function byLabel(view: ViewWrapper, label: string) {
  return view.findAll('button').find((button) => button.text() === label);
}

describe('AdminUsageView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  afterEach(async () => {
    await vi.waitFor(() => {
      const store = useAdminUsageStore();
      expect(store.loading || store.submitting).toBe(false);
    });
    mounted?.unmount();
    mounted = null;
  });

  it('asks for the only published bucket and echoes the server window', async () => {
    const sent = recordingUsage();
    const view = await mountView();

    expect(sent).toHaveLength(1);
    expect(sent[0]?.get('bucket')).toBe('day');
    expect(sent[0]?.get('topUsers')).toBe('10');
    expect(sent[0]?.has('from')).toBe(false);
    const summary = usageSummaryData();
    expect(view.text()).toContain(
      `服务端回显的实际窗口：${formatDateTime(String(summary.from))} — ${formatDateTime(String(summary.to))}（粒度 day）`,
    );
  });

  it('prints the nine totals with budget exhaustion kept apart from failures', async () => {
    const view = await mountView();
    const state = stateOf(view);

    expect(state).toEqual(
      new Map([
        ['问答次数', '128'],
        ['成功', '113'],
        ['预算耗尽', '6'],
        ['失败', '9'],
        ['token 用量', '412882'],
        ['工具调用', '74'],
        ['编排轮次', '301'],
        ['缓存命中', '17'],
        ['平均延迟', '2140.5 ms'],
      ]),
    );
    expect(view.text()).toContain(LATENCY_NOTE);
    expect(view.text()).toContain('「预算耗尽」与「失败」是两个计数');
  });

  it('reads an absent average latency as an empty window, not as zero', async () => {
    const summary = usageSummaryData();
    recordingUsage({ ...summary, totals: { ...summary.totals, avgLatencyMs: null } });
    const view = await mountView();

    expect(stateOf(view).get('平均延迟')).toBe('窗口内无执行');
    expect(view.text()).not.toContain('0 ms');
  });

  it('normalises each ratio bar against the largest value in its own column', async () => {
    const view = await mountView();

    // 按日表：问答列基准 67、token 列基准 214480；每行两条，顺序是问答、token。
    // 视图发出的是 `toFixed(1)` 的三位形态，回读 `style.width` 时 CSSStyleDeclaration 会去掉末尾 `.0`。
    expect(fillsIn(view, 0)).toEqual(['91%', '92.5%', '100%', '100%']);
    expect(fillsIn(view, 1)).toEqual(['100%', '5.3%', '8%']);
    expect(fillsIn(view, 2)).toEqual(['100%', '61.4%']);
    expect(view.text()).toContain('问答列基准 67，token 列基准 214480');
    expect(view.text()).toContain('条长只表示相对高低，不是百分比占比');
  });

  it('maps the status distribution through the contract enum without re-judging it', async () => {
    const view = await mountView();

    expect(view.findAll('.usage-table')[1]?.findAll('tbody tr').map((row) => row.findAll('td')[0]?.text())).toEqual([
      USAGE_STATUS_LABELS.SUCCESS,
      USAGE_STATUS_LABELS.BUDGET_EXHAUSTED,
      USAGE_STATUS_LABELS.FAILED,
    ]);
    expect(view.text()).not.toContain(USAGE_STATUS_LABELS.RUNNING);
  });

  it('sends a window only when the form supplies one and clears it back to the default', async () => {
    const sent = recordingUsage();
    const view = await mountView();
    const inputs = view.findAll('input[type="text"]');

    await inputs[0]?.setValue('2026-11-01T00:00:00Z');
    await inputs[1]?.setValue('2026-11-02T00:00:00Z');
    await byLabel(view, '查询')?.trigger('click');
    await vi.waitFor(() => expect(sent).toHaveLength(2));

    expect(sent[1]?.get('from')).toBe('2026-11-01T00:00:00Z');
    expect(sent[1]?.get('to')).toBe('2026-11-02T00:00:00Z');

    await byLabel(view, '清空窗口')?.trigger('click');
    await vi.waitFor(() => expect(sent).toHaveLength(3));
    expect(sent[2]?.has('from')).toBe(false);
    expect(sent[2]?.get('bucket')).toBe('day');
  });

  it('renders an empty window as no executions instead of an empty grid', async () => {
    const summary = usageSummaryData();
    recordingUsage({ ...summary, byDay: [], byStatus: [], topUsers: [] });
    const view = await mountView();

    expect(view.find('table').exists()).toBe(false);
    expect(view.text()).toContain(EMPTY_STATE_TEXT.usageNone);
    expect(view.text()).not.toContain('条长按本列最大值归一');
    // 汇总卡与序列是两件事：窗口内有总计、只是没有按日行，仍然要看得见总计。
    expect(stateOf(view).get('问答次数')).toBe('128');
  });
});
