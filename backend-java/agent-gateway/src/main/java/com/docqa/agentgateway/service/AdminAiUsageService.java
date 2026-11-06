package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.UsageDayPoint;
import com.docqa.agentgateway.dto.UsageStatusCount;
import com.docqa.agentgateway.dto.UsageSummary;
import com.docqa.agentgateway.dto.UsageTopUser;
import com.docqa.agentgateway.dto.UsageTotals;
import com.docqa.agentgateway.repository.AiUsageRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 管理面 AI 用量汇总（D-159）。
 *
 * <p>时间窗缺省最近 24 小时、最长 31 天；日桶按 UTC；只返回计数与均值，
 * 不返回分位数、不承诺延迟上界。本服务不投递 MQ、不调用 Python。</p>
 */
@Service
public class AdminAiUsageService {

    public static final String BUCKET_DAY = "day";
    private static final int MAX_TOP_USERS = 50;
    private static final List<String> STATUS_ORDER =
            List.of("RUNNING", "SUCCESS", "BUDGET_EXHAUSTED", "FAILED");

    private final AiUsageRepository usageRepository;
    private final Clock clock;

    public AdminAiUsageService(AiUsageRepository usageRepository, Clock clock) {
        this.usageRepository = usageRepository;
        this.clock = clock;
    }

    public UsageSummary summarize(String from, String to, String bucket, int topUsers) {
        if (bucket != null && !bucket.isBlank() && !BUCKET_DAY.equals(bucket)) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    "unsupported bucket",
                    List.of(FieldViolation.of("bucket", "only day is supported")));
        }
        if (topUsers < 1 || topUsers > MAX_TOP_USERS) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    "topUsers out of range",
                    List.of(FieldViolation.of("topUsers", "must be between 1 and 50")));
        }
        AdminQueryWindow window = AdminQueryWindow.resolve(from, to, clock, true);
        UsageTotals totals = usageRepository.totals(window.from(), window.to());
        List<UsageDayPoint> byDay = usageRepository.byDay(window.from(), window.to());
        List<UsageStatusCount> byStatus = sortStatuses(usageRepository.byStatus(window.from(), window.to()));
        List<UsageTopUser> top = usageRepository.topUsers(window.from(), window.to(), topUsers);
        return new UsageSummary(window.from(), window.to(), BUCKET_DAY, totals, byDay, byStatus, top);
    }

    private static List<UsageStatusCount> sortStatuses(List<UsageStatusCount> rows) {
        return rows.stream()
                .sorted(Comparator.comparingInt(row -> statusRank(row.status())))
                .toList();
    }

    private static int statusRank(String status) {
        int index = STATUS_ORDER.indexOf(status);
        return index < 0 ? STATUS_ORDER.size() : index;
    }
}
