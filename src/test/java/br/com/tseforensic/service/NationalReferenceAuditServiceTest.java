package br.com.tseforensic.service;

import br.com.tseforensic.domain.CandidateResult;
import br.com.tseforensic.domain.RegionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NationalReferenceAuditServiceTest {

    @Test
    void incompleteRegionalCoverageMustNotBeCalledAnElectoralDiscrepancy() {
        ElectionAnalysisService analysis = mock(ElectionAnalysisService.class);
        when(analysis.overview(2)).thenReturn(new RegionResult("BR", "Brasil", 1000,
                List.of(new CandidateResult(13, "Lula", 600, BigDecimal.valueOf(60)),
                        new CandidateResult(22, "Flávio Bolsonaro", 400, BigDecimal.valueOf(40)))));
        when(analysis.states(2)).thenReturn(List.of(new RegionResult("MA", "Maranhão", 1000,
                List.of(new CandidateResult(13, "Lula", 600, BigDecimal.valueOf(60))))));

        var result = new NationalReferenceAuditService(analysis).check();

        assertThat(result.status()).isEqualTo("COBERTURA_INCOMPLETA");
        assertThat(result.missingUfs()).contains("SP", "ZZ");
    }
}
