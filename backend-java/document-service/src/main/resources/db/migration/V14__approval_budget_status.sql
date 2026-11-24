-- 人在回路：审批挂起态进入 budget_status 值域（PENDING_APPROVAL）。
ALTER TABLE agent_execution DROP CONSTRAINT chk_agent_execution_budget;
ALTER TABLE agent_execution ADD CONSTRAINT chk_agent_execution_budget CHECK (
    budget_status IS NULL OR budget_status IN ('OK', 'LOW_CONFIDENCE', 'BUDGET_EXHAUSTED', 'PENDING_APPROVAL')
  );
