import type { UserListData, UserSummary, UpdateUserRolesRequest, UpdateUserStatusRequest } from '../types/users';
import type { UserRole, UserStatus } from '../types/api';
import { get, patch, put } from './http';

/**
 * 用户管理面的唯一出口（契约 2.5.0 的 `User` tag，ADMIN 专用）。
 *
 * 与管理面 `api/admin.ts` 的**方向相反**（D-207）：角色替换与状态变更这两个写操作
 * 在契约里都正式声明了 `IdempotencyKey` 参数，Java 侧 `@RequestHeader` 为必填。
 * 本文件仍然**不构造任何请求头**——共享拦截器已覆盖「非 GET 且缺该头」并补一个随机键，
 * 401 刷新重放复用同一 `config.headers` 因而复用同一个键；在这里再拼一次就是第二套键生成逻辑。
 *
 * 第五个操作（按 id 读取单个用户）**刻意不实现**（D-214）：`UserSummaryData` 的字段集合
 * 与列表行完全同构，重读拿不到列表以外的信息，而随后的写操作自己就会回 404。
 * 路径上的身份参数是**被管理对象**的标识，不是调用者身份来源——身份只来自 JWT `sub`。
 */

export interface UserListQuery {
  /** 单值过滤（契约参数 `UserStatusFilter`，`name: status`）；undefined 表示不带该条件。 */
  status?: UserStatus;
  /** 单值过滤（契约参数 `UserRoleFilter`，`name: role`）。语义是「持有该角色即命中」，不是集合相等。 */
  role?: UserRole;
  /** 取自上一页的 `nextCursor`；不透明，客户端不得自行构造。 */
  cursor?: string;
  limit?: number;
}

/**
 * 按注册时间倒序的分页用户列表。契约有意不返回 `total`，调用方不得把已加载行数说成总数。
 */
export function listUsers(query: UserListQuery): Promise<UserListData> {
  return get<UserListData>('/users', {
    status: query.status,
    role: query.role,
    cursor: query.cursor,
    limit: query.limit,
  });
}

/**
 * 整集替换某用户的角色（`roles` 必须出现，空数组在 schema 层合法、由服务端按策略 422）。
 * 两条契约护栏：禁止摘掉调用者自己的 `ADMIN`、禁止任何账号落到零角色。
 * 同一幂等键换不同请求体返回 409 `USR-CONFLICT-001` 族。
 */
export function replaceUserRoles(userId: string, request: UpdateUserRolesRequest): Promise<UserSummary> {
  return put<UserSummary>(`/users/${encodeURIComponent(userId)}/roles`, request);
}

/**
 * 停用或启用账号。`DISABLED` 即时吊销该用户全部刷新令牌家族并推进用户级吊销水位，
 * 且**重新启用不复活已吊销凭据**——这是停用需要二次确认、启用不需要的唯一依据（D-209）。
 */
export function updateUserStatus(userId: string, request: UpdateUserStatusRequest): Promise<UserSummary> {
  return patch<UserSummary>(`/users/${encodeURIComponent(userId)}/status`, request);
}
