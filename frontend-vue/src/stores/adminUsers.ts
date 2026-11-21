import { defineStore } from 'pinia';
import { listUsers, replaceUserRoles, updateUserStatus } from '../api/users';
import type { UserRole, UserStatus } from '../types/api';
import type { UserListQuery } from '../api/users';
import type { UserSummary } from '../types/users';
import {
  clearFailureFields,
  isStaleCursor,
  makeApplyFailure,
  PAGE_LIMIT,
  rejectLocal,
  type FailureFields,
} from './failureFields';

const applyFailure = makeApplyFailure('用户列表加载失败，请稍后重试。');

export interface AdminUsersState extends FailureFields {
  items: UserSummary[];
  nextCursor: string | null;
  loading: boolean;
  loaded: boolean;
  /** 列表取数代号：过滤切换递增它，在途翻页的过期响应一律丢弃。 */
  listSeq: number;
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
    listSeq: 0,
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
      clearFailureFields(this);
    },

    async loadFirst(): Promise<void> {
      if (this.loading) {
        return;
      }
      await this.fetchFirstPage();
    },

    /** 不受 `loading` 守卫约束：过滤切换后必须真取新条件结果，过期响应按代号丢弃。 */
    async fetchFirstPage(): Promise<void> {
      const seq = ++this.listSeq;
      this.loading = true;
      try {
        const data = await listUsers(this.queryParams);
        if (seq !== this.listSeq) {
          return;
        }
        this.items = data.items;
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.listSeq) {
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.listSeq) {
          this.loading = false;
          this.loaded = true;
        }
      }
    },

    async loadMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadMore || cursor === null) {
        return;
      }
      const seq = this.listSeq;
      this.loading = true;
      try {
        const data = await listUsers({ ...this.queryParams, cursor });
        if (seq !== this.listSeq) {
          return;
        }
        this.items = [...this.items, ...data.items];
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.listSeq) {
          return;
        }
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
        if (seq === this.listSeq) {
          this.loading = false;
        }
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
