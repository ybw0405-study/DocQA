import type { MemoryListData } from '../types/memories';
import { deleteNoContent, get } from './http';

/**
 * 长期记忆管理面两个操作的唯一出口（契约 2.9.0 的 `Admin` tag）。
 *
 * **这里不构造任何请求头**：两个操作都没有 `IdempotencyKey` parameter（同裁决），
 * 删除按值幂等。租户由服务端取自已验签 JWT；查询里携带的是**被治理用户**的标识，
 * 不是调用方自己的——与配额页、审计查询同一契约例外。
 *
 * 记忆只由用户在问答里显式下达「记住：…」指令写入，管理面没有写入口。
 */

const BASE = '/admin/ai/memories';

/** 某个用户的记忆清单（createdAt 倒序）；数据面未启用记忆时服务端回 503，不假装空清单。 */
export function listMemories(userId: string): Promise<MemoryListData> {
  return get<MemoryListData>(`${BASE}?userId=${encodeURIComponent(userId)}`);
}

/** 删除一条记忆，立即生效：下一次问答不再注入它；不存在或跨租户的 key 回 404。 */
export function deleteMemory(userId: string, memoryKey: string): Promise<void> {
  return deleteNoContent(`${BASE}/${encodeURIComponent(memoryKey)}?userId=${encodeURIComponent(userId)}`);
}