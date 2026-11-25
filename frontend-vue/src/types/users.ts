/**
 * 契约 `openapi-java.yaml` 2.5.0 用户管理面 schema 的逐字段镜像，字段名不做本地化改名。
 * 镜像与契约是否仍然一致由 `tests/contract-drift.spec.ts` 当场对账，不靠本注释。
 *
 * `UserRole` / `UserStatus` 两个枚举与 `PASSWORD_PATTERN`、`REGISTER_USERNAME_*` 已经活在
 * `src/types/api.ts`（交付 `auth/me` 时建立），这里**只引用不重复定义**——
 * 同一个枚举在两处各写一遍就会失去对账意义。
 *
 * 两条贯穿本片的口径：
 * 1. 用户列表是 **keyset**（`limit` + `cursor` → `{items, nextCursor, limit}`），契约有意不返回 `total`
 * （`UserListData.description` 原文），界面不得显示「共 N 人」，也不得用已加载行数冒充总数。
 * 2. 口令字段在契约上是 `writeOnly`：只出现在请求体，任何响应、任何日志、任何浏览器存储都不该有它。
 */

import type { UserRole, UserStatus } from './api';

/** 契约 `UserListData.items` 的 `maxItems`，与查询参数 `PageLimit` 的 `maximum` 同值。 */
export const USER_LIST_LIMIT_MAX = 100;

/**
 * 契约 `ChangePasswordRequest.currentPassword`：`minLength: 1`、`maxLength: 72`、**没有 pattern**。
 * 缺或错都统一返回 401 `USR-AUTH-001` 并计入登录失败限流，所以客户端不做强度预检。
 */
export const CURRENT_PASSWORD_MIN_LENGTH = 1;

export const CURRENT_PASSWORD_MAX_LENGTH = 72;

/**
 * 修改口令的请求体（契约 `ChangePasswordRequest`，`additionalProperties: false`）。
 * `newPassword` 复用注册策略 `PasswordPolicy`，因此模式与长度界都指向 `types/api.ts` 的同一对常量。
 */
export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/**
 * 管理员视角的一行用户（契约 `UserSummaryData`）。刻意**不含** `password_hash`、刷新令牌摘要、
 * Redis 键等任何凭据材料——契约原话是「管理员需要的是定位账号，不是接管账号」。
 * `lastLoginAt` 是契约里显式 nullable 的字段：从未登录过为 `null`，不是「时间未知可以留空」。
 */
export interface UserSummary {
  userId: string;
  username: string;
  status: UserStatus;
  roles: UserRole[];
  createdAt: string;
  lastLoginAt: string | null;
}

/**
 * 契约 `UserRolesUpdateRequest`（`additionalProperties: false`）是**整集替换**。
 * `roles` 在 schema 层**没有** `minItems`：空数组通过格式校验，由服务端按策略返回 422
 * `USR-VALIDATION-001`（账号必须至少持有 `USER`）。界面因此把「清空角色」挡住，
 * 但挡住的表述只能是「服务端会拒绝」，不能写成「前端已防止」。
 * 另一条自锁护栏同属契约明文：禁止摘掉调用者自己的 `ADMIN`（否则管理面会自我关闭）。
 */
export interface UpdateUserRolesRequest {
  roles: UserRole[];
}

/** 契约 `UserStatusUpdateRequest`（`additionalProperties: false`），单字段枚举。 */
export interface UpdateUserStatusRequest {
  status: UserStatus;
}

/** 用户列表的 keyset 信封；契约有意不返回 `total`（见文件头口径 1）。 */
export interface UserListData {
  items: UserSummary[];
  /** 下一页游标；null 表示已是最后一页。 */
  nextCursor: string | null;
  /** 本次实际生效的每页条数，由服务端回显而不是前端假设。 */
  limit: number;
}
