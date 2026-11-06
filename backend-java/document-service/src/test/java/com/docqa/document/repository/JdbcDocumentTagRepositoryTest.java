package com.docqa.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.document.support.TagNormalizer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcDocumentTagRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID EXISTING_TAG_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-10-20T10:15:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcDocumentTagRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcDocumentTagRepository(jdbcTemplate);
    }

    @Test
    void resolvingAnEmptyTagListDoesNotTouchTheDatabase() {
        assertThat(repository.resolveOrCreate(USER_ID, List.of(), NOW)).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void theExistingTagLookupIsScopedToTheOwningUserAndBindsNormalizedNames() {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(tagRow("考研 数学", EXISTING_TAG_ID));
            return null;
        }).when(jdbcTemplate).query(
                anyString(), any(RowCallbackHandler.class),
                eq(USER_ID.toString()), eq("考研 数学"), eq("错题本"));

        Map<String, UUID> resolved = repository.resolveOrCreate(
                USER_ID,
                List.of(TagNormalizer.of("考研 数学"), TagNormalizer.of("错题本")),
                NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(), any(RowCallbackHandler.class),
                eq(USER_ID.toString()), eq("考研 数学"), eq("错题本"));
        assertThat(sql.getValue()).contains("FROM document_tag WHERE user_id = ?");
        assertThat(sql.getValue()).contains("normalized_name IN (?, ?)");
        assertThat(resolved).containsEntry("考研 数学", EXISTING_TAG_ID);
        assertThat(resolved).containsKey("错题本");
        assertThat(resolved.get("错题本")).isNotEqualTo(EXISTING_TAG_ID);
    }

    @Test
    void onlyTheMissingTagNameIsInserted() throws SQLException {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(tagRow("考研 数学", EXISTING_TAG_ID));
            return null;
        }).when(jdbcTemplate).query(
                anyString(), any(RowCallbackHandler.class), eq(USER_ID.toString()), anyString(), anyString());

        repository.resolveOrCreate(
                USER_ID,
                List.of(TagNormalizer.of("考研 数学"), TagNormalizer.of("错题本")),
                NOW);

        ArgumentCaptor<String> insertSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(insertSql.capture(), setter.capture());
        assertThat(insertSql.getValue()).contains("INSERT INTO document_tag (id, user_id, name, normalized_name, created_at)");
        assertThat(setter.getValue().getBatchSize()).isEqualTo(1);

        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement, 0);
        verify(statement).setString(eq(2), eq(USER_ID.toString()));
        verify(statement).setString(eq(3), eq("错题本"));
        verify(statement).setString(eq(4), eq("错题本"));
        verify(statement).setTimestamp(eq(5), eq(Timestamp.from(NOW)));
    }

    @Test
    void aConcurrentInsertIsResolvedByRereadingTheOwnersRows() {
        doAnswer(invocation -> null)
                .doAnswer(invocation -> {
                    RowCallbackHandler handler = invocation.getArgument(1);
                    handler.processRow(tagRow("错题本", EXISTING_TAG_ID));
                    return null;
                })
                .when(jdbcTemplate)
                .query(anyString(), any(RowCallbackHandler.class), eq(USER_ID.toString()), anyString());
        doThrow(new DuplicateKeyException("uk_user_tag"))
                .when(jdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        Map<String, UUID> resolved = repository.resolveOrCreate(
                USER_ID, List.of(TagNormalizer.of("错题本")), NOW);

        assertThat(resolved).containsEntry("错题本", EXISTING_TAG_ID);
    }

    @Test
    void replacingLinksDeletesFirstAndInsertsEveryLinkWithOneTimestamp() throws SQLException {
        UUID first = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

        repository.replaceLinks(DOCUMENT_ID, List.of(first, second), NOW);

        verify(jdbcTemplate).update(
                "DELETE FROM document_tag_link WHERE document_id = ?", DOCUMENT_ID.toString());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), setter.capture());
        assertThat(sql.getValue())
                .isEqualTo("INSERT INTO document_tag_link (document_id, tag_id, created_at) VALUES (?, ?, ?)");
        assertThat(setter.getValue().getBatchSize()).isEqualTo(2);

        PreparedStatement secondStatement = mock(PreparedStatement.class);
        setter.getValue().setValues(secondStatement, 1);
        verify(secondStatement).setString(1, DOCUMENT_ID.toString());
        verify(secondStatement).setString(2, second.toString());
        verify(secondStatement).setTimestamp(3, Timestamp.from(NOW));
    }

    @Test
    void anEmptyLinkListOnlyClearsTheExistingLinks() {
        repository.replaceLinks(DOCUMENT_ID, List.of(), NOW);

        verify(jdbcTemplate).update(
                "DELETE FROM document_tag_link WHERE document_id = ?", DOCUMENT_ID.toString());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void tagNamesAreReadInCreationOrderAndMappedFromTheNameColumn() throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<String>> mapper = mapperCaptor();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("name")).thenReturn("考研数学");
        when(jdbcTemplate.query(sql.capture(), mapper.capture(), eq(DOCUMENT_ID.toString())))
                .thenReturn(List.of("考研数学"));

        assertThat(repository.findNames(DOCUMENT_ID)).containsExactly("考研数学");

        assertThat(sql.getValue()).contains("FROM document_tag_link l");
        assertThat(sql.getValue()).contains("WHERE l.document_id = ?");
        assertThat(sql.getValue()).contains("ORDER BY t.created_at, t.id");
        assertThat(sql.getValue()).doesNotContain("ORDER BY t.name");
        assertThat(mapper.getValue().mapRow(resultSet, 0)).isEqualTo("考研数学");
    }

    @Test
    void theBatchedTagReadBindsOnePlaceholderPerDocumentAndGroupsByDocument() {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(linkRow(DOCUMENT_ID, "考研数学"));
            handler.processRow(linkRow(OTHER_DOCUMENT_ID, "错题本"));
            handler.processRow(linkRow(DOCUMENT_ID, "错题本"));
            return null;
        }).when(jdbcTemplate).query(
                anyString(), any(RowCallbackHandler.class),
                eq(DOCUMENT_ID.toString()), eq(OTHER_DOCUMENT_ID.toString()));

        Map<UUID, List<String>> names = repository.findNamesByDocuments(List.of(DOCUMENT_ID, OTHER_DOCUMENT_ID));

        assertThat(names.get(DOCUMENT_ID)).containsExactly("考研数学", "错题本");
        assertThat(names.get(OTHER_DOCUMENT_ID)).containsExactly("错题本");
    }

    @Test
    void theBatchedTagReadShortCircuitsForAnEmptyPage() {
        assertThat(repository.findNamesByDocuments(List.of())).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    private static ResultSet tagRow(String normalized, UUID id) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("normalized_name")).thenReturn(normalized);
        when(resultSet.getString("id")).thenReturn(id.toString());
        return resultSet;
    }

    private static ResultSet linkRow(UUID documentId, String name) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("document_id")).thenReturn(documentId.toString());
        when(resultSet.getString("name")).thenReturn(name);
        return resultSet;
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<RowMapper<T>> mapperCaptor() {
        return ArgumentCaptor.forClass(RowMapper.class);
    }
}
