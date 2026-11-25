package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.config.TenantBootstrapProperties;
import com.docqa.user.dto.CreateTenantRequest;
import com.docqa.user.dto.TenantCreatedData;
import com.docqa.user.repository.TenantRepository;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantProvisioningServiceTest {

    private static final String TOKEN = "bootstrap-secret-with-enough-entropy";

    private FakeTenantRepository repository;
    private TenantBootstrapProperties properties;
    private TenantProvisioningService service;

    @BeforeEach
    void setUp() {
        repository = new FakeTenantRepository();
        properties = new TenantBootstrapProperties();
        properties.setToken(TOKEN);
        service = new TenantProvisioningService(repository, properties);
    }

    @Test
    void createsTenantWithNormalizedSlugAndActiveStatus() {
        TenantCreatedData created = service.create(TOKEN, request(" Acme-Corp "));

        assertThat(created.slug()).isEqualTo("acme-corp");
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.id()).isNotNull();
        assertThat(repository.bySlug).containsKey("acme-corp");
    }

    @Test
    void replayingSameSlugReturnsExistingIdWithoutCreatingDuplicate() {
        TenantCreatedData first = service.create(TOKEN, request("acme"));
        TenantCreatedData again = service.create(TOKEN, request("acme"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(repository.bySlug).hasSize(1);
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> service.create(null, request("acme")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USR_FORBIDDEN_001));
    }

    @Test
    void rejectsWrongToken() {
        assertThatThrownBy(() -> service.create("wrong-token", request("acme")))
                .isInstanceOf(BusinessException.class);
        assertThat(repository.bySlug).isEmpty();
    }

    @Test
    void disabledWhenTokenIsBlank() {
        properties.setToken(" ");

        assertThatThrownBy(() -> service.create(" ", request("acme")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USR_FORBIDDEN_001));
        assertThat(repository.bySlug).isEmpty();
    }

    private static CreateTenantRequest request(String slug) {
        return new CreateTenantRequest(slug, "Acme Inc", "STANDARD", "SHARED");
    }

    /** 轻量假仓储：只实现供给端点用到的 insert / findBySlug 语义。 */
    private static final class FakeTenantRepository implements TenantRepository {

        private final Map<String, UUID> bySlug = new LinkedHashMap<>();

        @Override
        public Optional<com.docqa.user.domain.TenantRecord> findBySlug(String slug) {
            UUID id = bySlug.get(slug.toLowerCase(Locale.ROOT));
            return id == null
                    ? Optional.empty()
                    : Optional.of(new com.docqa.user.domain.TenantRecord(id, slug, "ACTIVE"));
        }

        @Override
        public int insert(UUID id, String slug, String name, String tier, String isolation) {
            String key = slug.toLowerCase(Locale.ROOT);
            if (bySlug.containsKey(key)) {
                return 0;
            }
            bySlug.put(key, id);
            return 1;
        }
    }
}
