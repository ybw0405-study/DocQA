package com.docqa.user.dto;

import java.util.List;

/** keyset 分页信封，与其余只读列表接口同构：有意不返回 total。 */
public record UserListData(List<UserSummaryData> items, String nextCursor, int limit) {
}
