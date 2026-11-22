package com.docqa.agentgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动期把库内的生效连接重新发布到 Redis。
 *
 * <p>为什么需要：Redis 只是分发缓存，本地配置是 maxmemory + allkeys-lru，
 * 无 TTL 的冷键在内存紧张时会被驱逐（审计游标就因此从 Redis 搬进了 MySQL）。
 * 键被驱逐或 Redis 被清空后，数据面会回退到 env provider——不报错，但界面配置悄悄失效。
 * 网关每次启动重发布一次，把这个静默失效收敛掉。
 *
 * <p>刻意 best-effort：DB 或 Redis 不可用只记 WARN，不能让整个网关起不来。
 */
@Component
public class ModelConnectionRepublishRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelConnectionRepublishRunner.class);

    private final ModelConnectionService service;

    public ModelConnectionRepublishRunner(ModelConnectionService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            service.republishActiveConnections();
        } catch (RuntimeException ex) {
            LOGGER.warn("Model connection republish skipped: {}", ex.getClass().getSimpleName());
        }
    }
}
