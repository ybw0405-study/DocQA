package com.docqa.document.service;

import com.docqa.document.dto.UploadInitData;

public record UploadInitResult(UploadInitData data, boolean created) {
}
