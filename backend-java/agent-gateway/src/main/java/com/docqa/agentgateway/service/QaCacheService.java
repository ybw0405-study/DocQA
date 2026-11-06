package com.docqa.agentgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class QaCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QaCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final PythonProperties pythonProperties;
    private final PiiMasker piiMasker;

    public QaCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AgentProperties agentProperties,
            PythonProperties pythonProperties,
            PiiMasker piiMasker) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
        this.pythonProperties = pythonProperties;
        this.piiMasker = piiMasker;
    }

    public Optional<CachedAnswer> find(
            UUID userId,
            List<AuthorizedDocument> documents,
            List<PythonHistoryMessageMask> history,
            String piiMaskedQuestion) {
        String value = redisTemplate.opsForValue().get(key(userId, documents, history, piiMaskedQuestion));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, CachedAnswer.class));
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Discarding malformed QA cache entry");
            return Optional.empty();
        }
    }

    public void put(
            UUID userId,
            List<AuthorizedDocument> documents,
            List<PythonHistoryMessageMask> history,
            String piiMaskedQuestion,
            CachedAnswer answer) {
        try {
            redisTemplate.opsForValue().set(
                    key(userId, documents, history, piiMaskedQuestion),
                    objectMapper.writeValueAsString(answer),
                    agentProperties.getQaCacheTtl());
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Unable to serialize QA cache entry");
        }
    }

    private String key(
            UUID userId,
            List<AuthorizedDocument> documents,
            List<PythonHistoryMessageMask> history,
            String piiMaskedQuestion) {
        String documentScope = documents.stream()
                .sorted(Comparator.comparing(AuthorizedDocument::documentId))
                .map(document -> document.documentId() + ":" + document.indexVersion())
                .collect(Collectors.joining(","));
        String historyValue = history.stream()
                .map(message -> message.role() + ":" + message.piiMaskedContent())
                .collect(Collectors.joining("\n"));
        String normalizedQuestion = piiMasker.normalize(piiMaskedQuestion).trim().replaceAll("\\s+", " ");
        return "qa:" + userId
                + ":" + Hashing.sha256(documentScope)
                + ":" + Hashing.sha256(historyValue)
                + ":" + Hashing.sha256(normalizedQuestion)
                + ":" + pythonProperties.getPromptVersion();
    }

    public record PythonHistoryMessageMask(String role, String piiMaskedContent) {
    }
}
