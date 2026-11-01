package br.com.tseforensic.api;

import br.com.tseforensic.service.HistoricalArchiveService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/archive")
public class HistoricalArchiveController {

    private final HistoricalArchiveService service;

    public HistoricalArchiveController(HistoricalArchiveService service) {
        this.service = service;
    }

    @GetMapping("/history")
    public JsonNode history() {
        return service.index();
    }

    @GetMapping("/original")
    public ResponseEntity<Void> download(@RequestParam String snapshot) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(service.signedDownload(snapshot))
                .header("Cache-Control", "private, no-store")
                .build();
    }
}
