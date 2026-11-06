package com.docqa.agentgateway.dto;

/** 实验账本快照；limitsKnown=false 表示 Python 尚未发布上限，两个上限字段此时为 null。 */
public record ExperimentBudgetState(
        long callsUsed,
        long tokensUsed,
        Long callLimit,
        Long tokenLimit,
        boolean limitsKnown) {
}
