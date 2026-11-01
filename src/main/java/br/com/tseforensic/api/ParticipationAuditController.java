package br.com.tseforensic.api;

import br.com.tseforensic.service.ParticipationAuditService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/audits")
public class ParticipationAuditController {

    private final ParticipationAuditService service;

    public ParticipationAuditController(ParticipationAuditService service) {
        this.service = service;
    }

    @GetMapping("/turnout")
    public ParticipationAuditService.ParticipationSummary turnout(
            @RequestParam(defaultValue = "2") @Min(1) @Max(2) int round,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{1,12}") String municipalityCode) {
        return service.turnout(round, normalize(uf), municipalityCode);
    }

    @GetMapping("/turnout/comparison")
    public ParticipationAuditService.TurnoutComparison compareTurnout(
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{1,12}") String municipalityCode) {
        return service.compare(normalize(uf), municipalityCode);
    }

    @GetMapping("/turnout/sections")
    public List<ParticipationAuditService.SectionTurnout> turnoutSections(
            @RequestParam @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(defaultValue = "2") @Min(1) @Max(2) int round,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{1,12}") String municipalityCode,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return service.sections(round, normalize(uf), municipalityCode, limit, offset);
    }

    @GetMapping("/biometrics")
    public ParticipationAuditService.BiometricSummary biometrics(
            @RequestParam(defaultValue = "2") @Min(1) @Max(2) int round,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{1,12}") String municipalityCode) {
        return service.biometrics(round, normalize(uf), municipalityCode);
    }

    @GetMapping("/biometrics/comparison")
    public ParticipationAuditService.BiometricComparison compareBiometrics(
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{1,12}") String municipalityCode) {
        return service.compareBiometrics(normalize(uf), municipalityCode);
    }

    @GetMapping("/sections/reconciliation")
    public Map<String, Object> sectionReconciliation(
            @RequestParam @Pattern(regexp = "[A-Za-z]{2}") String uf,
            @RequestParam(defaultValue = "2") @Min(1) @Max(2) int round,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return Map.of("status", "PARTIAL_VOTE_TURNOUT_CHECK",
                "limitation", "Comparecimento pode incluir votos brancos/nulos. O teste identifica apenas votos válidos maiores que comparecimento, não confere RDV nem BU.",
                "findings", service.reconcile(round, normalize(uf), limit));
    }

    private String normalize(String uf) {
        return uf == null ? null : uf.toUpperCase(Locale.ROOT);
    }
}
