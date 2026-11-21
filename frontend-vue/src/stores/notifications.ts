import { defineStore } from 'pinia';
import { listNotifications, markNotificationsRead } from '../api/notifications';
import type { NotificationData, NotificationReadRequest } from '../types/notifications';
import { NOTIFICATION_IDS_MAX } from '../types/notifications';
import { clearFailureFields, isStaleCursor, makeApplyFailure, PAGE_LIMIT } from './failureFields';

const applyFailure = makeApplyFailure('通知面板加载失败，请稍后重试。');

export interface NotificationsState {
  items: NotificationData[];
  nextCursor: string | null;
  listLoading: boolean;
  listLoaded: boolean;
  /** 列表取数代号：写后刷新与「只看未读」切换递增它，在途翻页的过期响应一律丢弃。 */
  listSeq: number;
  /** 契约 `unreadOnly`：true 只返回 `readAt` 为 null 的行。 */
  unreadOnly: boolean;
  /** 标记已读在途；与列表读锁分开，红点刷新不该被一次翻页挡住。 */
  marking: boolean;
  /** 最近一次标记**真正由空变有**的行数，0 表示这批在服务端看已经全是已读。 */
  lastUpdated: number | null;

  detail: string | null;
  code: string | null;
  traceId: string | null;
}

export const useNotificationsStore = defineStore('notifications', {
  state: (): NotificationsState => ({
    items: [],
    nextCursor: null,
    listLoading: false,
    listLoaded: false,
    listSeq: 0,
    unreadOnly: false,
    marking: false,
    lastUpdated: null,

    detail: null,
    code: null,
    traceId: null,
  }),

  getters: {
    canLoadMore: (state): boolean => !state.listLoading && state.nextCursor !== null,

    /**
     * **已加载页**里仍未读的行数，不是全量未读数：契约的只读列表一律不做 `COUNT`，
     * 红点因此只承诺「这一屏里还有几条没读」，文案也不许写成「你有 N 条未读通知」。
     */
    unreadCount: (state): number => state.items.filter((item) => item.readAt === null).length,
  },

  actions: {
    clearFailure(): void {
      clearFailureFields(this);
    },

    async loadListFirst(): Promise<void> {
      if (this.listLoading) {
        return;
      }
      await this.fetchFirstPage();
    },

    /**
     * 不受 `listLoading` 守卫约束：写操作或「只看未读」切换后必须真取新结果，
     * 否则服务端事实被一次在途旧读挤掉；过期响应按代号丢弃。
     */
    async fetchFirstPage(): Promise<void> {
      const seq = ++this.listSeq;
      this.listLoading = true;
      try {
        const data = await listNotifications({ unreadOnly: this.unreadOnly, limit: PAGE_LIMIT });
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
          this.listLoading = false;
          this.listLoaded = true;
        }
      }
    },

    async loadListMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadMore || cursor === null) {
        return;
      }
      const seq = this.listSeq;
      this.listLoading = true;
      try {
        const data = await listNotifications({ unreadOnly: this.unreadOnly, cursor, limit: PAGE_LIMIT });
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
          this.nextCursor = null;
          this.listLoading = false;
          await this.fetchFirstPage();
          this.detail = '通知翻页游标已失效，列表已回到第一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.listSeq) {
          this.listLoading = false;
        }
      }
    },

    /** 过滤条件是每页重新求值的、不是快照：换过滤就等于换一页，必须清空已拼接的旧结果。 */
    async setUnreadOnly(value: boolean): Promise<void> {
      if (value === this.unreadOnly) {
        return;
      }
      this.unreadOnly = value;
      this.items = [];
      this.nextCursor = null;
      this.listLoaded = false;
      await this.fetchFirstPage();
    },

    /**
     * 标记已读是**收敛操作**：服务端只回 `updated` 行数与 `all` 回显，不回任何行，
     * 所以前端既不伪造 `readAt`，也不预测哪些行会变灰——成功后一律重取第一页，
     * 面板上每一个字段都来自服务端。代价是每次标记多一个 GET，
     * 换来的是「未加载页的未读项」不会被本地乐观状态说成已读。
     */
    async markRead(ids: string[]): Promise<boolean> {
      if (this.marking) {
        return false;
      }
      if (ids.length === 0) {
        this.detail = '请先勾选要标记已读的通知。';
        this.code = null;
        this.traceId = null;
        return false;
      }
      if (ids.length > NOTIFICATION_IDS_MAX) {
        this.detail = `一次最多标记 ${String(NOTIFICATION_IDS_MAX)} 条，其余请分批或用「全部已读」。`;
        this.code = null;
        this.traceId = null;
        return false;
      }
      return this.submitMark({ all: false, ids });
    },

    /** 「全部未读」不依赖已加载页的 id，这是勾选提交替代不了它的原因。 */
    async markAllRead(): Promise<boolean> {
      if (this.marking) {
        return false;
      }
      return this.submitMark({ all: true });
    },

    async submitMark(request: NotificationReadRequest): Promise<boolean> {
      this.marking = true;
      try {
        const data = await markNotificationsRead(request);
        this.lastUpdated = data.updated;
        await this.fetchFirstPage();
        return true;
      } catch (error) {
        applyFailure(this, error);
        return false;
      } finally {
        this.marking = false;
      }
    },
  },
});
