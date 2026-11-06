package com.docqa.agentgateway.service;

public record ExperimentBudgetView(long callsUsed, long tokensUsed, Long callLimit, Long tokenLimit) {

    public boolean limitsKnown() {
        return callLimit != null && tokenLimit != null;
    }
}
