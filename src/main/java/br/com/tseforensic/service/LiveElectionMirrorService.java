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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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
    private JsonNode cachedMonitor;
    private Instant monitorUntil = Instant.EPOCH;

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
            until = Instant.now().plus(Duration.ofMinutes(2));
            return cached;
        } catch (Exception ex) {
            // Never convert transport failure into data and never show partial aggregate.
            cached = mapper.valueToTree(Map.of("status", "UNAVAILABLE",
                    "warning", "Não foi possível conferir o espelho de dados no momento."));
            until = Instant.now().plus(Duration.ofMinutes(2));
            return cached;
        }
    }

    /**
     * Lightweight update check. This returns the last official source check time,
     * not a claim that election data are being published in real time.
     * The extraction runner records last_attempt.json on each hourly pass.
     */
    public synchronized JsonNode monitor() {
        if (cachedMonitor != null && Instant.now().isBefore(monitorUntil)) {
            return cachedMonitor;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("source", "TSE_OFFICIAL_CKAN_TEMPORARY_ZIP");
        result.put("cadence", "HOURLY_BEST_EFFORT");
        result.put("disclaimer", "Os dados representam a última publicação oficial processada; não há transmissão de votos em tempo real.");
        result.put("checkedAt", null);
        result.put("snapshotAt", null);
        result.put("version", null);
        result.put("status", "PENDING");
        try {
            JsonNode last = fetch(BASE + "archive/derived/last_attempt.json", 64_000);
            JsonNode pointer = fetch(BASE + "archive/derived/current.json", 64_000);
            String version = pointer == null ? "" : pointer.path("derived_version").asText("");
            if (version.matches("[a-f0-9]{64}")) {
                result.put("version", version);
                result.put("snapshotAt", pointer.path("observed_at").asText(null));
            }
            if (last != null) {
                result.put("checkedAt", last.path("checked_at").asText(null));
                String outcome = last.path("status").asText("");
                result.put("lastAttempt", outcome);
                if ("FAILED".equals(outcome)) {
                    result.put("status", version.isBlank() ? "ERROR" : "DEGRADED");
                    result.put("warning", "A última coleta falhou; exibindo somente a última versão válida.");
                } else if (!version.isBlank()) {
                    Instant check = Instant.parse(last.path("checked_at").asText());
                    long hours = ChronoUnit.HOURS.between(check, Instant.now());
                    result.put("status", hours >= 3 ? "STALE" : "OK");
                    if (hours >= 3) result.put("warning", "Sem verificação recente; consulte o histórico de execução.");
                }
            } else if (!version.isBlank()) {
                result.put("status", "STALE");
                result.put("warning", "Sem registro acessível da última verificação.");
            }
        } catch (Exception error) {
            result.put("status", "UNAVAILABLE");
            result.put("warning", "O registro de atualização não pôde ser conferido.");
        }
        cachedMonitor = mapper.valueToTree(result);
        monitorUntil = Instant.now().plus(Duration.ofSeconds(90));
        return cachedMonitor;
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
