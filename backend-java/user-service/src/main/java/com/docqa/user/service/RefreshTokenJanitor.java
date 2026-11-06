package com.docqa.user.service;

import com.docqa.user.repository.RefreshTokenRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理已终结且过期的刷新令牌行。轮换链只增不减，不清理会让 refresh_token 无限增长；
 * 每批 500 行，交给 idx_refresh_token_expiry 扫描。
 */
@Component
public class RefreshTokenJanitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenJanitor.class);
    private static final int BATCH_LIMIT = 500;

    private final RefreshTokenRepository repository;
    private final Clock clock;

    public RefreshTokenJanitor(RefreshTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT5M")
    public void purgeExpiredTerminal() {
        int deleted = repository.deleteExpiredTerminal(clock.instant(), BATCH_LIMIT);
        if (deleted > 0) {
            LOGGER.info("Purged expired terminal refresh tokens: count={}", deleted);
        }
    }
}
