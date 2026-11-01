package br.com.tseforensic.api;

import br.com.tseforensic.service.NationalReferenceAuditService;
import br.com.tseforensic.service.NationalReferenceAuditService.ReferenceCheck;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audits")
public class NationalReferenceAuditController {

    private final NationalReferenceAuditService service;

    public NationalReferenceAuditController(NationalReferenceAuditService service) {
        this.service = service;
    }

    @GetMapping("/national-reference")
    public ReferenceCheck nationalReference() {
        return service.check();
    }
}
