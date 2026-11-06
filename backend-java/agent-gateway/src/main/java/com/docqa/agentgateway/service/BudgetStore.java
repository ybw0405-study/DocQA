package com.docqa.agentgateway.service;

import java.time.Instant;
import java.util.Optional;

public interface BudgetStore {

    void initialize(String traceId, int tokenBudget, int maxToolCalls, int maxRounds, Instant deadlineAt);

    Optional<BudgetSnapshot> snapshot(String traceId);
}
