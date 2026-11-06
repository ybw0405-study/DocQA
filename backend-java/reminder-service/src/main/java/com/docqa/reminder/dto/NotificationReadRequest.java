package com.docqa.reminder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code all} 与 {@code ids} 互斥，互斥判定与「{@code all=false} 时 ids 至少一个」都在服务层做：
 * 那是跨字段规则，单字段注解表达不了。
 */
public record NotificationReadRequest(
        @NotNull
        Boolean all,

        @Size(min = 1, max = 500)
        List<UUID> ids) {
}
