package com.docqa.reminder.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class RocketMqPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsTopicNamesWithDots() {
        RocketMqProperties properties = validProperties();
        properties.setDueTopic("reminder.due");

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("dueTopic");
    }

    @Test
    void acceptsHyphenatedTopicNames() {
        assertThat(validator.validate(validProperties())).isEmpty();
    }

    @Test
    void rejectsABlankConsumerGroupBecauseTheConsumerCannotStartWithoutOne() {
        RocketMqProperties properties = validProperties();
        properties.setConsumerGroup(" ");

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("consumerGroup");
    }

    @Test
    void rejectsAnInvertedConsumerThreadRange() {
        // 两个 @Min(1) 各自合法，区间本身却非法：@AssertTrue 是唯一的判据所在。
        RocketMqProperties properties = validProperties();
        properties.setConsumeThreadMin(8);
        properties.setConsumeThreadMax(2);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("consumeThreadRangeValid");
    }

    @Test
    void rejectsAZeroSizedConsumerThreadBound() {
        RocketMqProperties properties = validProperties();
        properties.setConsumeThreadMin(0);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("consumeThreadMin");
    }

    @Test
    void rejectsADeadLetterThresholdAboveTheClientSideCeiling() {
        RocketMqProperties properties = validProperties();
        properties.setMaxReconsumeTimes(33);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("maxReconsumeTimes");
    }

    private RocketMqProperties validProperties() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNamesrv("127.0.0.1:9876");
        properties.setProducerGroup("reminder-service-producer");
        properties.setDueTopic("reminder-due");
        properties.setConsumerGroup("reminder-service-reminder-due");
        return properties;
    }
}
