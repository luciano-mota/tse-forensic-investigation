package br.com.tseforensic.api;

import br.com.tseforensic.domain.RegionResult;
import br.com.tseforensic.infra.repository.ElectionRepository;
import br.com.tseforensic.service.ElectionAnalysisService;
import br.com.tseforensic.service.ElectionAnalysisService.Shift;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ElectionController {

    private final ElectionAnalysisService analysis;
    private final ElectionRepository repository;

    public ElectionController(ElectionAnalysisService analysis, ElectionRepository repository) {
        this.analysis = analysis;
        this.repository = repository;
    }

    @GetMapping("/elections/overview")
    public RegionResult overview(@RequestParam(defaultValue = "2") @Min(1) int round) {
        ensureRound(round);
        return analysis.overview(round);
    }

    @GetMapping("/elections/states")
    public List<RegionResult> states(@RequestParam(defaultValue = "2") @Min(1) int round) {
        ensureRound(round);
        return analysis.states(round);
    }

    @GetMapping("/elections/states/{uf}/municipalities")
    public List<RegionResult> municipalities(
            @PathVariable @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(defaultValue = "2") int round) {
        ensureRound(round);
        return analysis.municipalities(round, uf.toUpperCase(java.util.Locale.ROOT));
    }

    @GetMapping("/elections/comparison")
    public Map<String, Object> compare(
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(defaultValue = "500") @Min(1) long minVotes,
            @RequestParam(defaultValue = "15") @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal thresholdPp) {
        String normalized = uf == null ? null : uf.toUpperCase(java.util.Locale.ROOT);
        List<Shift> shifts = analysis.changes(normalized, minVotes, thresholdPp);
        return Map.of(
                "classification", "DESCRIPTIVE_ONLY",
                "warning", "Variação entre turnos não demonstra fraude nem transferência individual de votos.",
                "minValidVotesPerRound", minVotes,
                "thresholdPercentagePoints", thresholdPp,
                "shifts", shifts
        );
    }

    @GetMapping("/sources")
    public List<ElectionRepository.SourceRow> sources() {
        return repository.sources();
    }

    @GetMapping("/methodology")
    public Map<String, Object> methodology() {
        return Map.of(
                "year", 2026,
                "office", "PRESIDENTE",
                "status", "INDEPENDENT_NONOFFICIAL",
                "evidence", "Nenhuma anomalia estatística isolada comprova fraude.",
                "limitations", List.of(
                        "O sistema usa somente dados efetivamente importados; ausência de dados não significa ausência de divergências.",
                        "Sem logs, código e teste de integridade da urna, a hipótese de alteração anterior à emissão do BU não pode ser descartada por comparação de totais.",
                        "Variações entre turnos são esperadas devido a candidatos eliminados, abstenção e comportamento eleitoral.",
                        "Auditoria de pesquisas exige amostra, método, período e incerteza; pesquisa não é totalização."
                )
        );
    }

    private void ensureRound(int round) {
        if (round != 1 && round != 2) {
            throw new IllegalArgumentException("round deve ser 1 ou 2");
        }
    }
}
