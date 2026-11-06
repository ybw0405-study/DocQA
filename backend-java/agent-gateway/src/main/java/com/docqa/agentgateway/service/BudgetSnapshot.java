package com.docqa.agentgateway.service;

import java.time.Instant;

public record BudgetSnapshot(
        int tokensUsed,
        int toolCallsMade,
        int roundsUsed,
        int tokenBudget,
        int maxToolCalls,
        int maxRounds,
        Instant deadlineAt) {
}
