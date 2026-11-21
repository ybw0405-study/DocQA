package com.docqa.user.service;

import com.docqa.common.tenant.TenantContext;
import com.docqa.user.config.DemoUserProperties;
import com.docqa.user.domain.NewUser;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.repository.UserRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动种子账号：app_user 里没有同名行时写入一次，之后登录一律走数据库。
 * 演示口令摘要来自部署变量，仓库里不出现任何可用口令的散列。
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoUserSeeder.class);
    /** 与 AuthProperties 默认强度一致；低于此值的哈希不接受作为种子管理员口令。 */
    private static final int MIN_BCRYPT_COST = 12;
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");

    private final DemoUserProperties properties;
    private final UserRepository userRepository;
    private final Clock clock;

    public DemoUserSeeder(DemoUserProperties properties, UserRepository userRepository, Clock clock) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        String passwordHash = properties.getPasswordHash();
        if (passwordHash == null || passwordHash.isBlank()) {
            LOGGER.warn("Demo account seeding skipped: docqa.demo-user.password-hash is not configured");
            return;
        }
        Matcher matcher = BCRYPT_HASH.matcher(passwordHash);
        if (!matcher.matches()) {
            throw new IllegalStateException("Demo user password hash must be a BCrypt hash");
        }
        // 只查形态不够：cost=4 的哈希形态合法但 GPU 分钟级可爆破，且曾随备份落盘。
        // 种子管理员必须达到与应用策略一致的下限，弱哈希直接拒绝启动而非静默放行。
        int cost = Integer.parseInt(matcher.group(1));
        if (cost < MIN_BCRYPT_COST) {
            throw new IllegalStateException(
                    "Demo user BCrypt cost " + cost + " is below minimum " + MIN_BCRYPT_COST);
        }
        String username = properties.getUsername() == null
                ? "demo"
                : properties.getUsername().trim().toLowerCase(Locale.ROOT);
        if (username.isEmpty()) {
            throw new IllegalStateException("Demo user username must not be blank");
        }
        if (userRepository.existsByUsername(TenantContext.DEFAULT_TENANT_ID, username)) {
            return;
        }

        Set<UserRole> roles = properties.isAdmin()
                ? EnumSet.of(UserRole.USER, UserRole.ADMIN)
                : EnumSet.of(UserRole.USER);
        userRepository.insert(
                TenantContext.DEFAULT_TENANT_ID,
                new NewUser(properties.getUserId(), username, passwordHash, UserStatus.ACTIVE, clock.instant()),
                roles);
        LOGGER.info("Seeded demo account with roles={}", roles);
    }
}
