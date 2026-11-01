package br.com.tseforensic.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParticipationAuditServiceTest {

    @Test
    void missingTurnoutDataNeverBecomesFraudClaim() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenAnswer(call -> {
            org.springframework.jdbc.core.RowMapper<?> mapper = call.getArgument(1);
            ResultSet result = mock(ResultSet.class);
            when(result.getLong(anyString())).thenReturn(0L);
            return mapper.mapRow(result,0);
        });
        var result = new ParticipationAuditService(jdbc).compare(null,null);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_COVERAGE");
        assertThat(result.abstentionChangePp()).isNull();
    }
}
