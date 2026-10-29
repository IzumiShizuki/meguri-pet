package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresTurnJournalSessionScopeTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sessionPollReadsOnlyLifecycleColumnsWithBoundSessionAndOptionalIdentity(boolean scoped) {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                assertThat(sql).startsWith("SELECT turn_id, status FROM turn_runtime WHERE session_id = ?")
                        .doesNotContain("request_json", "result_json", "manifest_json");
                if (scoped) {
                    assertThat(sql).endsWith(" AND user_id = ? AND client_id = ?");
                    assertThat(args).containsExactly("isolated-session", "fictional-user", "website");
                } else {
                    assertThat(sql).doesNotContain("user_id", "client_id");
                    assertThat(args).containsExactly("isolated-session");
                }
                try {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("turn_id")).thenReturn("visible-turn");
                    when(row.getString("status")).thenReturn("running");
                    return List.of(mapper.mapRow(row, 0));
                } catch (SQLException error) {
                    throw new AssertionError(error);
                }
            }
        };
        var journal = new PostgresTurnJournal(jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(TransactionTemplate.class), "fixture", false, false);
        assertThat(journal.sessionTurnStatuses("isolated-session",
                scoped ? "fictional-user" : null, scoped ? "website" : null))
                .containsOnlyKeys("visible-turn")
                .containsEntry("visible-turn", TurnStatus.RUNNING);
    }
}
