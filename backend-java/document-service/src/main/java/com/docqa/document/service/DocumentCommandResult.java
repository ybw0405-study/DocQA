package com.docqa.document.service;

import com.docqa.document.dto.DocumentStatusData;

public record DocumentCommandResult(DocumentStatusData data, boolean accepted) {
}
