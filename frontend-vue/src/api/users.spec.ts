import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { listUsers, replaceUserRoles, updateUserStatus } from './users';
import { ApiError } from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import {
  DEMO_DISABLED_USER_ID,
  DEMO_SECOND_ADMIN_ID,
  DEMO_TARGET_USER_ID,
  DEMO_TOKEN,
  DEMO_TRACE_ID,
  DEMO_USER_ID,
  callLog,
  userSummaryRow,
} from '../../tests/fixtures';
import type { UserListQuery } from './users';
import type { UpdateUserRolesRequest } from '../types/users';

/**
 * 幂等键由共享拦截器补发，两种 `newIdempotencyKey()` 分支（安全上下文的 UUID、
 * 局域网 IP 直访时的 32 位十六进制）都算合法形态，这里只认形态不认算法。
 */
const KEY_SHAPE = /^([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;

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
  return callLog.userOps.filter((entry) => entry.method !== 'GET');
}

describe('users api', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('serializes only the parameters the caller actually supplied', async () => {
    await listUsers({});
    await listUsers({ status: 'DISABLED', role: 'ADMIN', cursor: 'PAGE2', limit: 50 });

    // 未给的条件必须是「不出现」，不是 `status=undefined`：契约上这两个参数各自独立可选。
    expect(callLog.userOps.map((entry) => entry.search)).toEqual([
      '',
      '?status=DISABLED&role=ADMIN&cursor=PAGE2&limit=50',
    ]);
  });

  it('carries no idempotency key on the read because the contract declares none', async () => {
    await listUsers({ limit: 20 });

    expect(callLog.userOps.map((entry) => [entry.method, entry.key, entry.body])).toEqual([
      ['GET', null, null],
    ]);
  });

  it('unwraps the keyset page field by field and never invents a total', async () => {
    const page = await listUsers({ limit: 20 });

    expect(page).toEqual({
      items: [userSummaryRow(1), userSummaryRow(2)],
      nextCursor: 'PAGE2',
      limit: 20,
    });
    // 契约有意不返回 `total`（`UserListData.description` 原话），界面拿不到总数就不许编一个。
    expect(page).not.toHaveProperty('total');
  });

  it('replaces roles as a whole set with exactly the roles key', async () => {
    const updated = await replaceUserRoles(DEMO_TARGET_USER_ID, { roles: ['USER', 'ADMIN'] });

    expect(writes()[0]).toEqual({
      method: 'PUT',
      path: `/api/v1/users/${DEMO_TARGET_USER_ID}/roles`,
      search: '',
      key: expect.stringMatching(KEY_SHAPE),
      body: { roles: ['USER', 'ADMIN'] },
    });
    // 写响应回显的是合并后的整行，调用方因此不需要再读一次列表确认。
    expect(updated).toEqual({ ...userSummaryRow(2), roles: ['USER', 'ADMIN'] });
  });

  it('lets the status change ride a single-field body to a PATCH', async () => {
    const updated = await updateUserStatus(DEMO_DISABLED_USER_ID, { status: 'ACTIVE' });

    expect(writes()[0]).toEqual({
      method: 'PATCH',
      path: `/api/v1/users/${DEMO_DISABLED_USER_ID}/status`,
      search: '',
      key: expect.stringMatching(KEY_SHAPE),
      body: { status: 'ACTIVE' },
    });
    expect(updated).toEqual({ ...userSummaryRow(4), status: 'ACTIVE' });
  });

  it('refuses an invented second field in the roles body because the schema accepts one key', async () => {
    // `additionalProperties: false` 的反证：想在这里捎带别的信息就是 400，而不是被静默忽略。
    const invented = { roles: ['USER'], userId: DEMO_TARGET_USER_ID } as unknown as UpdateUserRolesRequest;

    const error = await expectApiError(replaceUserRoles(DEMO_TARGET_USER_ID, invented));

    expect(error.status).toBe(400);
    expect(error.code).toBe('COM-VALIDATION-001');
    expect(writes()[0]?.body).toEqual({ roles: ['USER'], userId: DEMO_TARGET_USER_ID });
  });

  it('encodes the target user id instead of pasting it into the path raw', async () => {
    const error = await expectApiError(updateUserStatus('user id/with space', { status: 'DISABLED' }));

    expect(writes()[0]?.path).toBe('/api/v1/users/user%20id%2Fwith%20space/status');
    // 编码后的路径仍然指向一个不存在的账号：机器可读码只有 `USR-NOT_FOUND-001` 这一个写法。
    expect(error.status).toBe(404);
    expect(error.code).toBe('USR-NOT_FOUND-001');
    expect(error.traceId).toBe(DEMO_TRACE_ID);
  });

  it('passes the self-lockout rejection through as a field message on the roles axis', async () => {
    const error = await expectApiError(replaceUserRoles(DEMO_USER_ID, { roles: ['USER'] }));

    expect(error.status).toBe(422);
    expect(error.code).toBe('USR-VALIDATION-001');
    expect(error.detail).toBe('不能移除当前调用者自身的 ADMIN 角色。');
    // message 是 Java 侧原文，前端不改写：界面要能证明这句话来自服务端。
    expect(error.fieldMessage('roles')).toBe('cannot remove ADMIN from the calling administrator');
  });

  it('passes the self-lockout rejection through as a field message on the status axis', async () => {
    const error = await expectApiError(updateUserStatus(DEMO_USER_ID, { status: 'DISABLED' }));

    expect(error.status).toBe(422);
    expect(error.fieldMessage('status')).toBe('cannot disable the calling administrator');
  });

  it('lets an empty role set reach the server because the schema accepts it', async () => {
    // 空数组通过格式校验、由服务端按策略 422：本层不替服务端做决定，也不在这里补一个默认角色。
    const error = await expectApiError(replaceUserRoles(DEMO_SECOND_ADMIN_ID, { roles: [] }));

    expect(writes()[0]?.body).toEqual({ roles: [] });
    expect(error.status).toBe(422);
    expect(error.fieldMessage('roles')).toBe('at least one role is required');
  });

  it('mints a fresh key per write rather than reusing one across distinct actions', async () => {
    await updateUserStatus(DEMO_TARGET_USER_ID, { status: 'DISABLED' });
    await updateUserStatus(DEMO_DISABLED_USER_ID, { status: 'ACTIVE' });

    // 契约在这两个写上确实声明了 `IdempotencyKey`，所以「每次点击一个新键」不是免费的：
    // 连点两次就是两次不同的动作。把这个键变成有意义的是 store 的写串行锁，不是本层。
    const keys = writes().map((entry) => entry.key);
    expect(keys).toEqual([expect.stringMatching(KEY_SHAPE), expect.stringMatching(KEY_SHAPE)]);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it('surfaces a rejected filter enum as the server-side 400 rather than a silent empty page', async () => {
    // 类型镜像挡不住的值（例如手工拼出来的查询）必须由服务端裁决，前端不把它咽成「这个条件没有结果」。
    const rogue = { status: 'PENDING', limit: 20 } as unknown as UserListQuery;

    const error = await expectApiError(listUsers(rogue));

    expect(error.status).toBe(400);
    expect(error.code).toBe('COM-VALIDATION-001');
    expect(error.detail).toBe('status 只接受 ACTIVE 或 DISABLED。');
  });
});
