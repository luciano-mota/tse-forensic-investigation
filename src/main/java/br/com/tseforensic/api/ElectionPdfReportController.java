package br.com.tseforensic.api;

import br.com.tseforensic.service.ElectionPdfReportService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ElectionPdfReportController {
    private final ElectionPdfReportService reports;

    public ElectionPdfReportController(ElectionPdfReportService reports) {
        this.reports=reports;
    }

    @GetMapping(value="/presidential-2026.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> presidential(
            @RequestParam(defaultValue="") String uf,
            @RequestParam(defaultValue="") String municipalityCode) {
        String place=uf.isBlank()?"brasil":uf.toLowerCase();
        if (!municipalityCode.isBlank()) place+="-" + municipalityCode;
        byte[] pdf=reports.generate(uf,municipalityCode);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"tse-forensic-2026-"+place+".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }
}
