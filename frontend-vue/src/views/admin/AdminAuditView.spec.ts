import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { HttpResponse, http as mswHttp } from 'msw';
import AdminAuditView from './AdminAuditView.vue';
import { bindSessionToAuthStore } from '../../stores/sessionBridge';
import { useAdminAuditStore } from '../../stores/adminAudit';
import { useAuthStore } from '../../stores/auth';
import {
  DEMO_TARGET_USER_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  adminStubState,
  auditEventItem,
  callLog,
  envelope,
} from '../../../tests/fixtures';
import { server } from '../../../tests/server';
import { AUDIT_PAYLOAD_LABELS, AUDIT_SEVERITY_LABELS, EMPTY_STATE_TEXT, formatDateTime } from '../../utils/display';

let mounted: ViewWrapper | null = null;

async function mountView() {
  const wrapper = mount(AdminAuditView);
  mounted = wrapper;
  await vi.waitFor(() => expect(useAdminAuditStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

/** 固定返回单页末页的桩，用来把任意形态的载荷摆到展开行里。 */
function stubRows(items: unknown[]): void {
  server.use(
    mswHttp.get('/api/v1/admin/audit/events', () =>
      HttpResponse.json(envelope({ items, nextCursor: null, limit: 100 })),
    ),
  );
}

function rowActions(view: ViewWrapper, index = 0) {
  const cells = view.findAll('tbody tr')[index]?.findAll('td') ?? [];
  return cells[cells.length - 1]?.findAll('button') ?? [];
}

function byLabel(view: ViewWrapper, label: string) {
  return view.findAll('button').find((button) => button.text() === label);
}

function methods(): string[] {
  // 挂载时还会读一次可观测性入口（部署配置）；这里只计审计查询本身的请求。
  return callLog.adminOps
    .filter((entry) => entry.path === '/api/v1/admin/audit/events')
    .map((entry) => entry.method);
}

/** 展开行的载荷表：dt 是展示标签（未知键带原文键名与标注），dd 是转义后的值。 */
function stateOf(view: ViewWrapper): Map<string, string> {
  const entries = new Map<string, string>();
  for (const row of view.findAll('.admin-state > div')) {
    entries.set((row.find('dt')?.text() ?? '').trim(), (row.find('dd')?.text() ?? '').trim());
  }
  return entries;
}

async function expandFirstPayload(view: ViewWrapper): Promise<void> {
  await rowActions(view, 0).find((button) => button.text() === '载荷')?.trigger('click');
  await vi.waitFor(() => expect(view.find('.admin-state').exists()).toBe(true));
}

describe('AdminAuditView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  afterEach(async () => {
    await vi.waitFor(() => {
      const store = useAdminAuditStore();
      expect(store.loading || store.submitting).toBe(false);
    });
    mounted?.unmount();
    mounted = null;
  });

  it('keeps the returned page order instead of re-sorting it in the browser', async () => {
    // 倒序由服务端的 `occurred_at DESC, id DESC` 决定；桩数据第一页是 item 1（10:00:01）在前、
    // item 2（10:15:42）在后，故意不是时间序，用来证明前端没有重新排序（D-201 边界：不做再计算）。
    const view = await mountView();
    const rows = view.findAll('tbody tr');

    expect(rows).toHaveLength(2);
    const cells = rows[0]?.findAll('td') ?? [];
    expect(cells[0]?.text()).toBe(formatDateTime(String(auditEventItem(1).occurredAt)));
    expect(cells[1]?.text()).toContain('RAG_RETRIEVED');
    expect(cells[1]?.text()).toContain('rag_retriever');
    expect(cells[2]?.text()).toBe('PYTHON');
    expect(cells[3]?.text()).toBe(AUDIT_SEVERITY_LABELS.INFO);
    expect(cells[4]?.text()).toBe(DEMO_TRACE_ID);
    expect(cells[5]?.text()).toBe(DEMO_TARGET_USER_ID);
    expect(view.find('thead').text()).toContain('写入端');
  });

  it('tones the severity chip from the enum instead of re-judging it', async () => {
    stubRows([
      { ...auditEventItem(1), severity: 'ERROR' },
      { ...auditEventItem(2), severity: 'WARN' },
      { ...auditEventItem(3), severity: 'INFO', agentName: null },
    ]);
    const view = await mountView();

    expect(
      view.findAll('tbody tr').map((row) => [
        (row.findAll('td')[3]?.text() ?? ''),
        row.find('.governance__chip--warn').exists(),
      ]),
    ).toEqual([
      [AUDIT_SEVERITY_LABELS.ERROR, true],
      [AUDIT_SEVERITY_LABELS.WARN, true],
      [AUDIT_SEVERITY_LABELS.INFO, false],
    ]);
    // `agentName` 可空：这里要说「没有执行体」，不能说成一次失败。
    expect(view.findAll('tbody tr')[2]?.text()).toContain('无执行体');
  });

  it('renders every whitelisted payload key under its registered label', async () => {
    // 17 键的三侧事实源：Java `AuditPayloadSanitizer.ALLOWED_KEYS`、Python `governance/audit.ALLOWED_PAYLOAD_KEYS`，
    // 以及 Java 侧的 `AuditPayloadKeySetContractTest`。前两侧的逐键相同由它们自己的测试守，
    // 这里守第三侧：前端展示表既不漏键，也不自造键。
    expect(Object.keys(AUDIT_PAYLOAD_LABELS)).toHaveLength(17);

    const payload: Record<string, unknown> = {};
    for (const key of Object.keys(AUDIT_PAYLOAD_LABELS)) {
      payload[key] = `V-${key}`;
    }
    stubRows([{ ...auditEventItem(1), payload }]);
    const view = await mountView();

    await expandFirstPayload(view);
    const state = stateOf(view);

    expect([...state.keys()].sort()).toEqual([...Object.values(AUDIT_PAYLOAD_LABELS)].sort());
    expect([...state.values()].sort()).toEqual(Object.keys(payload).map((key) => `V-${key}`).sort());
    expect(view.text()).not.toContain('未登记键名');
  });

  it('keeps an unregistered payload key visible under its raw key name', async () => {
    // 白名单只决定「有没有中文名」：拿它当校验就会把服务端将来新增的治理字段静默吞掉。
    stubRows([{ ...auditEventItem(1), payload: { rerankModelVersion: 'r2', chunkCount: 6 } }]);
    const view = await mountView();

    await expandFirstPayload(view);
    const state = stateOf(view);

    expect(state.get('rerankModelVersion （未登记键名）')).toBe('r2');
    expect(state.get(AUDIT_PAYLOAD_LABELS.chunkCount)).toBe('6');
  });

  it('renders an untrusted payload value as escaped text, never as markup', async () => {
    stubRows([{ ...auditEventItem(1), payload: { errorCode: '<img src=x onerror=alert(1)>' } }]);
    const view = await mountView();

    await expandFirstPayload(view);

    expect(stateOf(view).get(AUDIT_PAYLOAD_LABELS.errorCode)).toBe('<img src=x onerror=alert(1)>');
    expect(view.find('img').exists()).toBe(false);
    expect(view.find('.admin-raw__body').text()).toBe('{"errorCode":"<img src=x onerror=alert(1)>"}');
  });

  it('reads the payload value shapes through text interpolation', async () => {
    stubRows([
      {
        ...auditEventItem(1),
        payload: { cacheHit: true, historyTruncated: false, piiCategories: ['EMAIL', 'PHONE'], maskingVersion: null, piiCount: 2 },
      },
    ]);
    const view = await mountView();

    await expandFirstPayload(view);
    const state = stateOf(view);

    expect(state.get(AUDIT_PAYLOAD_LABELS.cacheHit)).toBe('是');
    expect(state.get(AUDIT_PAYLOAD_LABELS.historyTruncated)).toBe('否');
    expect(state.get(AUDIT_PAYLOAD_LABELS.piiCategories)).toBe('EMAIL、PHONE');
    expect(state.get(AUDIT_PAYLOAD_LABELS.maskingVersion)).toBe('无值');
    expect(state.get(AUDIT_PAYLOAD_LABELS.piiCount)).toBe('2');
  });

  it('says so when an event carries no payload', async () => {
    stubRows([{ ...auditEventItem(1), payload: {} }]);
    const view = await mountView();

    await rowActions(view, 0).find((button) => button.text() === '载荷')?.trigger('click');
    await vi.waitFor(() => expect(view.find('.admin-raw').exists()).toBe(true));

    expect(view.find('.admin-state').exists()).toBe(false);
    expect(view.text()).toContain('这条事件没有载荷。');
    expect(view.find('.admin-raw__body').text()).toBe('{}');
  });

  it('sends only the conditions that have a value and reads an empty result as filtered', async () => {
    const sent: URLSearchParams[] = [];
    server.use(
      mswHttp.get('/api/v1/admin/audit/events', ({ request }) => {
        sent.push(new URL(request.url).searchParams);
        return HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 100 }));
      }),
    );
    const view = await mountView();

    await view.findAll('input[type="text"]')[0]?.setValue(DEMO_TRACE_ID);
    await byLabel(view, '查询')?.trigger('click');
    await vi.waitFor(() => expect(sent).toHaveLength(2));

    expect(sent[0]?.get('limit')).toBe('100');
    expect(sent[0]?.has('traceId')).toBe(false);
    expect(sent[1]?.get('traceId')).toBe(DEMO_TRACE_ID);
    expect(sent[1]?.has('userId')).toBe(false);
    expect(sent[1]?.has('eventType')).toBe(false);
    expect(sent[1]?.has('from')).toBe(false);
    expect(sent[1]?.has('to')).toBe(false);
    // 「筛后为空」与「窗口内没有」是两种行动指引，不能合并成一句暂无数据。
    expect(view.text()).toContain(EMPTY_STATE_TEXT.auditFiltered);
  });

  it('renders the trace id as an external LangFuse link when a base url is configured', async () => {
    adminStubState.langfuseBaseUrl = 'http://127.0.0.1:13000';
    const view = await mountView();
    await vi.waitFor(() => expect(useAdminAuditStore().langfuseBaseUrl).not.toBe(''));

    const cell = view.findAll('tbody tr')[0]?.findAll('td')[4];
    const link = cell?.find('a');
    expect(link?.exists()).toBe(true);
    // href 由服务端下发的基地址运行时拼接，产物里不烘焙任何绝对地址。
    expect(link?.attributes('href')).toBe(`http://127.0.0.1:13000/trace/${DEMO_TRACE_ID}`);
    expect(link?.attributes('target')).toBe('_blank');
    expect(link?.attributes('rel')).toBe('noopener noreferrer');
    expect(link?.text()).toBe(DEMO_TRACE_ID);
  });

  it('keeps the trace id plain text when LangFuse is not deployed', async () => {
    const view = await mountView();
    await vi.waitFor(() => expect(useAdminAuditStore().loaded).toBe(true));

    const cell = view.findAll('tbody tr')[0]?.findAll('td')[4];
    expect(cell?.find('a').exists()).toBe(false);
    expect(cell?.text()).toBe(DEMO_TRACE_ID);
  });

  it('refuses a malformed trace id locally without spending a request', async () => {
    const view = await mountView();

    await view.findAll('input[type="text"]')[0]?.setValue('not-a-trace-id');
    await byLabel(view, '查询')?.trigger('click');
    await vi.waitFor(() => expect(useAdminAuditStore().detail).toContain('32 位小写十六进制'));

    expect(methods()).toEqual(['GET']);
    expect(view.find('[role="alert"]').text()).toContain('traceId 必须是 32 位小写十六进制。');
  });
});
