package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.config.ModelConnectionProperties;
import com.docqa.agentgateway.dto.CreateModelConnectionRequest;
import com.docqa.agentgateway.dto.ModelConnection;
import com.docqa.agentgateway.dto.ModelProbeData;
import com.docqa.agentgateway.dto.ModelProbeFailure;
import com.docqa.agentgateway.repository.AdminAuditEntry;
import com.docqa.agentgateway.repository.AdminAuditRepository;
import com.docqa.agentgateway.repository.ModelConnectionRepository;
import com.docqa.agentgateway.repository.ModelConnectionRow;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelConnectionServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FOREIGN_TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CONNECTION_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-22T10:15:42.010Z");
    private static final String MASTER_KEY = base64Of(sha256("docqa-service-test-master-key"));
    private static final String SECRET = "sk-unit-test-0123456789";
    private static final String BASE_URL = "https://api.deepseek.com/v1";

    @Mock
    private ModelConnectionRepository repository;
    @Mock
    private AdminAuditRepository auditRepository;
    @Mock
    private TransactionRunner transactionRunner;
    @Mock
    private RedisModelConnectionPublisher publisher;
    @Mock
    private ModelProbeClient probeClient;
    @Mock
    private LlmEgressPolicy egressPolicy;

    private final ModelConnectionProperties properties = new ModelConnectionProperties();
    private ModelConnectionService service;

    @BeforeEach
    void setUp() {
        properties.setMasterKey(MASTER_KEY);
        service = new ModelConnectionService(
                repository, auditRepository, transactionRunner, publisher, probeClient, egressPolicy,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(transactionRunner).execute(any(Runnable.class));
        lenient().when(egressPolicy.validate(anyString()))
                .thenAnswer(invocation -> new LlmEgressPolicy.ValidatedEndpoint(
                        invocation.getArgument(0, String.class), "api.deepseek.com", 443));
    }

    @Test
    void upsertPersistsTheValidatedUrlAndAuditsOnlyWhitelistedKeys() {
        when(repository.findByName(TENANT_ID, "DeepSeek"))
                .thenReturn(Optional.empty(), Optional.of(row(true, false)));
        when(repository.count(TENANT_ID)).thenReturn(0);

        ModelConnection result = service.upsert(TENANT_ID, ADMIN_ID,
                new CreateModelConnectionRequest("DeepSeek", "deepseek", BASE_URL, "deepseek-chat", null));

        verify(repository).upsert(eq(TENANT_ID), any(UUID.class), eq("DeepSeek"), eq("deepseek"), eq(BASE_URL),
                eq("deepseek-chat"), eq(true), eq(ADMIN_ID), eq(NOW));
        AdminAuditEntry entry = captureAudit();
        assertThat(entry.eventType()).isEqualTo(ModelConnectionService.EVENT_UPSERTED);
        assertThat(entry.payload()).isEqualTo(Map.of("model", "deepseek-chat"));
        assertThat(entry.traceId()).matches("[0-9a-f]{32}");
        assertThat(result.hasCredential()).isFalse();
        verify(publisher).publish(eq(TENANT_ID), any());
    }

    @Test
    void upsertRejectsUrlsTheEgressPolicyRefusesBeforeWritingAnything() {
        when(egressPolicy.validate("http://169.254.169.254/latest"))
                .thenThrow(new LlmEgressPolicy.EgressRejected("URL resolves to a private or reserved address"));

        assertThatThrownBy(() -> service.upsert(TENANT_ID, ADMIN_ID,
                new CreateModelConnectionRequest("元数据", null, "http://169.254.169.254/latest", "m", null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(ex.getFieldViolations()).extracting(v -> v.field()).containsExactly("baseUrl");
                });
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        verify(auditRepository, never()).insert(any(), any());
    }

    @Test
    void upsertEnforcesThePerTenantCapOnlyWhenCreating() {
        when(repository.findByName(TENANT_ID, "新连接")).thenReturn(Optional.empty());
        when(repository.count(TENANT_ID)).thenReturn(ModelConnectionService.MAX_CONNECTIONS_PER_TENANT);

        assertThatThrownBy(() -> service.upsert(TENANT_ID, ADMIN_ID,
                new CreateModelConnectionRequest("新连接", null, BASE_URL, "m", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001));
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    /** 同名重复提交是改配置，不该被上限拦住，也不该改变生效状态。 */
    @Test
    void upsertOnAnExistingNameUpdatesInPlaceAndKeepsItsId() {
        when(repository.findByName(TENANT_ID, "DeepSeek"))
                .thenReturn(Optional.of(row(true, true)), Optional.of(row(true, true)));

        ModelConnection result = service.upsert(TENANT_ID, ADMIN_ID,
                new CreateModelConnectionRequest("DeepSeek", "deepseek", BASE_URL, "deepseek-reasoner", null));

        verify(repository, never()).count(any());
        assertThat(result.id()).isEqualTo(CONNECTION_ID);
        assertThat(result.active()).isTrue();
    }

    @Test
    void authorizeSealsTheSecretWithConnectionAndTenantBoundAad() {
        when(repository.findById(TENANT_ID, CONNECTION_ID)).thenReturn(Optional.of(row(true, false)));

        service.authorize(TENANT_ID, ADMIN_ID, CONNECTION_ID, SECRET);

        ArgumentCaptor<byte[]> sealed = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> keyId = ArgumentCaptor.forClass(String.class);
        verify(repository).updateCredential(eq(TENANT_ID), eq(CONNECTION_ID), sealed.capture(), keyId.capture(),
                eq(ADMIN_ID), eq(NOW));
        assertThat(new String(sealed.getValue(), StandardCharsets.UTF_8)).doesNotContain(SECRET);
        CredentialCipher cipher = new CredentialCipher(MASTER_KEY, List.of());
        assertThat(keyId.getValue()).isEqualTo(cipher.keyId());
        // 用同一把主密钥与同一份 AAD 能解回原文，证明落库的确实是这条连接/租户的密文。
        assertThat(new String(cipher.open(sealed.getValue(), CredentialCipher.aad(CONNECTION_ID, TENANT_ID),
                keyId.getValue()), StandardCharsets.UTF_8)).isEqualTo(SECRET);
        AdminAuditEntry entry = captureAudit();
        assertThat(entry.eventType()).isEqualTo(ModelConnectionService.EVENT_CREDENTIAL_SET);
        assertThat(entry.payload().toString()).doesNotContain(SECRET);
    }

    @Test
    void authorizeRefusesWhenTheMasterKeyIsMissingOrMalformed() {
        when(repository.findById(TENANT_ID, CONNECTION_ID)).thenReturn(Optional.of(row(true, false)));
        properties.setMasterKey("");
        assertThatThrownBy(() -> service.authorize(TENANT_ID, ADMIN_ID, CONNECTION_ID, SECRET))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_INTERNAL_001));

        properties.setMasterKey(base64Of(new byte[16]));
        assertThatThrownBy(() -> service.authorize(TENANT_ID, ADMIN_ID, CONNECTION_ID, SECRET))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_INTERNAL_001));
        verify(repository, never()).updateCredential(any(), any(), any(), any(), any(), any());
    }

    @Test
    void probeDecryptsServerSideAndRecordsTheOutcome() {
        CredentialCipher cipher = new CredentialCipher(MASTER_KEY, List.of());
        byte[] sealed = cipher.seal(SECRET.getBytes(StandardCharsets.UTF_8),
                CredentialCipher.aad(CONNECTION_ID, TENANT_ID));
        when(repository.findById(TENANT_ID, CONNECTION_ID))
                .thenReturn(Optional.of(rowWithCredential(sealed, cipher.keyId())));
        when(probeClient.probe(BASE_URL, SECRET)).thenReturn(ModelProbeData.success(List.of("deepseek-chat")));

        ModelProbeData result = service.probe(TENANT_ID, ADMIN_ID, CONNECTION_ID);

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("deepseek-chat");
        verify(repository).updateProbeStatus(TENANT_ID, CONNECTION_ID, "OK", NOW, NOW);
        assertThat(captureAudit().eventType()).isEqualTo(ModelConnectionService.EVENT_PROBED);
    }

    /** 主密钥轮换后旧密钥没保留：必须明确报「重新录入」，绝不能静默退回离线。 */
    @Test
    void probeReportsUnreadableCredentialWithoutTouchingTheNetwork() {
        byte[] sealed = new CredentialCipher(MASTER_KEY, List.of())
                .seal(SECRET.getBytes(StandardCharsets.UTF_8), CredentialCipher.aad(CONNECTION_ID, TENANT_ID));
        when(repository.findById(TENANT_ID, CONNECTION_ID))
                .thenReturn(Optional.of(rowWithCredential(sealed, "ffffffffffff")));

        ModelProbeData result = service.probe(TENANT_ID, ADMIN_ID, CONNECTION_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.failureReason()).isEqualTo(ModelProbeFailure.CREDENTIAL_UNREADABLE);
        verify(probeClient, never()).probe(any(), any());
        verify(repository).updateProbeStatus(TENANT_ID, CONNECTION_ID, "FAILED", NOW, NOW);
    }

    @Test
    void probeWithoutCredentialKeepsTheNeverProbedState() {
        when(repository.findById(TENANT_ID, CONNECTION_ID)).thenReturn(Optional.of(row(true, false)));
        when(probeClient.probe(BASE_URL, null))
                .thenReturn(ModelProbeData.failure(ModelProbeFailure.CREDENTIAL_MISSING, "这条连接还没有保存 API Key"));

        ModelProbeData result = service.probe(TENANT_ID, ADMIN_ID, CONNECTION_ID);

        assertThat(result.failureReason()).isEqualTo(ModelProbeFailure.CREDENTIAL_MISSING);
        verify(repository, never()).updateProbeStatus(any(), any(), any(), any(), any());
    }

    @Test
    void activationClearsTheOldSlotBeforeSettingTheNewOne() {
        when(repository.findById(TENANT_ID, CONNECTION_ID)).thenReturn(Optional.of(row(true, false)));

        service.setActivation(TENANT_ID, ADMIN_ID, CONNECTION_ID, true);

        InOrder order = inOrder(repository);
        order.verify(repository).clearActive(TENANT_ID, ADMIN_ID, NOW);
        order.verify(repository).markActive(TENANT_ID, CONNECTION_ID, ADMIN_ID, NOW);
        assertThat(captureAudit().eventType()).isEqualTo(ModelConnectionService.EVENT_ACTIVATED);
    }

    @Test
    void activationRefusesDisabledConnections() {
        when(repository.findById(TENANT_ID, CONNECTION_ID)).thenReturn(Optional.of(row(false, false)));

        assertThatThrownBy(() -> service.setActivation(TENANT_ID, ADMIN_ID, CONNECTION_ID, true))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001));
        verify(repository, never()).markActive(any(), any(), any(), any());
        verify(repository, never()).clearActive(any(), any(), any());
    }

    /** 停用一条连接不得把租户内其它连接的生效标记一起清掉。 */
    @Test
    void deactivationOnlyTouchesItsOwnRow() {
        when(repository.findById(TENANT_ID, CONNECTION_ID)).thenReturn(Optional.of(row(true, true)));

        service.setActivation(TENANT_ID, ADMIN_ID, CONNECTION_ID, false);

        verify(repository).deactivate(TENANT_ID, CONNECTION_ID, ADMIN_ID, NOW);
        verify(repository, never()).clearActive(any(), any(), any());
        assertThat(captureAudit().eventType()).isEqualTo(ModelConnectionService.EVENT_DEACTIVATED);
    }

    @Test
    void deleteRemovesTheRowAndClearsTheDistributionKeyWhenNothingIsActive() {
        when(repository.findById(TENANT_ID, CONNECTION_ID)).thenReturn(Optional.of(row(true, true)));
        when(repository.findActive(TENANT_ID)).thenReturn(Optional.empty());

        service.delete(TENANT_ID, ADMIN_ID, CONNECTION_ID);

        verify(repository).delete(TENANT_ID, CONNECTION_ID);
        verify(publisher).publish(TENANT_ID, null);
        assertThat(captureAudit().eventType()).isEqualTo(ModelConnectionService.EVENT_DELETED);
    }

    @Test
    void everyOperationRefusesConnectionsThatOnlyExistInAnotherTenant() {
        when(repository.findById(FOREIGN_TENANT_ID, CONNECTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authorize(FOREIGN_TENANT_ID, ADMIN_ID, CONNECTION_ID, SECRET))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_NOT_FOUND_001));
        assertThatThrownBy(() -> service.probe(FOREIGN_TENANT_ID, ADMIN_ID, CONNECTION_ID))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setActivation(FOREIGN_TENANT_ID, ADMIN_ID, CONNECTION_ID, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(FOREIGN_TENANT_ID, ADMIN_ID, CONNECTION_ID))
                .isInstanceOf(BusinessException.class);
        verify(auditRepository, never()).insert(any(), any());
    }

    @Test
    void auditFailuresPropagateSoTheWriteRollsBackWithTheAudit() {
        when(repository.findByName(TENANT_ID, "DeepSeek"))
                .thenReturn(Optional.empty(), Optional.of(row(true, false)));
        when(repository.count(TENANT_ID)).thenReturn(0);
        doThrow(new IllegalStateException("audit insert failed"))
                .when(auditRepository).insert(any(), any(AdminAuditEntry.class));

        assertThatThrownBy(() -> service.upsert(TENANT_ID, ADMIN_ID,
                new CreateModelConnectionRequest("DeepSeek", null, BASE_URL, "deepseek-chat", null)))
                .isInstanceOf(IllegalStateException.class);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void startupRepublishDelegatesToThePublisher() {
        List<ModelConnectionRow> active = List.of(row(true, true));
        when(repository.findAllActive()).thenReturn(active);

        service.republishActiveConnections();

        verify(publisher).republishAll(active);
    }

    private AdminAuditEntry captureAudit() {
        ArgumentCaptor<AdminAuditEntry> captor = ArgumentCaptor.forClass(AdminAuditEntry.class);
        verify(auditRepository, atLeastOnce()).insert(eq(TENANT_ID), captor.capture());
        return captor.getValue();
    }

    private static ModelConnectionRow row(boolean enabled, boolean active) {
        return new ModelConnectionRow(CONNECTION_ID, TENANT_ID, "DeepSeek", "deepseek", BASE_URL, "deepseek-chat",
                enabled, active, null, null, "UNKNOWN", null, NOW, NOW, ADMIN_ID);
    }

    private static ModelConnectionRow rowWithCredential(byte[] credential, String keyId) {
        return new ModelConnectionRow(CONNECTION_ID, TENANT_ID, "DeepSeek", "deepseek", BASE_URL, "deepseek-chat",
                true, false, credential, keyId, "UNKNOWN", null, NOW, NOW, ADMIN_ID);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64Of(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
