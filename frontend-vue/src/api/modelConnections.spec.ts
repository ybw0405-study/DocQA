import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import {
  authorizeModelConnection,
  deleteModelConnection,
  listModelConnectionPresets,
  listModelConnections,
  probeModelConnection,
  updateModelConnectionActivation,
  upsertModelConnection,
} from './modelConnections';
import { ApiError } from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import {
  DEMO_TOKEN,
  callLog,
  modelConnectionItem,
  modelConnectionsStubState,
} from '../../tests/fixtures';

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

describe('model connections api', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    modelConnectionsStubState.rows = [modelConnectionItem()];
  });

  it('reads the catalog and the connection list without any query parameters', async () => {
    const presets = await listModelConnectionPresets();
    const list = await listModelConnections();

    expect(presets.items.length).toBeGreaterThan(0);
    expect(list.items).toHaveLength(1);
    expect(callLog.adminOps).toEqual([
      { method: 'GET', path: '/api/v1/admin/ai/model-connections/presets', search: '', key: null, body: null },
      { method: 'GET', path: '/api/v1/admin/ai/model-connections', search: '', key: null, body: null },
    ]);
  });

  it('never exposes credential material on the read side', async () => {
    const list = await listModelConnections();

    expect(list.items[0].hasCredential).toBe(true);
    expect(JSON.stringify(list)).not.toContain('secret');
    expect(JSON.stringify(list)).not.toContain('credentialB64');
    expect(Object.keys(list.items[0])).not.toContain('credential');
  });

  it('upserts by name and keeps the activation flag untouched', async () => {
    modelConnectionsStubState.rows = [modelConnectionItem({ active: true })];

    const updated = await upsertModelConnection({
      name: 'DeepSeek 主连接',
      presetId: 'deepseek',
      baseUrl: 'https://api.deepseek.com/v1',
      model: 'deepseek-reasoner',
    });

    expect(updated.model).toBe('deepseek-reasoner');
    expect(updated.active).toBe(true);
    const write = callLog.adminOps.find((entry) => entry.method === 'POST');
    expect(write?.body).toEqual({
      name: 'DeepSeek 主连接',
      presetId: 'deepseek',
      baseUrl: 'https://api.deepseek.com/v1',
      model: 'deepseek-reasoner',
    });
    // 管理面不声明幂等键参数（D-197）：拦截器补的随机头不被服务端消费，但这里断言它确实存在且非空。
    expect(write?.key).toBeTruthy();
  });

  it('sends the secret only to the credential endpoint and never receives it back', async () => {
    const id = String(modelConnectionsStubState.rows[0].id);

    const result = await authorizeModelConnection(id, { secret: 'sk-unit-test-0123456789' });

    expect(result.hasCredential).toBe(true);
    expect(JSON.stringify(result)).not.toContain('sk-unit-test-0123456789');
    const write = callLog.adminOps.find((entry) => entry.method === 'PUT');
    expect(write?.path).toBe(`/api/v1/admin/ai/model-connections/${id}/credential`);
    expect(write?.body).toEqual({ secret: 'sk-unit-test-0123456789' });
  });

  it('treats a failed probe as a 200 conclusion rather than an HTTP error', async () => {
    modelConnectionsStubState.probeOk = false;
    modelConnectionsStubState.probeFailureReason = 'HTTP_4XX';
    const id = String(modelConnectionsStubState.rows[0].id);

    const probe = await probeModelConnection(id);

    expect(probe.ok).toBe(false);
    expect(probe.failureReason).toBe('HTTP_4XX');
    expect(probe.models).toEqual([]);
  });

  it('reports a missing credential as a probe conclusion, not a thrown error', async () => {
    modelConnectionsStubState.rows = [modelConnectionItem({ hasCredential: false })];
    const id = String(modelConnectionsStubState.rows[0].id);

    const probe = await probeModelConnection(id);

    expect(probe.ok).toBe(false);
    expect(probe.failureReason).toBe('CREDENTIAL_MISSING');
  });

  it('activates one connection and clears the others', async () => {
    modelConnectionsStubState.rows = [
      modelConnectionItem({ id: 'a', active: true }),
      modelConnectionItem({ id: 'b', name: '备用', active: false }),
    ];

    const result = await updateModelConnectionActivation('b', { active: true });

    expect(result.active).toBe(true);
    expect(modelConnectionsStubState.rows.filter((row) => row.active === true)).toHaveLength(1);
    const write = callLog.adminOps.find((entry) => entry.method === 'PUT');
    expect(write?.body).toEqual({ active: true });
  });

  it('deletes with no content and removes the row', async () => {
    const id = String(modelConnectionsStubState.rows[0].id);

    await deleteModelConnection(id);

    expect(modelConnectionsStubState.rows).toHaveLength(0);
    const write = callLog.adminOps.find((entry) => entry.method === 'DELETE');
    expect(write?.path).toBe(`/api/v1/admin/ai/model-connections/${id}`);
  });

  it('surfaces a 404 for an unknown connection as ApiError with code and trace', async () => {
    const error = await expectApiError(probeModelConnection('00000000-0000-0000-0000-0000000000ff'));

    expect(error.status).toBe(404);
    expect(error.code).toBe('COM-NOT_FOUND-001');
    expect(error.traceId).toBeTruthy();
  });

  it('surfaces a 400 when the activation flag is missing', async () => {
    const id = String(modelConnectionsStubState.rows[0].id);
    const error = await expectApiError(
      updateModelConnectionActivation(id, {} as { active: boolean }),
    );

    expect(error.status).toBe(400);
    expect(error.code).toBe('COM-VALIDATION-001');
  });
});
