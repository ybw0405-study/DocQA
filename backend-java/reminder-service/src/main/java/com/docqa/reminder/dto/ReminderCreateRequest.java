package com.docqa.reminder.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.docqa.reminder.domain.ReminderKind;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = DeadlineReminderCreateRequest.class, name = "DEADLINE"),
        @JsonSubTypes.Type(value = CustomReminderCreateRequest.class, name = "CUSTOM")
})
public sealed interface ReminderCreateRequest
        permits DeadlineReminderCreateRequest, CustomReminderCreateRequest {

    ReminderKind kind();

    String title();

    String description();

    String timezone();
}
