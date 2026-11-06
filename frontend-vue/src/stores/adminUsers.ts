import { defineStore } from 'pinia';
import { listUsers, replaceUserRoles, updateUserStatus } from '../api/users';
import { ApiError } from '../api/errors';
import type { UserRole, UserStatus } from '../types/api';
import type { UserListQuery } from '../api/users';
import type { UserSummary } from '../types/users';

/** 契约全局 `PageLimit` 的默认值（也是 Java 侧 `defaultValue`）；显式发送，与其余列表 store 同口径。 */
const PAGE_LIMIT = 20;

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

export interface AdminUsersState extends FailureFields {
  items: UserSummary[];
  nextCursor: string | null;
  loading: boolean;
  loaded: boolean;
  /** 写操作串行锁：角色是整集替换，两次并发提交会互相覆盖成后写那一套。 */
  submitting: boolean;

  filterStatus: UserStatus | null;
  filterRole: UserRole | null;
}

/** 视图交给 store 的筛选输入；空值一律归一成「不给这个条件」。 */
export interface UserFilterInput {
  status?: UserStatus | null;
  role?: UserRole | null;
}

function applyFailure(target: FailureFields, error: unknown): void {
  if (error instanceof ApiError) {
    target.detail = error.detail;
    target.code = error.code;
    target.traceId = error.traceId;
    return;
  }
  target.detail = '用户列表加载失败，请稍后重试。';
  target.code = null;
  target.traceId = null;
}

function rejectLocal(target: FailureFields, message: string): void {
  target.detail = message;
  target.code = null;
  target.traceId = null;
}

function isStaleCursor(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400;
}

/** 服务端把筛选条件放在每一页重新求值，本地按同一口径判断「这一行还该不该留在列表里」。 */
function matches(row: UserSummary, status: UserStatus | null, role: UserRole | null): boolean {
  return (status === null || row.status === status) && (role === null || row.roles.includes(role));
}

export const useAdminUsersStore = defineStore('adminUsers', {
  state: (): AdminUsersState => ({
    items: [],
    nextCursor: null,
    loading: false,
    loaded: false,
    submitting: false,

    filterStatus: null,
    filterRole: null,

    detail: null,
    code: null,
    traceId: null,
  }),

  getters: {
    canLoadMore: (state): boolean => !state.loading && state.nextCursor !== null,
    hasFilter: (state): boolean => state.filterStatus !== null || state.filterRole !== null,

    queryParams(state): UserListQuery {
      return {
        status: state.filterStatus ?? undefined,
        role: state.filterRole ?? undefined,
        limit: PAGE_LIMIT,
      };
    },
  },

  actions: {
    clearFailure(): void {
      this.detail = null;
      this.code = null;
      this.traceId = null;
    },

    async loadFirst(): Promise<void> {
      if (this.loading) {
        return;
      }
      await this.fetchFirstPage();
    },

    async fetchFirstPage(): Promise<void> {
      this.loading = true;
      try {
        const data = await listUsers(this.queryParams);
        this.items = data.items;
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    async loadMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadMore || cursor === null) {
        return;
      }
      this.loading = true;
      try {
        const data = await listUsers({ ...this.queryParams, cursor });
        this.items = [...this.items, ...data.items];
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (isStaleCursor(error)) {
          // 排序键是 `created_at DESC`，游标锚定「排序毫秒|记录ID」；锚点失效后停在坏游标上只会看不到更旧的行。
          this.nextCursor = null;
          this.loading = false;
          await this.fetchFirstPage();
          this.detail = '用户翻页游标已失效，列表已回到第一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        this.loading = false;
      }
    },

    /** 换条件等于换一页：已拼接的后续行必须清空，否则界面上会是两套条件的混合结果。 */
    async applyFilters(input: UserFilterInput): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.filterStatus = input.status ?? null;
      this.filterRole = input.role ?? null;
      this.items = [];
      this.nextCursor = null;
      this.loaded = false;
      this.submitting = true;
      try {
        await this.fetchFirstPage();
        return true;
      } finally {
        this.submitting = false;
      }
    },

    /**
     * 整集替换角色（契约：空集合非法，服务端回 422 `USR-VALIDATION-001` 的 `roles` 字段违规）。
     * 这里只把「空集合」这一条在本地挡住，其余判定（自锁护栏、零角色以外的策略）由服务端给出，
     * 前端不复制一份服务端规则库。
     */
    async saveRoles(userId: string, roles: UserRole[]): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      if (roles.length === 0) {
        rejectLocal(this, '至少要为账号保留一个角色，空集合不会被服务端接受。');
        return false;
      }
      this.submitting = true;
      try {
        const updated = await replaceUserRoles(userId, { roles });
        this.applyUpdatedRow(updated);
        return true;
      } catch (error) {
        applyFailure(this, error);
        return false;
      } finally {
        this.submitting = false;
      }
    },

    async setStatus(userId: string, status: UserStatus): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.submitting = true;
      try {
        const updated = await updateUserStatus(userId, { status });
        this.applyUpdatedRow(updated);
        return true;
      } catch (error) {
        applyFailure(this, error);
        return false;
      } finally {
        this.submitting = false;
      }
    },

    /**
     * 排序键 `created_at` 不可变，所以写成功后的这一行**位置不变**，回显的完整行就地合并即可，
     * 不需要重取整页——那只会把管理员刚翻到的位置丢掉。
     * 唯一要动集合的情形是筛选：条件在每一页重新求值，改完不再匹配当前条件的行必须离开列表，
     * 而它的新状态已经由这条写响应给全，重新查询才会再看到它。
     */
    applyUpdatedRow(row: UserSummary): void {
      this.items = matches(row, this.filterStatus, this.filterRole)
        ? this.items.map((item) => (item.userId === row.userId ? row : item))
        : this.items.filter((item) => item.userId !== row.userId);
    },
  },
});
