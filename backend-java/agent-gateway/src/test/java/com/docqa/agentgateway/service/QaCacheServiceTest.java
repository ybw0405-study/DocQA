package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import com.docqa.agentgateway.dto.Citation;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class QaCacheServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private QaCacheService service;
    private PythonProperties pythonProperties;

    @BeforeEach
    void setUp() {
        pythonProperties = new PythonProperties();
        service = new QaCacheService(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                new AgentProperties(),
                pythonProperties,
                new PiiMasker());
    }

    @Test
    void returnsEmptyWhenKeyIsMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(service.find(USER_ID, documents(), List.of(), "问题")).isEmpty();
    }

    @Test
    void discardsMalformedEntry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("{not-json");

        assertThat(service.find(USER_ID, documents(), List.of(), "问题")).isEmpty();
    }

    @Test
    void readsSerializedAnswer() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(
                "{\"answer\":\"答案\",\"citations\":[],\"confidence\":0.9}");

        Optional<CachedAnswer> cached = service.find(USER_ID, documents(), List.of(), "问题");

        assertThat(cached).isPresent();
        assertThat(cached.orElseThrow().answer()).isEqualTo("答案");
    }

    @Test
    void writesWithConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AgentProperties properties = new AgentProperties();
        properties.setQaCacheTtl(Duration.ofMinutes(3));
        QaCacheService cache = new QaCacheService(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                pythonProperties,
                new PiiMasker());

        cache.put(USER_ID, documents(), List.of(), "问题", new CachedAnswer("答案", List.of(), 0.9));

        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofMinutes(3)));
    }

    @Test
    void cacheKeyIgnoresDocumentOrder() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        service.find(USER_ID,
                List.of(new AuthorizedDocument(DOCUMENT_ID, "v1"), new AuthorizedDocument(OTHER_ID, "v1")),
                List.of(), "问题");
        service.find(USER_ID,
                List.of(new AuthorizedDocument(OTHER_ID, "v1"), new AuthorizedDocument(DOCUMENT_ID, "v1")),
                List.of(), "问题");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, org.mockito.Mockito.times(2)).get(keys.capture());
        List<String> captured = keys.getAllValues();
        assertThat(captured.get(0)).isEqualTo(captured.get(1));
    }

    @Test
    void cacheKeyIncludesPromptVersion() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        service.find(USER_ID, documents(), List.of(), "问题");
        pythonProperties.setPromptVersion("p2");
        service.find(USER_ID, documents(), List.of(), "问题");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, org.mockito.Mockito.times(2)).get(keys.capture());
        assertThat(keys.getAllValues().get(0)).endsWith(":p1");
        assertThat(keys.getAllValues().get(1)).endsWith(":p2");
    }

    private List<AuthorizedDocument> documents() {
        return List.of(new AuthorizedDocument(DOCUMENT_ID, "v1"));
    }

    @Test
    void cachesCitations() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        CachedAnswer answer = new CachedAnswer(
                "答案", List.of(new Citation(DOCUMENT_ID, 0, 0.9, "片段")), 0.9);

        service.put(USER_ID, documents(), List.of(), "问题", answer);

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }
}
