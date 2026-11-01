package br.com.tseforensic.api;

import br.com.tseforensic.service.UrnaLogAuditService;
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

@Validated
@RestController
@RequestMapping("/api/v1/audits/urna-logs")
public class UrnaLogAuditController {

    private final UrnaLogAuditService service;
    public UrnaLogAuditController(UrnaLogAuditService service) {this.service=service;}

    @GetMapping("/overview")
    public UrnaLogAuditService.Summary overview(
            @RequestParam(defaultValue="2") @Min(1) @Max(2) int round,
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z]{2}") String uf,
            @RequestParam(required=false) @Pattern(regexp="[0-9]{1,12}") String municipalityCode) {
        return service.overview(round,uf==null?null:uf.toUpperCase(Locale.ROOT),municipalityCode);
    }

    @GetMapping("/bweb-reconciliation")
    public Map<String,Object> bwebReconciliation(
            @RequestParam @Pattern(regexp="[A-Za-z]{2}") String uf,
            @RequestParam(defaultValue="2") @Min(1) @Max(2) int round,
            @RequestParam(defaultValue="100") @Min(1) @Max(500) int limit) {
        List<Map<String,Object>> observations=service.reconcileBweb(round,uf.toUpperCase(Locale.ROOT),limit);
        return Map.of("status","CHECK_REQUIRES_REVIEW",
                "findings",observations,
                "limitation","Diferenças podem decorrer de contingência, mensagens de log ausentes ou interpretação do evento. Não comprovam fraude. Revisar arquivos de origem.");
    }
}
