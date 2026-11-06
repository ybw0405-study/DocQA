package com.docqa.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class RocketMqPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsTopicNamesWithDots() {
        RocketMqProperties properties = validProperties();
        properties.setParseRequestedTopic("parse.requested");

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("parseRequestedTopic");
    }

    @Test
    void acceptsHyphenatedTopicNames() {
        assertThat(validator.validate(validProperties())).isEmpty();
    }

    @Test
    void rejectsVectorChangeTopicsWithDots() {
        RocketMqProperties properties = validProperties();
        properties.setVectorChangeRequestedTopic("vector.change.requested");

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("vectorChangeRequestedTopic");
    }

    @Test
    void requiresTheVectorResultConsumerGroup() {
        RocketMqProperties properties = validProperties();
        properties.setVectorResultConsumerGroup(null);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("vectorResultConsumerGroup");
    }

    private RocketMqProperties validProperties() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNamesrv("127.0.0.1:9876");
        properties.setProducerGroup("document-service-producer");
        properties.setConsumerGroup("document-service-parse-result");
        properties.setParseRequestedTopic("parse-requested");
        properties.setParseCompletedTopic("parse-completed");
        properties.setParseFailedTopic("parse-failed");
        properties.setVectorChangeRequestedTopic("vector-change-requested");
        properties.setVectorChangeCompletedTopic("vector-change-completed");
        properties.setVectorResultConsumerGroup("document-service-vector-result");
        return properties;
    }
}
