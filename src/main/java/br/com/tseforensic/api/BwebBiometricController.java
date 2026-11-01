package br.com.tseforensic.api;

import br.com.tseforensic.service.BwebBiometricService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@Validated
@RestController
@RequestMapping("/api/v1/audits/biometrics/bweb")
public class BwebBiometricController {

    private final BwebBiometricService service;
    public BwebBiometricController(BwebBiometricService service) { this.service=service; }

    @GetMapping
    public BwebBiometricService.BwebResult summary(
            @RequestParam(defaultValue="2") @Min(1) @Max(2) int round,
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z]{2}") String uf,
            @RequestParam(required=false) @Pattern(regexp="[0-9]{1,12}") String municipalityCode) {
        return service.summary(round,uf==null?null:uf.toUpperCase(Locale.ROOT),municipalityCode);
    }

    @GetMapping("/comparison")
    public BwebBiometricService.BwebComparison compare(
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z]{2}") String uf,
            @RequestParam(required=false) @Pattern(regexp="[0-9]{1,12}") String municipalityCode) {
        return service.compare(uf==null?null:uf.toUpperCase(Locale.ROOT),municipalityCode);
    }
}
