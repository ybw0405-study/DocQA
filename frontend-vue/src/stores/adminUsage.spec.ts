import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useAdminUsageStore } from './adminUsage';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import { DEMO_TOKEN, callLog, envelope, usageSummaryData } from '../../tests/fixtures';
import { server } from '../../tests/server';

function lastSearch(): string {
  return callLog.adminOps.at(-1)?.search ?? '';
}

describe('adminUsage store', () => {
  let store: ReturnType<typeof useAdminUsageStore>;

  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    store = useAdminUsageStore();
  });

  it('asks for the only published bucket and the contract default top users', async () => {
    await store.load();

    // 粒度与榜单长度都显式发送：两侧各留一个默认值，早晚会漂到不同的地方。
    expect(lastSearch()).toBe('?bucket=day&topUsers=10');
    expect(store.summary?.totals.questions).toBe(128);
    expect(store.loaded).toBe(true);
  });

  it('sends a window only once the form supplies one', async () => {
    const pending = store.applyWindow({ from: '2026-11-02T09:00:00Z', to: '2026-11-03T09:00:00Z' });

    expect(store.submitting).toBe(true);
    await pending;
    expect(store.submitting).toBe(false);

    const params = new URLSearchParams(lastSearch());
    expect([...params.keys()]).toEqual(['from', 'to', 'bucket', 'topUsers']);
    expect(params.get('from')).toBe('2026-11-02T09:00:00Z');
    expect(store.from).toBe('2026-11-02T09:00:00Z');
  });

  it('normalises a blank window back to the server default', async () => {
    await store.applyWindow({ from: ' ', to: '' });

    expect(lastSearch()).toBe('?bucket=day&topUsers=10');
    expect(store.from).toBeNull();
    expect(store.to).toBeNull();
  });

  it('keeps budget exhaustion counted apart from failures', async () => {
    await store.load();
    const totals = store.summary?.totals;

    // 契约把 BUDGET_EXHAUSTED 单列一档：并入 failed 会把「额度用尽」说成「系统出错」。
    expect(totals?.budgetExhausted).toBe(6);
    expect(totals?.failed).toBe(9);
    expect(store.summary?.byStatus.map((row) => row.status)).toEqual([
      'SUCCESS',
      'BUDGET_EXHAUSTED',
      'FAILED',
    ]);
  });

  it('leaves an absent average latency absent', async () => {
    server.use(
      mswHttp.get('/api/v1/admin/ai/usage', () =>
        HttpResponse.json(envelope(usageSummaryData({ totals: { ...usageSummaryData().totals, avgLatencyMs: null } }))),
      ),
    );

    await store.load();

    // 窗口内没有执行记录时均值就是 null；填成 0 会被读成「快到可以忽略」。
    expect(store.summary?.totals.avgLatencyMs).toBeNull();
  });

  it('leaves a too-wide window to the server instead of guessing the limit locally', async () => {
    await store.applyWindow({ from: '2026-01-01T00:00:00Z', to: '2026-02-10T00:00:00Z' });

    expect(store.detail).toBe('时间窗跨度超过 31 天。');
    expect(store.code).toBe('COM-VALIDATION-001');
    expect(store.summary).toBeNull();
  });

  it('does not stack a second read while one is in flight', async () => {
    const first = store.load();
    await store.load();
    await first;

    expect(callLog.adminOps).toHaveLength(1);
  });
});
