import type { NotificationListData, NotificationReadData, NotificationReadRequest } from '../types/notifications';
import { get, post } from './http';

export interface NotificationListQuery {
  /** null 表示「全部」，不发送该参数；服务端把缺省与 false 同样处理。 */
  unreadOnly?: boolean | null;
  cursor?: string;
  limit?: number;
}

/** 排序为 `plannedTriggerAt DESC, id DESC`；游标编码与排序键严格一致，不携带来源域标记，跨列表复用只表现为空页。 */
export function listNotifications(query: NotificationListQuery): Promise<NotificationListData> {
  return get<NotificationListData>('/notifications', {
    unreadOnly: query.unreadOnly ?? undefined,
    cursor: query.cursor,
    limit: query.limit,
  });
}

/**
 * 契约 2.3.0 的显式例外：这个 POST **不要求**幂等键请求头，因为已读是「只由空变有」的收敛操作，
 * 重复提交既不改写首次已读时刻也没有第二次副作用。请求拦截器仍会补一个随机键，
 * 服务端不消费它，因此反复点击不会被判成同键不同指纹的 409。
 *
 * `all` 与 `ids` 互斥、越权 id 静默跳过、`updated` 只数真正变为已读的行——三点都在响应里可读，
 * 前端不预先「乐观置灰」，否则一次越权提交会被显示成已经读过了。
 */
export function markNotificationsRead(request: NotificationReadRequest): Promise<NotificationReadData> {
  return post<NotificationReadData>('/notifications/read', request);
}
