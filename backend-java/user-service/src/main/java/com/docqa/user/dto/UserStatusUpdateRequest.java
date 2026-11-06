package com.docqa.user.dto;

import com.docqa.user.domain.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UserStatusUpdateRequest(@NotNull UserStatus status) {
}
