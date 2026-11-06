package com.docqa.agentgateway.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAiControlsRequest(@NotNull Boolean killSwitchActive) {
}
