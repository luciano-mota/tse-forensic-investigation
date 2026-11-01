package br.com.tseforensic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fetches derived, publicly versioned TSE aggregates from our GitHub archive.
 * Raw official ZIP files are NEVER persisted on Render or exposed via this endpoint.
 */
@Service
public class LiveElectionMirrorService {
    private static final String BASE =
            "https://raw.githubusercontent.com/luciano-mota/tse-forensic-investigation/archive-history/";
    private static final Pattern DERIVED = Pattern.compile(
            "archive/derived/versions/[a-f0-9]{64}/atlas\\.json");
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8)).build();
    private JsonNode cached;
    private Instant until = Instant.EPOCH;

    public LiveElectionMirrorService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized JsonNode current() {
        if (cached != null && Instant.now().isBefore(until)) return cached;
        try {
            JsonNode pointer = fetch(BASE + "archive/derived/current.json", 64_000);
            if (pointer == null) {
                cached = mapper.valueToTree(Map.of("status", "WAITING_FIRST_EXTRACTION",
                        "warning", "Aguardando os agregados oficiais. Nenhum resultado inventado."));
                until = Instant.now().plus(Duration.ofMinutes(3));
                return cached;
            }
            String path = pointer.path("data_path").asText("");
            if (!DERIVED.matcher(path).matches()) {
                throw new IllegalStateException("Invalid immutable derived source path");
            }
            JsonNode atlas = fetch(BASE + path, 20_000_000);
            if (atlas == null || atlas.path("schema").asInt() != 1
                    || atlas.path("rounds").path("1").isMissingNode()) {
                throw new IllegalStateException("Derived atlas invalid or missing");
            }
            if (!pointer.path("derived_version").asText("")
                    .equals(atlas.path("derived_version").asText("invalid"))) {
                throw new IllegalStateException("Derived snapshot version does not match index");
            }
            cached = atlas;
            until = Instant.now().plus(Duration.ofMinutes(12));
            return cached;
        } catch (Exception ex) {
            // Never convert transport failure into data and never show partial aggregate.
            cached = mapper.valueToTree(Map.of("status", "UNAVAILABLE",
                    "warning", "Não foi possível conferir o espelho de dados no momento."));
            until = Instant.now().plus(Duration.ofMinutes(2));
            return cached;
        }
    }

    private JsonNode fetch(String url, int limit) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET().timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json")
                .header("User-Agent", "TSE-Forensic-Data-Mirror/1.0").build();
        HttpResponse<byte[]> response = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200 || response.body().length > limit) {
            throw new IOException("Unexpected status or payload size: " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }
}
