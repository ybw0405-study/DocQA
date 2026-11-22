import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import { useAdminModelConnectionsStore } from './adminModelConnections';
import { bindSessionToAuthStore } from './sessionBridge';
import { useAuthStore } from './auth';
import {
  DEMO_TOKEN,
  callLog,
  modelConnectionItem,
  modelConnectionsStubState,
} from '../../tests/fixtures';

const SECRET = 'sk-store-test-0123456789';

function connectInput(overrides: Record<string, unknown> = {}) {
  return {
    name: 'DeepSeek 主连接',
    presetId: 'deepseek',
    baseUrl: 'https://api.deepseek.com/v1',
    model: 'deepseek-chat',
    secret: SECRET,
    noKeyAllowed: false,
    allowWithoutProbe: false,
    ...overrides,
  };
}

function methods() {
  return callLog.adminOps.map((entry) => `${entry.method} ${entry.path}`);
}

describe('adminModelConnections store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    modelConnectionsStubState.rows = [];
  });

  it('keeps items null until the server has been read', () => {
    const store = useAdminModelConnectionsStore();

    expect(store.items).toBeNull();
    expect(store.count).toBeNull();
    expect(store.activeConnection).toBeNull();
  });

  it('runs create → credential → probe → activation in that order', async () => {
    const store = useAdminModelConnectionsStore();

    const result = await store.connect(connectInput());

    expect(result.ok).toBe(true);
    expect(methods()).toEqual([
      'POST /api/v1/admin/ai/model-connections',
      'PUT /api/v1/admin/ai/model-connections/0192f3a4-9d4e-7f32-8a01-1234567890c1/credential',
      'POST /api/v1/admin/ai/model-connections/0192f3a4-9d4e-7f32-8a01-1234567890c1/probe',
      'PUT /api/v1/admin/ai/model-connections/0192f3a4-9d4e-7f32-8a01-1234567890c1/activation',
      'GET /api/v1/admin/ai/model-connections',
    ]);
    expect(store.items).toHaveLength(1);
    expect(store.activeConnection?.model).toBe('deepseek-chat');
  });

  it('never keeps the plaintext secret in state or in the notice', async () => {
    const store = useAdminModelConnectionsStore();

    await store.connect(connectInput());

    expect(JSON.stringify(store.$state)).not.toContain(SECRET);
    expect(store.notice ?? '').not.toContain(SECRET);
  });

  it('rolls back a newly created connection when the probe fails', async () => {
    modelConnectionsStubState.probeOk = false;
    const store = useAdminModelConnectionsStore();

    const result = await store.connect(connectInput());

    expect(result.ok).toBe(false);
    expect(result.message).toContain('Base URL');
    expect(modelConnectionsStubState.rows).toHaveLength(0);
    expect(methods()).toContain('DELETE /api/v1/admin/ai/model-connections/0192f3a4-9d4e-7f32-8a01-1234567890c1');
  });

  it('keeps the connection with a warning when the user allows saving without a probe', async () => {
    modelConnectionsStubState.probeOk = false;
    const store = useAdminModelConnectionsStore();

    const result = await store.connect(connectInput({ allowWithoutProbe: true }));

    expect(result.ok).toBe(true);
    expect(result.warning).toBeTruthy();
    expect(modelConnectionsStubState.rows).toHaveLength(1);
    expect(store.activeConnection).not.toBeNull();
  });

  /**
   * 创建是按 name 的 upsert：同名命中既有行时，失败回滚**不得**删掉用户原有的连接。
   * 这是与 AgentFlow「create 总是新行」语义的关键差别。
   */
  it('does not delete a pre-existing connection when an upsert hit fails later', async () => {
    modelConnectionsStubState.rows = [modelConnectionItem({ id: 'existing-id', active: true })];
    modelConnectionsStubState.probeOk = false;
    const store = useAdminModelConnectionsStore();
    await store.fetch();

    const result = await store.connect(connectInput());

    expect(result.ok).toBe(false);
    expect(modelConnectionsStubState.rows).toHaveLength(1);
    expect(modelConnectionsStubState.rows[0].id).toBe('existing-id');
    expect(methods().some((entry) => entry.startsWith('DELETE'))).toBe(false);
  });

  it('skips the probe for keyless endpoints and still activates them', async () => {
    const store = useAdminModelConnectionsStore();

    const result = await store.connect(connectInput({ secret: '', noKeyAllowed: true }));

    expect(result.ok).toBe(true);
    expect(result.warning).toContain('跳过自动探测');
    expect(methods().some((entry) => entry.endsWith('/probe'))).toBe(false);
    expect(store.activeConnection).not.toBeNull();
  });

  it('activating a second connection leaves exactly one active', async () => {
    modelConnectionsStubState.rows = [
      modelConnectionItem({ id: 'a', active: true }),
      modelConnectionItem({ id: 'b', name: '备用', active: false }),
    ];
    const store = useAdminModelConnectionsStore();
    await store.fetch();

    const succeeded = await store.setActive('b', true);

    expect(succeeded).toBe(true);
    expect(store.items?.filter((item) => item.active)).toHaveLength(1);
    expect(store.activeConnection?.id).toBe('b');
    expect(store.notice).toContain('无需重启');
  });

  it('refuses to activate a disabled connection and reports the server reason', async () => {
    modelConnectionsStubState.rows = [modelConnectionItem({ id: 'a', enabled: false })];
    const store = useAdminModelConnectionsStore();
    await store.fetch();

    const succeeded = await store.setActive('a', true);

    expect(succeeded).toBe(false);
    expect(store.code).toBe('COM-VALIDATION-001');
  });

  it('deleting the active connection clears it and says the credential is gone', async () => {
    modelConnectionsStubState.rows = [modelConnectionItem({ id: 'a', active: true })];
    const store = useAdminModelConnectionsStore();
    await store.fetch();

    const succeeded = await store.remove('a');

    expect(succeeded).toBe(true);
    expect(store.items).toHaveLength(0);
    expect(store.activeConnection).toBeNull();
    expect(store.notice).toContain('密文');
  });

  it('loads the server-side catalog and never falls back to a local one', async () => {
    const store = useAdminModelConnectionsStore();

    await store.loadPresets();

    expect(store.presets?.length).toBeGreaterThan(0);
    expect(store.presets?.[0].baseUrl).toContain('deepseek');
  });

  it('reports a catalog failure instead of inventing presets', async () => {
    modelConnectionsStubState.presetsFail = true;
    const store = useAdminModelConnectionsStore();

    await store.loadPresets();

    expect(store.presets).toBeNull();
    expect(store.detail).toBe('预设目录暂不可用。');
    expect(store.code).toBe('COM-INTERNAL-001');
  });

  it('serializes writes so two submits cannot race', async () => {
    const store = useAdminModelConnectionsStore();

    const [first, second] = await Promise.all([
      store.connect(connectInput()),
      store.connect(connectInput({ name: '另一条' })),
    ]);

    expect([first.ok, second.ok].filter(Boolean)).toHaveLength(1);
    expect(store.submitting).toBe(false);
  });
});
