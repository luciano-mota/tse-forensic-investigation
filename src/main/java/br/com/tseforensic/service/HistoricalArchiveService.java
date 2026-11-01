package br.com.tseforensic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Git archive-history is the public immutable metadata ledger.
 * S3/R2 is the source of original byte downloads, never the Git repository.
 */
@Service
public class HistoricalArchiveService {

    private static final URI INDEX = URI.create(
            "https://raw.githubusercontent.com/luciano-mota/tse-forensic-investigation/archive-history/archive/index.json");
    private static final Pattern SNAPSHOT = Pattern.compile(
            "snapshots/[A-Za-z0-9._/-]{1,400}[a-f0-9]{64}\\.json");
    private static final Pattern KEY = Pattern.compile("tse/2026/[A-Za-z0-9._/-]{1,700}");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper mapper;
    private JsonNode cachedIndex;
    private Instant cacheUntil = Instant.EPOCH;

    public HistoricalArchiveService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized JsonNode index() {
        if (cachedIndex != null && Instant.now().isBefore(cacheUntil)) {
            return cachedIndex;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(INDEX)
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .header("User-Agent", "TSE-Forensic-Investigation/1.0")
                    .GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return mapper.readTree("""
                        {"schema":1,"status":"WAITING_FIRST_SYNC","resources":{},"snapshots":[],"poll_count":0}
                        """);
            }
            if (response.statusCode() != 200 || response.body().length > 12_000_000) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Histórico temporariamente indisponível na origem pública");
            }
            JsonNode parsed = mapper.readTree(response.body());
            if (!parsed.path("snapshots").isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Índice de histórico não está no formato esperado");
            }
            cachedIndex = parsed;
            cacheUntil = Instant.now().plus(Duration.ofMinutes(6));
            return cachedIndex;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Consulta interrompida", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não foi possível consultar histórico público", exception);
        }
    }

    public URI signedDownload(String snapshot) {
        if (snapshot == null || snapshot.contains("..") || !SNAPSHOT.matcher(snapshot).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de versão inválido");
        }
        JsonNode item = null;
        for (JsonNode candidate : index().path("snapshots")) {
            if (snapshot.equals(candidate.path("snapshot").asText())) {
                item = candidate;
                break;
            }
        }
        if (item == null || !"stored".equals(item.path("original").path("status").asText())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta versão não possui original armazenado");
        }
        String key = item.path("original").path("object_key").asText();
        if (!KEY.matcher(key).matches() || key.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Chave de original inválida");
        }
        String endpoint = System.getenv("TSE_ARCHIVE_ENDPOINT");
        String bucket = System.getenv("TSE_ARCHIVE_BUCKET");
        String accessKey = System.getenv("TSE_ARCHIVE_ACCESS_KEY_ID");
        String secretKey = System.getenv("TSE_ARCHIVE_SECRET_ACCESS_KEY");
        if (blank(endpoint) || blank(bucket) || blank(accessKey) || blank(secretKey)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cópia arquivada, mas download privado ainda não foi configurado neste serviço");
        }
        URI endpointUri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(endpointUri.getScheme())
                || endpointUri.getHost() == null || endpointUri.getUserInfo() != null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Endpoint de armazenamento inválido");
        }
        String filename = key.substring(key.lastIndexOf('/') + 1);
        String disposition = "attachment; filename=\"" +
                filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"";
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(Optional.ofNullable(System.getenv("TSE_ARCHIVE_REGION")).filter(v -> !v.isBlank()).orElse("auto")))
                .endpointOverride(endpointUri)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .responseContentDisposition(disposition).build())
                    .build()).url().toURI();
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível assinar o download histórico", exception);
        }
    }

    private boolean blank(String input) {
        return input == null || input.isBlank();
    }
}
