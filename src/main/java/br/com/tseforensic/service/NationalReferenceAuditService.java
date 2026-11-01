package br.com.tseforensic.service;

import br.com.tseforensic.domain.CandidateResult;
import br.com.tseforensic.domain.RegionResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NationalReferenceAuditService {

    private static final String TSE_SOURCE = "https://www.tse.jus.br/comunicacao/noticias/2026/Outubro/presidente-do-tse-anuncia-reeleicao-de-lula-e-faz-balanco-do-2o-turno-das-eleicoes-2026";
    private static final Map<Integer, Long> OFFICIAL_TOTALS = Map.of(
            13, 64_256_232L,
            22, 59_826_041L
    );
    private static final Set<String> REQUIRED_UFS = Set.of(
            "AC", "AL", "AM", "AP", "BA", "CE", "DF", "ES", "GO", "MA",
            "MG", "MS", "MT", "PA", "PB", "PE", "PI", "PR", "RJ", "RN",
            "RO", "RR", "RS", "SC", "SE", "SP", "TO", "ZZ"
    );

    private final ElectionAnalysisService analysis;

    public NationalReferenceAuditService(ElectionAnalysisService analysis) {
        this.analysis = analysis;
    }

    public ReferenceCheck check() {
        RegionResult imported = analysis.overview(2);
        Set<String> loaded = analysis.states(2).stream()
                .filter(region -> region.validVotes() > 0)
                .map(RegionResult::code)
                .collect(Collectors.toSet());
        Set<String> missing = REQUIRED_UFS.stream()
                .filter(code -> !loaded.contains(code))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        Map<Integer, Long> votes = imported.candidates().stream()
                .collect(Collectors.toMap(CandidateResult::number, CandidateResult::votes));

        List<ReferenceLine> rows = OFFICIAL_TOTALS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ReferenceLine(entry.getKey(), entry.getValue(),
                        votes.getOrDefault(entry.getKey(), 0L),
                        votes.getOrDefault(entry.getKey(), 0L) - entry.getValue()))
                .toList();

        String status;
        if (!missing.isEmpty() || imported.validVotes() == 0) {
            status = "COBERTURA_INCOMPLETA";
        } else if (rows.stream().allMatch(row -> row.difference() == 0)
                && imported.validVotes() == OFFICIAL_TOTALS.values().stream().mapToLong(Long::longValue).sum()) {
            status = "CONFERE_COM_TOTAL_PUBLICADO";
        } else {
            status = "DIVERGENCIA_COM_TOTAL_PUBLICADO";
        }

        return new ReferenceCheck(status, 2026, 2, TSE_SOURCE, "2026-10-26",
                List.copyOf(missing), imported.validVotes(), rows,
                "Confronto com números publicados pelo TSE em 26/10/2026. Não é evidência independente da captura do voto e não valida software de urna.");
    }

    public record ReferenceLine(int candidateNumber, long referenceVotes, long importedVotes, long difference) {
    }

    public record ReferenceCheck(
            String status,
            int electionYear,
            int electionRound,
            String referenceUrl,
            String referenceDate,
            List<String> missingUfs,
            long importedValidVotes,
            List<ReferenceLine> candidates,
            String limitation
    ) {
    }
}
