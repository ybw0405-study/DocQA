package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.ModelConnectionProperties;
import com.docqa.agentgateway.dto.CreateModelConnectionRequest;
import com.docqa.agentgateway.dto.ModelConnection;
import com.docqa.agentgateway.dto.ModelConnectionListData;
import com.docqa.agentgateway.dto.ModelProbeData;
import com.docqa.agentgateway.dto.ModelProbeFailure;
import com.docqa.agentgateway.repository.AdminAuditEntry;
import com.docqa.agentgateway.repository.AdminAuditRepository;
import com.docqa.agentgateway.repository.ModelConnectionRepository;
import com.docqa.agentgateway.repository.ModelConnectionRow;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型连接的管理编排：upsert / 录入凭据 / 探测 / 切换生效 / 删除。
 *
 * <p>安全边界：
 * <ul>
 * <li>tenantId 一律来自已验签 JWT，不接受请求参数；每个仓库调用都带租户谓词。</li>
 * <li>明文 Key 只在 {@link #authorize} 的入参与 {@link #probe} 的出网调用里出现，
 * 落库前封装成密文，日志与审计 payload 都不含它。</li>
 * <li>审计 payload 只用跨语言白名单里的键（{@code model}），连接 id 只进结构化日志，
 * 避免为这个功能扩白名单而牵动三侧实现。</li>
 * <li>每次写提交后同步 Redis 分发键；MySQL 是事实来源，分发失败不回滚库内变更。</li>
 * </ul>
 */
@Service
public class ModelConnectionService {

    public static final String EVENT_UPSERTED = "ADMIN_AI_MODEL_CONNECTION_UPSERTED";
    public static final String EVENT_CREDENTIAL_SET = "ADMIN_AI_MODEL_CONNECTION_CREDENTIAL_SET";
    public static final String EVENT_ACTIVATED = "ADMIN_AI_MODEL_CONNECTION_ACTIVATED";
    public static final String EVENT_DEACTIVATED = "ADMIN_AI_MODEL_CONNECTION_DEACTIVATED";
    public static final String EVENT_DELETED = "ADMIN_AI_MODEL_CONNECTION_DELETED";
    public static final String EVENT_PROBED = "ADMIN_AI_MODEL_CONNECTION_PROBED";

    /** 每租户连接数上限：管理面是小集合，封顶让列表查询天然有界，也让误操作不会堆出上千条。 */
    static final int MAX_CONNECTIONS_PER_TENANT = 20;

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelConnectionService.class);
    private static final String PROBE_OK = "OK";
    private static final String PROBE_FAILED = "FAILED";

    private final ModelConnectionRepository repository;
    private final AdminAuditRepository auditRepository;
    private final TransactionRunner transactionRunner;
    private final RedisModelConnectionPublisher publisher;
    private final ModelProbeClient probeClient;
    private final LlmEgressPolicy egressPolicy;
    private final ModelConnectionProperties properties;
    private final Clock clock;

    /** 懒构造：主密钥缺失或非法只让本功能报错，不能把整个网关的启动拖下水。 */
    private volatile CredentialCipher cipher;

    public ModelConnectionService(
            ModelConnectionRepository repository,
            AdminAuditRepository auditRepository,
            TransactionRunner transactionRunner,
            RedisModelConnectionPublisher publisher,
            ModelProbeClient probeClient,
            LlmEgressPolicy egressPolicy,
            ModelConnectionProperties properties,
            Clock clock) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.transactionRunner = transactionRunner;
        this.publisher = publisher;
        this.probeClient = probeClient;
        this.egressPolicy = egressPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    public ModelConnectionListData list(UUID tenantId) {
        List<ModelConnection> items = repository.findAll(tenantId).stream()
                .map(ModelConnectionService::toDto)
                .toList();
        return new ModelConnectionListData(items);
    }

    public ModelConnection upsert(UUID tenantId, UUID adminId, CreateModelConnectionRequest request) {
        String baseUrl = validateBaseUrl(request.baseUrl());
        String name = request.name().strip();
        boolean enabled = request.enabled() == null || request.enabled();
        Instant now = clock.instant();
        String traceId = TraceIdContext.currentOrCreate();
        boolean creating = repository.findByName(tenantId, name).isEmpty();
        if (creating && repository.count(tenantId) >= MAX_CONNECTIONS_PER_TENANT) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    "每个租户最多 " + MAX_CONNECTIONS_PER_TENANT + " 条模型连接，请先删除不再使用的连接。",
                    List.of(FieldViolation.of("name", "connection limit reached")));
        }
        UUID candidateId = UUID.randomUUID();
        transactionRunner.execute(() -> {
            repository.upsert(tenantId, candidateId, name, request.presetId(), baseUrl, request.model().strip(),
                    enabled, adminId, now);
            audit(tenantId, adminId, traceId, EVENT_UPSERTED, request.model().strip(), now);
        });
        // upsert 命中已有行时保留原 id，所以必须按 name 重读，不能拿 candidateId 当结果。
        ModelConnectionRow row = repository.findByName(tenantId, name)
                .orElseThrow(() -> new BusinessException(ErrorCode.COM_INTERNAL_001, "模型连接写入后重读失败。"));
        syncActive(tenantId);
        LOGGER.info("Admin model connection upserted: tenantId={}, connectionId={}, model={}, enabled={}, "
                        + "creating={}, adminId={}, traceId={}",
                tenantId, row.id(), row.model(), enabled, creating, adminId, traceId);
        return toDto(row);
    }

    public ModelConnection authorize(UUID tenantId, UUID adminId, UUID connectionId, String secret) {
        ModelConnectionRow row = require(tenantId, connectionId);
        CredentialCipher credentialCipher = cipher();
        byte[] sealed = credentialCipher.seal(
                secret.strip().getBytes(StandardCharsets.UTF_8),
                CredentialCipher.aad(row.id(), tenantId));
        String keyId = credentialCipher.keyId();
        Instant now = clock.instant();
        String traceId = TraceIdContext.currentOrCreate();
        transactionRunner.execute(() -> {
            repository.updateCredential(tenantId, connectionId, sealed, keyId, adminId, now);
            audit(tenantId, adminId, traceId, EVENT_CREDENTIAL_SET, row.model(), now);
        });
        syncActive(tenantId);
        LOGGER.info("Admin model connection credential set: tenantId={}, connectionId={}, keyId={}, "
                        + "adminId={}, traceId={}",
                tenantId, connectionId, keyId, adminId, traceId);
        return toDto(require(tenantId, connectionId));
    }

    public ModelProbeData probe(UUID tenantId, UUID adminId, UUID connectionId) {
        ModelConnectionRow row = require(tenantId, connectionId);
        String apiKey = null;
        if (row.hasCredential()) {
            try {
                apiKey = new String(
                        cipher().open(row.credential(), CredentialCipher.aad(row.id(), tenantId), row.credentialKeyId()),
                        StandardCharsets.UTF_8);
            } catch (BusinessException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                // 主密钥轮换后旧密钥没保留、或密文被搬动过：明确告诉运维要重新录入，不静默降级。
                LOGGER.warn("Model connection credential unreadable: tenantId={}, connectionId={}, keyId={}",
                        tenantId, connectionId, row.credentialKeyId());
                return recordProbe(tenantId, adminId, row,
                        ModelProbeData.failure(ModelProbeFailure.CREDENTIAL_UNREADABLE,
                                "凭据无法解密，请重新录入 API Key（主密钥轮换后需保留旧密钥）。"));
            }
        }
        ModelProbeData result = probeClient.probe(row.baseUrl(), apiKey);
        if (result.failureReason() == ModelProbeFailure.CREDENTIAL_MISSING) {
            // 没录 Key 就没发生探测，probe_status 保持 UNKNOWN（「从未探测」而不是「探测失败」）。
            return result;
        }
        return recordProbe(tenantId, adminId, row, result);
    }

    public ModelConnection setActivation(UUID tenantId, UUID adminId, UUID connectionId, boolean active) {
        ModelConnectionRow row = require(tenantId, connectionId);
        if (active && !row.enabled()) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    "停用的连接不能设为生效，请先启用它。",
                    List.of(FieldViolation.of("active", "connection is disabled")));
        }
        Instant now = clock.instant();
        String traceId = TraceIdContext.currentOrCreate();
        transactionRunner.execute(() -> {
            if (active) {
                // 先清旧再设新：唯一键 (tenant_id, active_slot) 全程只允许一条生效。
                repository.clearActive(tenantId, adminId, now);
                repository.markActive(tenantId, connectionId, adminId, now);
            } else {
                repository.deactivate(tenantId, connectionId, adminId, now);
            }
            audit(tenantId, adminId, traceId, active ? EVENT_ACTIVATED : EVENT_DEACTIVATED, row.model(), now);
        });
        syncActive(tenantId);
        LOGGER.info("Admin model connection activation changed: tenantId={}, connectionId={}, active={}, "
                        + "adminId={}, traceId={}",
                tenantId, connectionId, active, adminId, traceId);
        return toDto(require(tenantId, connectionId));
    }

    public void delete(UUID tenantId, UUID adminId, UUID connectionId) {
        ModelConnectionRow row = require(tenantId, connectionId);
        Instant now = clock.instant();
        String traceId = TraceIdContext.currentOrCreate();
        transactionRunner.execute(() -> {
            repository.delete(tenantId, connectionId);
            audit(tenantId, adminId, traceId, EVENT_DELETED, row.model(), now);
        });
        syncActive(tenantId);
        LOGGER.info("Admin model connection deleted: tenantId={}, connectionId={}, adminId={}, traceId={}",
                tenantId, connectionId, adminId, traceId);
    }

    /** 启动期重发布：把每个租户的生效连接重新写进 Redis，并清掉残留键。 */
    public void republishActiveConnections() {
        publisher.republishAll(repository.findAllActive());
    }

    private ModelProbeData recordProbe(UUID tenantId, UUID adminId, ModelConnectionRow row, ModelProbeData result) {
        Instant now = clock.instant();
        String traceId = TraceIdContext.currentOrCreate();
        transactionRunner.execute(() -> {
            repository.updateProbeStatus(tenantId, row.id(), result.ok() ? PROBE_OK : PROBE_FAILED, now, now);
            audit(tenantId, adminId, traceId, EVENT_PROBED, row.model(), now);
        });
        syncActive(tenantId);
        LOGGER.info("Admin model connection probed: tenantId={}, connectionId={}, ok={}, failureReason={}, "
                        + "adminId={}, traceId={}",
                tenantId, row.id(), result.ok(), result.failureReason(), adminId, traceId);
        return result;
    }

    private String validateBaseUrl(String rawBaseUrl) {
        try {
            return egressPolicy.validate(rawBaseUrl).baseUrl();
        } catch (LlmEgressPolicy.EgressRejected ex) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    "Base URL 不满足出网策略：" + ex.getMessage(),
                    List.of(FieldViolation.of("baseUrl", ex.getMessage())));
        }
    }

    private ModelConnectionRow require(UUID tenantId, UUID connectionId) {
        return repository.findById(tenantId, connectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COM_NOT_FOUND_001, "模型连接不存在。"));
    }

    private void syncActive(UUID tenantId) {
        publisher.publish(tenantId, repository.findActive(tenantId).orElse(null));
    }

    private void audit(UUID tenantId, UUID adminId, String traceId, String eventType, String model, Instant now) {
        auditRepository.insert(tenantId, new AdminAuditEntry(
                UUID.randomUUID(),
                traceId,
                adminId,
                eventType,
                "INFO",
                Map.of("model", model),
                now,
                now));
    }

    private synchronized CredentialCipher cipher() {
        CredentialCipher existing = cipher;
        if (existing != null) {
            return existing;
        }
        String masterKey = properties.getMasterKey();
        if (masterKey == null || masterKey.isBlank()) {
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "服务端未配置凭据主密钥 DOCQA_CRED_MASTER_KEY，无法保存或探测模型连接。");
        }
        try {
            CredentialCipher created = new CredentialCipher(masterKey, properties.getPreviousKeys());
            cipher = created;
            return created;
        } catch (IllegalStateException ex) {
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "凭据主密钥格式非法：DOCQA_CRED_MASTER_KEY 必须是 base64 编码的 32 字节。");
        }
    }

    private static ModelConnection toDto(ModelConnectionRow row) {
        return new ModelConnection(
                row.id(),
                row.name(),
                row.presetId(),
                row.baseUrl(),
                row.model(),
                row.enabled(),
                row.active(),
                row.hasCredential(),
                row.probeStatus(),
                row.lastProbedAt(),
                row.createdAt(),
                row.updatedAt(),
                row.updatedBy());
    }
}
