package br.com.tseforensic.api;

import br.com.tseforensic.service.LiveElectionMirrorService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/live")
public class LiveElectionMirrorController {
    private final LiveElectionMirrorService mirror;
    public LiveElectionMirrorController(LiveElectionMirrorService mirror) { this.mirror = mirror; }

    @GetMapping("/atlas")
    public ResponseEntity<JsonNode> atlas() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(90, TimeUnit.SECONDS).cachePrivate())
                .body(mirror.current());
    }
}
