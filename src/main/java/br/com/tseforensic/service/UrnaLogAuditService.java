package br.com.tseforensic.service;

import br.com.tseforensic.domain.AuditMath;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UrnaLogAuditService {

    private final JdbcTemplate jdbc;
    public UrnaLogAuditService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Summary overview(int round,String uf,String municipality) {
        if (round!=1 && round!=2) throw new IllegalArgumentException("Turno inválido");
        if (municipality!=null && uf==null) throw new IllegalArgumentException("UF obrigatória");
        String filter=(uf==null?"":" AND uf=?") + (municipality==null?"":" AND municipality_code=?");
        Object[] params=municipality==null?(uf==null?new Object[]{round}:new Object[]{round,uf}):
                new Object[]{round,uf,municipality};
        return jdbc.queryForObject("""
             SELECT COUNT(*) sections, COALESCE(SUM(votes_computed),0) computed,
                    COALESCE(SUM(biometric_recognized),0) recognized,
                    COALESCE(SUM(biometric_failed),0) failed,
                    COALESCE(SUM(no_enrollment),0) no_enroll,
                    COALESCE(SUM(unclassified),0) unknown,
                    COALESCE(SUM(after_17_local),0) after17_local,
                    COALESCE(SUM(CASE WHEN log_files>1 THEN 1 ELSE 0 END),0) multiple_logs
             FROM section_log_audit WHERE election_round=?
             """+filter,(r,i)->{
            long present=r.getLong("sections"), computed=r.getLong("computed"),
                    recognized=r.getLong("recognized"), failed=r.getLong("failed"),
                    noEnroll=r.getLong("no_enroll"),unknown=r.getLong("unknown");
            return new Summary(round,uf,municipality,present,computed,recognized,failed,
                    noEnroll,unknown,r.getLong("after17_local"),r.getLong("multiple_logs"),
                    AuditMath.percent(failed+noEnroll,computed),
                    present==0?"NO_DATA":unknown>0?"PARTIAL_CLASSIFICATION":"DESCRIPTIVE_ONLY",
                    "Horas do log são LOCAIS, não horário de Brasília. Seções com múltiplos logs demandam reconciliação de contingências. Não há vínculo com candidaturas.");
        },params);
    }

    public List<Map<String,Object>> reconcileBweb(int round,String uf,int limit) {
        if (uf==null) throw new IllegalArgumentException("uf obrigatória");
        if (round!=1 && round!=2) throw new IllegalArgumentException("Turno inválido");
        return jdbc.queryForList("""
            SELECT l.uf,l.municipality_code,l.zone,l.section,l.biometric_failed AS log_failed,
                   b.not_recognized AS bweb_not_recognized,
                   l.biometric_failed-b.not_recognized AS difference, l.log_files,
                   l.unclassified
            FROM section_log_audit l
            JOIN bweb_biometric_nonrecognition b
              ON b.election_round=l.election_round AND b.uf=l.uf
              AND b.municipality_code=l.municipality_code
              AND b.zone=l.zone AND b.section=l.section
            WHERE l.election_round=? AND l.uf=? AND
                  (l.biometric_failed <> b.not_recognized OR l.log_files>1 OR l.unclassified>0)
            ORDER BY ABS(l.biometric_failed-b.not_recognized) DESC
            LIMIT ?
            """,round,uf,limit);
    }

    public record Summary(int round,String uf,String municipalityCode,long sections,
            long votesComputed,long recognized,long failedRecognition,long noEnrollment,
            long unclassified,long after17Local,long sectionsWithMultipleLogs,
            BigDecimal nonBiometricPercentage,String status,String limitation) {}
}
