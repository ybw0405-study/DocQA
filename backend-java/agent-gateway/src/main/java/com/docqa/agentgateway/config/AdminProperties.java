package com.docqa.agentgateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.admin")
public class AdminProperties {

    /** 与 agent:kill-switch 同一次写入的管理员元数据；缺失只影响展示。 */
    @NotBlank
    private String killSwitchMetaKey = "agent:kill-switch:meta";

    /** Python 启动时发布的实验账本上限；网关只读。 */
    @NotBlank
    private String experimentBudgetKey = "agent:budget:session-tokens";

    public String getKillSwitchMetaKey() {
        return killSwitchMetaKey;
    }

    public void setKillSwitchMetaKey(String killSwitchMetaKey) {
        this.killSwitchMetaKey = killSwitchMetaKey;
    }

    public String getExperimentBudgetKey() {
        return experimentBudgetKey;
    }

    public void setExperimentBudgetKey(String experimentBudgetKey) {
        this.experimentBudgetKey = experimentBudgetKey;
    }
}
