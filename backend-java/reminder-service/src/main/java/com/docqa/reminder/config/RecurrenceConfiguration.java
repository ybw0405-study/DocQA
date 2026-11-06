package com.docqa.reminder.config;

import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.RruleParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RecurrenceConfiguration {

    @Bean
    public RruleParser rruleParser() {
        return new RruleParser();
    }

    @Bean
    public RecurrenceCalculator recurrenceCalculator() {
        return new RecurrenceCalculator();
    }
}
