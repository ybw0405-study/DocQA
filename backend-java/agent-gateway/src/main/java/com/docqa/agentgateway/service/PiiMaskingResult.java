package com.docqa.agentgateway.service;

import java.util.List;

public record PiiMaskingResult(String masked, List<String> categories, int count) {
}
