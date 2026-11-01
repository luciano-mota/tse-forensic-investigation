package br.com.tseforensic.service;

import br.com.tseforensic.domain.AuditMath;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BwebBiometricService {

    private final JdbcTemplate jdbc;
    public BwebBiometricService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public BwebResult summary(int round,String uf,String municipalityCode) {
        if (round < 1 || round > 2) throw new IllegalArgumentException("Turno inválido");
        if (municipalityCode != null && uf == null) throw new IllegalArgumentException("UF obrigatória");
        String condition=(uf==null?"":" AND uf=?") +
                (municipalityCode==null?"":" AND municipality_code=?");
        Object[] params=municipalityCode!=null?new Object[]{round,uf,municipalityCode}:
                uf!=null?new Object[]{round,uf}:new Object[]{round};
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS sections,
                   COALESCE(SUM(turnout),0) AS turnout,
                   COALESCE(SUM(not_recognized),0) AS not_recognized
            FROM bweb_biometric_nonrecognition WHERE election_round=?
            """+condition,(rs,i)->{
            long sections=rs.getLong("sections"), turnout=rs.getLong("turnout"),
                    count=rs.getLong("not_recognized");
            return new BwebResult(round,uf,municipalityCode,sections,turnout,count,
                    AuditMath.percent(count,turnout),sections>0?"BWEB_IMPORT":"NO_DATA",
                    "QT_ELEI_BIOM_SEM_HABILITACAO: falha de reconhecimento biométrico para eleitor cadastrado; não representa todos os votantes sem biometria nem assinatura individual.");
        },params);
    }

    public BwebComparison compare(String uf,String municipalityCode) {
        BwebResult first=summary(1,uf,municipalityCode);
        BwebResult second=summary(2,uf,municipalityCode);
        BigDecimal delta=first.sections()>0 && second.sections()>0 ?
                second.percentage().subtract(first.percentage()):null;
        return new BwebComparison(delta==null?"INSUFFICIENT_COVERAGE":"DESCRIPTIVE_ONLY",
                first,second,delta,
                "Os percentuais correspondem ao BWEB importado; a ausência de biometria cadastrada não está incluída neste indicador.");
    }

    public record BwebResult(int round,String uf,String municipalityCode,long sections,
            long turnout,long notRecognized,BigDecimal percentage,String status,String limitation) {}
    public record BwebComparison(String status,BwebResult first,BwebResult second,
            BigDecimal changePp,String limitation) {}
}
