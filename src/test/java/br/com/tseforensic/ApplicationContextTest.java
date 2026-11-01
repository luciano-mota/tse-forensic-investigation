package br.com.tseforensic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:forensic_ci;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "FORENSIC_IMPORT_PATH=",
        "FORENSIC_TURNOUT_PATH=",
        "FORENSIC_SECTION_VOTES_PATH=",
        "FORENSIC_BIOMETRIC_PATH=",
        "FORENSIC_BWEB_PATH="
})
class ApplicationContextTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsAllAuditTablesWithFlywayOnStartup() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM section_turnout", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM section_biometric", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM section_vote", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bweb_biometric_nonrecognition", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM section_log_audit", Long.class)).isZero();
    }
}
