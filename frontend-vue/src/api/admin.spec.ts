import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import {
  getAiControls,
  getAiUsage,
  getTenantAiControls,
  listAiQuotas,
  listAuditEvents,
  updateAiControls,
  updateAiQuota,
  updateTenantAiControls,
} from './admin';
import { ApiError } from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import {
  DEMO_TARGET_USER_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  DEMO_USER_ID,
  KILL_SWITCH_WRITE_AT,
  adminStubState,
  callLog,
  envelope,
  problem,
  usageSummaryData,
} from '../../tests/fixtures';
import { server } from '../../tests/server';
import type { UpdateAiControlsRequest, UpdateAiQuotaRequest } from '../types/admin';

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

/** 只取写操作记录：GET 不带货架，混在断言里只会稀释重点。 */
function writes() {
  return callLog.adminOps.filter((entry) => entry.method === 'PUT');
}

describe('admin api', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('reads the governance snapshot without a key and passes two nulls through', async () => {
    const data = await getAiControls();

    expect(callLog.adminOps).toEqual([
      {
        method: 'GET',
        path: '/api/v1/admin/ai/controls',
        search: '',
        key: null,
        body: null,
      },
    ]);
    // 从未写入过的开关没有元数据：两个 null 必须原样到达界面，而不是被补成当前时刻。
    expect(data).toEqual({
      killSwitch: { active: false, updatedAt: null, updatedBy: null },
      experimentBudget: {
        callsUsed: 22,
        tokensUsed: 2096,
        callLimit: 200,
        tokenLimit: 200000,
        limitsKnown: true,
      },
    });
  });

  it('leaves an unpublished ceiling unreadable instead of inventing one', async () => {
    adminStubState.limitsKnown = false;

    const data = await getAiControls();

    // 上限不可知 ≠ 上限为 0：api 层不填默认值，界面才能区分「未发布」和「已用尽」。
    expect(data.experimentBudget).toEqual({
      callsUsed: 22,
      tokensUsed: 2096,
      callLimit: null,
      tokenLimit: null,
      limitsKnown: false,
    });
  });

  it('sends only killSwitchActive and lets the next read carry the new state', async () => {
    await updateAiControls({ killSwitchActive: true });
    const after = await getAiControls();

    expect(writes()[0]?.body).toEqual({ killSwitchActive: true });
    expect(after.killSwitch.active).toBe(true);
    expect(after.killSwitch.updatedAt).toBe(KILL_SWITCH_WRITE_AT);
    expect(after.killSwitch.updatedBy).toBe(DEMO_USER_ID);
  });

  it('rejects an invented version field because the contract accepts no more than one key', async () => {
    // D-197 的反证：S9-2 遗留注释里的「version 乐观锁」在交付契约上不存在，多一个键就是 400。
    const invented = { killSwitchActive: true, version: 3 } as unknown as UpdateAiControlsRequest;

    const error = await expectApiError(updateAiControls(invented));

    expect(error.status).toBe(400);
    expect(error.code).toBe('COM-VALIDATION-001');
    expect(writes()[0]?.body).toEqual({ killSwitchActive: true, version: 3 });
  });

  describe('tenant-scoped kill switch (contract 2.6.0)', () => {
    it('reads the effective state from the tenant path without carrying a tenant parameter', async () => {
      const state = await getTenantAiControls();

      // tenantId 只由服务端取自 JWT：这里既没有查询串也没有请求体，界面无法指定别的租户。
      expect(callLog.adminOps).toEqual([
        {
          method: 'GET',
          path: '/api/v1/admin/ai/controls/tenant',
          search: '',
          key: null,
          body: null,
        },
      ]);
      expect(state).toEqual({ active: false, updatedAt: null, updatedBy: null });
    });

    it('writes only killSwitchActive and adopts the echoed effective state', async () => {
      const state = await updateTenantAiControls({ killSwitchActive: true });

      expect(writes()[0]?.path).toBe('/api/v1/admin/ai/controls/tenant');
      expect(writes()[0]?.body).toEqual({ killSwitchActive: true });
      expect(state).toEqual({
        active: true,
        updatedAt: KILL_SWITCH_WRITE_AT,
        updatedBy: DEMO_USER_ID,
      });
      expect(adminStubState.tenantKillSwitch.active).toBe(true);
      // 平台级一键全停键完全不受这次写入影响。
      expect(adminStubState.killSwitch.active).toBe(false);
    });

    it('keeps active=true after turning the tenant key off while the platform switch is on', async () => {
      adminStubState.killSwitch = { active: true, updatedAt: KILL_SWITCH_WRITE_AT, updatedBy: DEMO_USER_ID };

      const echoed = await updateTenantAiControls({ killSwitchActive: false });

      // OR 叠加：本租户键确实关了，但回显的有效状态仍是 true，界面不能把它写成「已恢复服务」。
      expect(adminStubState.tenantKillSwitch.active).toBe(false);
      expect(echoed.active).toBe(true);
      const reread = await getTenantAiControls();
      expect(reread.active).toBe(true);
    });

    it('rejects an invented version field on the tenant path too', async () => {
      const invented = { killSwitchActive: false, version: 1 } as unknown as UpdateAiControlsRequest;

      const error = await expectApiError(updateTenantAiControls(invented));

      expect(error.status).toBe(400);
      expect(error.code).toBe('COM-VALIDATION-001');
    });

    it('surfaces the audit-failure 500 while the tenant key has already flipped', async () => {
      adminStubState.tenantControlsAuditFails = true;

      const error = await expectApiError(updateTenantAiControls({ killSwitchActive: true }));

      expect(error.status).toBe(500);
      expect(error.code).toBe('COM-INTERNAL-001');
      expect(adminStubState.tenantKillSwitch.active).toBe(true);
    });
  });

  it('mints a fresh inert key per write and consumes none of them', async () => {
    await updateAiControls({ killSwitchActive: true });
    await updateAiControls({ killSwitchActive: true });

    // 契约上这两个 PUT 都不声明 IdempotencyKey：拦截器补的随机键只是全站统一形态，
    // 每请求一个新值、服务端不读它，所以重复提交同一值仍然成立（「按值幂等」的根据）。
    const keys = writes().map((entry) => entry.key);
    expect(keys).toEqual([expect.any(String), expect.any(String)]);
    expect(keys[0]).not.toBe(keys[1]);
    expect(writes().map((entry) => entry.body)).toEqual([
      { killSwitchActive: true },
      { killSwitchActive: true },
    ]);
    expect(adminStubState.killSwitch.active).toBe(true);
  });

  it('reports the switch already flipped when the audit write behind it fails', async () => {
    adminStubState.controlsAuditFails = true;

    const error = await expectApiError(updateAiControls({ killSwitchActive: true }));

    // S9-5b 的真实形态：先写 Redis 再插审计，500 不等于回滚，调用方只能重读。
    expect(error.status).toBe(500);
    expect(error.code).toBe('COM-INTERNAL-001');
    expect(adminStubState.killSwitch.active).toBe(true);
  });

  it('lists quotas with only the keyset parameters and no total', async () => {
    await listAiQuotas({ limit: 50 });
    await listAiQuotas({ cursor: 'PAGE2', limit: 50 });

    expect(callLog.adminOps.map((entry) => entry.search)).toEqual(['?limit=50', '?cursor=PAGE2&limit=50']);

    const page = await listAiQuotas({ limit: 50 });
    expect(page.items.map((item) => item.userId)).toEqual([
      DEMO_TARGET_USER_ID,
      '0192f3a4-9d4e-7f32-8a01-1234567890a2',
    ]);
    // 契约有意不返回 total：任何「共 N 条」的字段在这里都不存在。
    expect(page).not.toHaveProperty('total');
    expect(page.nextCursor).toBe('PAGE2');
  });

  it('replaces a quota with both fields present and clears it with two nulls', async () => {
    const set = await updateAiQuota(DEMO_TARGET_USER_ID, { dailyQuestionLimit: 55, dailyTokenLimit: 60000 });

    expect(writes()[0]?.path).toBe(`/api/v1/admin/ai/quotas/${DEMO_TARGET_USER_ID}`);
    expect(writes()[0]?.body).toEqual({ dailyQuestionLimit: 55, dailyTokenLimit: 60000 });
    expect(set.dailyQuestionLimit).toBe(55);

    const cleared = await updateAiQuota(DEMO_TARGET_USER_ID, { dailyQuestionLimit: null, dailyTokenLimit: null });
    expect(writes()[1]?.body).toEqual({ dailyQuestionLimit: null, dailyTokenLimit: null });
    // null 是「该维度不设个性化上限」的显式表达，序列化时键必须仍在。
    expect(cleared).toEqual({
      userId: DEMO_TARGET_USER_ID,
      username: 'zhang.wei',
      dailyQuestionLimit: null,
      dailyTokenLimit: null,
      updatedAt: KILL_SWITCH_WRITE_AT,
      updatedBy: DEMO_USER_ID,
    });
  });

  it('encodes a target user id rather than pasting it into the path raw', async () => {
    await updateAiQuota('0192/user with space', { dailyQuestionLimit: 1, dailyTokenLimit: null });

    expect(writes()[0]?.path).toBe('/api/v1/admin/ai/quotas/0192%2Fuser%20with%20space');
  });

  it('treats an omitted quota field as a rejection rather than a keep', async () => {
    const partial = { dailyQuestionLimit: 30 } as unknown as UpdateAiQuotaRequest;

    const error = await expectApiError(updateAiQuota(DEMO_TARGET_USER_ID, partial));

    // 契约上没有「省略即保持原值」：全量替换语义下少一个键就是 400。
    expect(error.status).toBe(400);
    expect(error.code).toBe('COM-VALIDATION-001');
  });

  it('surfaces the 404 for a target user that does not exist', async () => {
    adminStubState.missingQuotaUsers.add('missing-user');

    const error = await expectApiError(
      updateAiQuota('missing-user', { dailyQuestionLimit: 10, dailyTokenLimit: null }),
    );

    // 机器可读的码只有一个写法：`USR-NOT_FOUND-001`，与契约文首描述里那处连字符排版无关。
    expect(error.status).toBe(404);
    expect(error.code).toBe('USR-NOT_FOUND-001');
    expect(error.traceId).toBe(DEMO_TRACE_ID);
  });

  it('sends audit filters only when the caller supplied one', async () => {
    await listAuditEvents({ limit: 100 });
    await listAuditEvents({ traceId: DEMO_TRACE_ID, limit: 100 });
    await listAuditEvents({
      userId: DEMO_TARGET_USER_ID,
      eventType: 'RAG_RETRIEVED',
      from: '2026-11-02T09:00:00Z',
      to: '2026-11-03T09:00:00Z',
      cursor: 'PAGE2',
      limit: 200,
    });

    const searches = callLog.adminOps.map((entry) => new URLSearchParams(entry.search));
    // 空条件不能变成 `traceId=`：契约的 pattern 对空串同样校验，发出去只会换来 400。
    expect(searches.map((params) => [...params.keys()])).toEqual([
      ['limit'],
      ['traceId', 'limit'],
      ['userId', 'eventType', 'from', 'to', 'cursor', 'limit'],
    ]);
    expect(searches[2]?.get('from')).toBe('2026-11-02T09:00:00Z');
    expect(searches[2]?.get('to')).toBe('2026-11-03T09:00:00Z');
    expect(callLog.adminOps.every((entry) => entry.key === null)).toBe(true);
  });

  it('hands back a malformed trace id as the server rejects it', async () => {
    const error = await expectApiError(listAuditEvents({ traceId: 'NOT-A-TRACE-ID', limit: 100 }));

    expect(error.status).toBe(400);
    expect(error.code).toBe('COM-VALIDATION-001');
  });

  it('keeps a null average latency null when the window had no execution', async () => {
    server.use(
      mswHttp.get('/api/v1/admin/ai/usage', () =>
        HttpResponse.json(
          envelope(usageSummaryData({ totals: { ...usageSummaryData().totals, avgLatencyMs: null } })),
        ),
      ),
    );

    const summary = await getAiUsage({ from: '2026-11-02T09:00:00Z', to: '2026-11-03T09:00:00Z' });

    // 均值为 null 时不能渲染成 0 ms：那是「没有执行记录」，不是「极快」。
    expect(summary.totals.avgLatencyMs).toBeNull();
    expect(summary.byStatus).toHaveLength(3);
  });

  it('omits bucket and topUsers when the caller leaves them to the contract defaults', async () => {
    await getAiUsage({});
    await getAiUsage({ bucket: 'day', topUsers: 50 });

    expect(callLog.adminOps.map((entry) => entry.search)).toEqual(['', '?bucket=day&topUsers=50']);
  });

  it('keeps the admin rejection readable as a forbidden code with its trace', async () => {
    server.use(
      mswHttp.get('/api/v1/admin/ai/controls', () =>
        HttpResponse.json(
          problem({
            status: 403,
            code: 'USR-FORBIDDEN-001',
            detail: '当前账号没有执行该操作的权限。',
            instance: '/api/v1/admin/ai/controls',
          }),
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await expectApiError(getAiControls());

    expect(error.status).toBe(403);
    expect(error.code).toBe('USR-FORBIDDEN-001');
    expect(error.traceId).toBe(DEMO_TRACE_ID);
  });
});
