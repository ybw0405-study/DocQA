/**
 * 契约 `openapi-java.yaml` 2.9.0 长期记忆管理面 schema 的逐字段镜像，字段名不做本地化改名。
 * 镜像与契约是否仍然一致由 `tests/contract-drift.spec.ts` 当场对账。
 *
 * 记忆文本是数据面**掩码后**的事实：管理页只读与删，没有写入口，也不做任何还原。
 */

/** 契约 `MemoryItem.text` 的 maxLength，与问答 question 的上限同源。 */
export const MEMORY_TEXT_MAX_LENGTH = 4000;

/** 契约 `MemoryListData.items` 的 maxItems，也是数据面每用户记忆条数上限。 */
export const MEMORY_FACTS_MAX = 50;

/** 一条长期记忆：key 是数据面生成的 UUID，删除按它定位。 */
export interface MemoryItem {
  key: string;
  text: string;
  createdAt: string;
}

/** 单个用户的记忆清单；每用户上限 50 条、TTL 90 天，契约不分页、不返回 total。 */
export interface MemoryListData {
  items: MemoryItem[];
}