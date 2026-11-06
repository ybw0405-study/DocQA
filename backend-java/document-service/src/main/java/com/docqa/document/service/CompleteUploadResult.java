package com.docqa.document.service;

import com.docqa.document.dto.CompleteUploadData;

public record CompleteUploadResult(CompleteUploadData data, boolean accepted) {
}
