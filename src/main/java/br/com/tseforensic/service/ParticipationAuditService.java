package br.com.tseforensic.service;

import br.com.tseforensic.domain.AuditMath;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class ParticipationAuditService {

    private final JdbcTemplate jdbc;

    public ParticipationAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ParticipationSummary turnout(int round, String uf, String municipality) {
        String filter = regionFilter(uf, municipality);
        Object[] args = parameters(round, uf, municipality);
        return jdbc.queryForObject("""
          SELECT COUNT(*) AS sections, COALESCE(SUM(registered),0) registered,
                 COALESCE(SUM(turnout),0) turnout, COALESCE(SUM(abstentions),0) abstentions,
                 COUNT(*) FILTER (WHERE registered <> turnout + abstentions) AS arithmetic_mismatch
          FROM section_turnout WHERE election_round = ?
          """ + filter, (rs, i) -> {
            long sections = rs.getLong("sections");
            long apt = rs.getLong("registered");
            long present = rs.getLong("turnout");
            long absent = rs.getLong("abstentions");
            return new ParticipationSummary(round, uf, municipality, sections, apt, present, absent,
                    AuditMath.percent(absent, apt), rs.getLong("arithmetic_mismatch"),
                    sections > 0 ? "DATA_IMPORTED" : "NO_DATA");
        }, args);
    }

    public TurnoutComparison compare(String uf, String municipality) {
        ParticipationSummary first = turnout(1, uf, municipality);
        ParticipationSummary second = turnout(2, uf, municipality);
        String status = first.sections == 0 || second.sections == 0
                ? "INSUFFICIENT_COVERAGE" : "DESCRIPTIVE_ONLY";
        BigDecimal delta = status.equals("DESCRIPTIVE_ONLY")
                ? second.abstentionPercentage.subtract(first.abstentionPercentage)
                : null;
        return new TurnoutComparison(status, first, second, delta,
                "Abstenção é diferente de votos válidos; verificar diferenças de eleitorado, transferências e universo de seções.");
    }

    public List<SectionTurnout> sections(int round, String uf, String municipality,
                                        int limit, int offset) {
        if (uf == null) {
            throw new IllegalArgumentException("uf obrigatória na listagem de seções");
        }
        String filter = municipality == null ? "" : " AND municipality_code = ?";
        Object[] params = municipality == null
                ? new Object[]{round, uf, limit, offset}
                : new Object[]{round, uf, municipality, limit, offset};
        return jdbc.query("""
          SELECT municipality_code, municipality, zone, section,
                 registered, turnout, abstentions, source_sha256
          FROM section_turnout WHERE election_round = ? AND uf = ?
          """ + filter + """
          ORDER BY municipality, zone, section LIMIT ? OFFSET ?
          """, (rs, i) -> new SectionTurnout(rs.getString("municipality_code"),
                rs.getString("municipality"), rs.getInt("zone"), rs.getInt("section"),
                rs.getLong("registered"), rs.getLong("turnout"), rs.getLong("abstentions"),
                rs.getString("source_sha256")), params);
    }

    public BiometricSummary biometrics(int round, String uf, String municipality) {
        String filter = regionFilter(uf, municipality);
        Object[] params = parameters(round, uf, municipality);
        return jdbc.queryForObject("""
           SELECT COUNT(*) AS sections,
                  COALESCE(SUM(biometric_recognized),0) AS recognized,
                  COALESCE(SUM(no_biometric_enrollment),0) AS no_enrollment,
                  COALESCE(SUM(biometric_failed),0) AS failed,
                  COALESCE(SUM(operator_authorizations),0) AS operator_authorizations,
                  COUNT(*) FILTER (WHERE operator_authorizations IS NULL) AS missing_operator_data
           FROM section_biometric WHERE election_round = ?
           """ + filter, (rs, i) -> {
            long recognized = rs.getLong("recognized");
            long noEnrollment = rs.getLong("no_enrollment");
            long failures = rs.getLong("failed");
            long total = recognized + noEnrollment + failures;
            long sections = rs.getLong("sections");
            return new BiometricSummary(round, uf, municipality, sections, total, recognized,
                    noEnrollment, failures, rs.getLong("operator_authorizations"),
                    rs.getLong("missing_operator_data"),
                    AuditMath.percent(noEnrollment + failures, total),
                    sections == 0 ? "NO_DATA" : "DERIVED_FROM_IMPORT");
        }, params);
    }

    public BiometricComparison compareBiometrics(String uf, String municipality) {
        BiometricSummary first = biometrics(1, uf, municipality);
        BiometricSummary second = biometrics(2, uf, municipality);
        BigDecimal delta = first.sections > 0 && second.sections > 0
                ? second.nonBiometricPercentage.subtract(first.nonBiometricPercentage)
                : null;
        return new BiometricComparison(delta == null ? "INSUFFICIENT_COVERAGE" : "DESCRIPTIVE_ONLY",
                first, second, delta,
                "Habilitação sem reconhecimento biométrico não comprova substituição de eleitor. O arquivo de origem deve ser validado contra logs oficiais.");
    }

    public List<Map<String, Object>> reconcile(int round, String uf, int limit) {
        if (uf == null) {
            throw new IllegalArgumentException("uf obrigatória para reconciliar seções");
        }
        return jdbc.queryForList("""
            SELECT t.uf, t.municipality_code, t.municipality, t.zone, t.section,
                   t.turnout, COALESCE(SUM(v.votes),0) valid_votes,
                   t.turnout - COALESCE(SUM(v.votes),0) as difference
            FROM section_turnout t
            JOIN section_vote v ON v.election_round=t.election_round AND v.uf=t.uf
                AND v.municipality_code=t.municipality_code AND v.zone=t.zone
                AND v.section=t.section AND v.candidate_number NOT IN (95,96)
            WHERE t.election_round=? AND t.uf=?
            GROUP BY t.uf,t.municipality_code,t.municipality,t.zone,t.section,t.turnout
            HAVING t.turnout < COALESCE(SUM(v.votes),0)
            ORDER BY difference ASC LIMIT ?
            """, round, uf, limit);
    }

    private String regionFilter(String uf, String municipality) {
        if (municipality != null && uf == null) {
            throw new IllegalArgumentException("uf obrigatória quando municipalityCode informado");
        }
        return (uf == null ? "" : " AND uf = ?") +
                (municipality == null ? "" : " AND municipality_code = ?");
    }

    private Object[] parameters(int round, String uf, String municipality) {
        if (round != 1 && round != 2) {
            throw new IllegalArgumentException("round deve ser 1 ou 2");
        }
        if (municipality != null && uf == null) {
            throw new IllegalArgumentException("uf obrigatória quando municipalityCode informado");
        }
        return municipality == null ? (uf == null ? new Object[]{round} : new Object[]{round, uf})
                : new Object[]{round, uf, municipality};
    }

    public record ParticipationSummary(int round, String uf, String municipalityCode,
             long sections, long registered, long turnout, long abstentions,
             BigDecimal abstentionPercentage, long arithmeticMismatchSections, String status) { }

    public record TurnoutComparison(String status, ParticipationSummary first,
             ParticipationSummary second, BigDecimal abstentionChangePp, String limitation) { }

    public record SectionTurnout(String municipalityCode, String municipality, int zone,
             int section, long registered, long turnout, long abstentions, String sourceSha256) { }

    public record BiometricSummary(int round, String uf, String municipalityCode,
             long sections, long countedEvents, long recognized, long noEnrollment,
             long failedRecognition, long operatorAuthorizations,
             long sectionsMissingOperatorData, BigDecimal nonBiometricPercentage,
             String status) { }

    public record BiometricComparison(String status, BiometricSummary first,
             BiometricSummary second, BigDecimal nonBiometricChangePp, String limitation) { }
}
