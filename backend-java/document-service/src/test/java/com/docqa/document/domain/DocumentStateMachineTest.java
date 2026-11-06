package com.docqa.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentStateMachineTest {

    @Test
    void uploadedCanOnlyMoveToParsing() {
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.UPLOADED, DocumentStatus.PARSING)).isTrue();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.UPLOADED, DocumentStatus.INDEXED)).isFalse();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.UPLOADED, DocumentStatus.FAILED)).isFalse();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.UPLOADED, DocumentStatus.DEAD)).isFalse();
    }

    @Test
    void parsingCanMoveToTerminalOrFailed() {
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.PARSING, DocumentStatus.INDEXED)).isTrue();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.PARSING, DocumentStatus.FAILED)).isTrue();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.PARSING, DocumentStatus.DEAD)).isTrue();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.PARSING, DocumentStatus.UPLOADED)).isFalse();
    }

    @Test
    void aRepublishedRetryKeepsTheDocumentParsing() {
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.PARSING, DocumentStatus.PARSING)).isTrue();
    }

    @Test
    void failedCanRetryOrBecomeDead() {
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.FAILED, DocumentStatus.PARSING)).isTrue();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.FAILED, DocumentStatus.DEAD)).isTrue();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.FAILED, DocumentStatus.INDEXED)).isFalse();
    }

    @Test
    void indexedIsTheOnlyReallyTerminalState() {
        for (DocumentStatus target : DocumentStatus.values()) {
            assertThat(DocumentStateMachine.canTransition(DocumentStatus.INDEXED, target)).isFalse();
        }
    }

    @Test
    void deadDocumentsReopenOnlyThroughAManualRetry() {
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.DEAD, DocumentStatus.PARSING)).isTrue();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.DEAD, DocumentStatus.INDEXED)).isFalse();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.DEAD, DocumentStatus.FAILED)).isFalse();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.DEAD, DocumentStatus.DEAD)).isFalse();
        assertThat(DocumentStateMachine.canTransition(DocumentStatus.DEAD, DocumentStatus.UPLOADED)).isFalse();
    }

    @Test
    void requireTransitionRejectsIllegalTransition() {
        assertThatThrownBy(() -> DocumentStateMachine.requireTransition(
                        DocumentStatus.INDEXED, DocumentStatus.PARSING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INDEXED -> PARSING");
    }
}
