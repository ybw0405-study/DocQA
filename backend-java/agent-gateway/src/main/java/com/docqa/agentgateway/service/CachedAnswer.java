package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.Citation;
import java.util.List;

public record CachedAnswer(String answer, List<Citation> citations, double confidence) {
}
