package com.docqa.document.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class DocumentStateMachine {

    private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED = Map.of(
            DocumentStatus.UPLOADED, EnumSet.of(DocumentStatus.PARSING),
            DocumentStatus.PARSING, EnumSet.of(
                    DocumentStatus.PARSING, DocumentStatus.INDEXED, DocumentStatus.FAILED, DocumentStatus.DEAD),
            DocumentStatus.FAILED, EnumSet.of(DocumentStatus.PARSING, DocumentStatus.DEAD),
            DocumentStatus.INDEXED, EnumSet.noneOf(DocumentStatus.class),
            DocumentStatus.DEAD, EnumSet.of(DocumentStatus.PARSING));

    private DocumentStateMachine() {
    }

    public static boolean canTransition(DocumentStatus from, DocumentStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(DocumentStatus from, DocumentStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal document transition: " + from + " -> " + to);
        }
    }
}
