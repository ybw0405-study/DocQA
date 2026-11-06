package com.docqa.user.support;

import com.docqa.user.service.RevocationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 记录撤销写入的假存储；读路径与真实实现一样是「没写就是未撤销」。 */
public class RecordingRevocationStore implements RevocationStore {

    private final Set<String> revokedTokenIds = new HashSet<>();
    private final Map<String, Duration> tokenTtls = new HashMap<>();
    private final Map<UUID, Instant> watermarks = new HashMap<>();
    private Duration userTtl;

    @Override
    public void revokeAccessToken(String tokenId, Duration remainingLifetime) {
        revokedTokenIds.add(tokenId);
        tokenTtls.put(tokenId, remainingLifetime);
    }

    @Override
    public void revokeUser(UUID userId, Instant watermark, Duration ttl) {
        watermarks.put(userId, watermark);
        userTtl = ttl;
    }

    @Override
    public boolean isAccessTokenRevoked(String tokenId) {
        return revokedTokenIds.contains(tokenId);
    }

    @Override
    public Optional<Instant> issuedAtFloor(UUID userId) {
        return Optional.ofNullable(watermarks.get(userId));
    }

    public Set<String> revokedTokenIds() {
        return Set.copyOf(revokedTokenIds);
    }

    public Duration ttlOf(String tokenId) {
        return tokenTtls.get(tokenId);
    }

    public Optional<Instant> watermarkOf(UUID userId) {
        return Optional.ofNullable(watermarks.get(userId));
    }

    public Duration userTtl() {
        return userTtl;
    }
}
