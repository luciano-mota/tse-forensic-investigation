package br.com.tseforensic.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ElectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ElectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<VoteRow> votes(int round, String uf) {
        String filter = uf == null ? "" : " AND uf = ?";
        String sql = """
                SELECT uf, municipality_code, MAX(municipality) AS municipality,
                       candidate_number, MAX(candidate_name) AS candidate_name,
                       SUM(votes) AS votes
                  FROM election_vote
                 WHERE election_year = 2026 AND election_round = ?
                   AND candidate_number NOT IN (95, 96)
                """ + filter + """
                 GROUP BY uf, municipality_code, candidate_number
                 ORDER BY uf, municipality_code, candidate_number
                """;
        Object[] args = uf == null ? new Object[]{round} : new Object[]{round, uf};
        return jdbcTemplate.query(sql, (rs, index) -> new VoteRow(
                rs.getString("uf"),
                rs.getString("municipality_code"),
                rs.getString("municipality"),
                rs.getInt("candidate_number"),
                rs.getString("candidate_name"),
                rs.getLong("votes")
        ), args);
    }

    public List<SourceRow> sources() {
        return jdbcTemplate.query("""
                SELECT sha256, filename, row_count, created_at
                  FROM import_batch ORDER BY created_at DESC
                """, (rs, index) -> new SourceRow(
                rs.getString("sha256"),
                rs.getString("filename"),
                rs.getLong("row_count"),
                rs.getTimestamp("created_at").toInstant().toString()
        ));
    }

    public record VoteRow(String uf, String municipalityCode, String municipality,
                          int candidateNumber, String candidateName, long votes) {
    }

    public record SourceRow(String sha256, String filename, long rows, String importedAt) {
    }
}
