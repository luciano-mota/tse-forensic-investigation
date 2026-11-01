package br.com.tseforensic.infra.importer;

import br.com.tseforensic.domain.VoteAggregate;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class TseCsvImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TseCsvImporter.class);
    private static final String UPSERT = """
            INSERT INTO election_vote
            (election_year, election_round, uf, municipality_code, municipality,
             candidate_number, candidate_name, votes, source_sha256)
            VALUES (2026, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (election_year, election_round, uf, municipality_code, candidate_number)
            DO UPDATE SET municipality = EXCLUDED.municipality,
                          candidate_name = EXCLUDED.candidate_name,
                          votes = EXCLUDED.votes,
                          source_sha256 = EXCLUDED.source_sha256
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Charset charset;

    public TseCsvImporter(JdbcTemplate jdbcTemplate,
                          @Value("${forensic.import.charset:ISO-8859-1}") String charset) {
        this.jdbcTemplate = jdbcTemplate;
        this.charset = Charset.forName(charset);
    }

    @Transactional
    public long importFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Arquivo inexistente: " + path);
        }
        String sha256 = sha256(path);
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM import_batch WHERE sha256 = ?", Integer.class, sha256);
        if (existing != null && existing > 0) {
            LOGGER.info("Arquivo já importado: {} ({})", path.getFileName(), sha256);
            return 0;
        }

        Map<VoteAggregate, Long> aggregates = new HashMap<>();
        long rows;
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            rows = readZip(path, aggregates);
        } else {
            try (InputStream input = Files.newInputStream(path)) {
                rows = parseCsv(input, aggregates);
            }
        }
        if (rows == 0 || aggregates.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma linha de votação presidencial reconhecida. Confira o layout do CSV.");
        }

        // Each input must be a complete official snapshot for its UF and round.
        Set<String> scopes = new HashSet<>();
        aggregates.keySet().forEach(k -> scopes.add(k.electionRound() + "|" + k.uf()));
        for (String scope : scopes) {
            String[] parts = scope.split("\\|");
            jdbcTemplate.update("DELETE FROM election_vote WHERE election_year = 2026 AND election_round = ? AND uf = ?",
                    Integer.parseInt(parts[0]), parts[1]);
        }

        jdbcTemplate.update("INSERT INTO import_batch (sha256, filename, row_count) VALUES (?, ?, ?)",
                sha256, path.getFileName().toString(), rows);
        List<VoteAggregate> values = new ArrayList<>(aggregates.size());
        aggregates.forEach((key, amount) -> values.add(key.withAdditionalVotes(amount)));
        jdbcTemplate.batchUpdate(UPSERT, values, 500, (statement, row) -> {
            statement.setInt(1, row.electionRound());
            statement.setString(2, row.uf());
            statement.setString(3, row.municipalityCode());
            statement.setString(4, row.municipality());
            statement.setInt(5, row.candidateNumber());
            statement.setString(6, row.candidateName());
            statement.setLong(7, row.votes());
            statement.setString(8, sha256);
        });
        LOGGER.info("Importação concluída: linhas={}, agregados={}, SHA-256={}",
                rows, values.size(), sha256);
        return rows;
    }

    private long readZip(Path path, Map<VoteAggregate, Long> aggregates) throws IOException {
        long count = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                    count += parseCsv(zip, aggregates);
                }
                zip.closeEntry();
            }
        }
        return count;
    }

    private long parseCsv(InputStream input, Map<VoteAggregate, Long> aggregates) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset));
        reader.mark(1);
        if (reader.read() != '\uFEFF') {
            reader.reset();
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(';')
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .get();
        long accepted = 0;
        // Do not close parser here: a ZipInputStream must remain open for next entry.
        CSVParser parser = format.parse(reader);
        for (CSVRecord row : parser) {
            if (!isPresident(row)) {
                continue;
            }
            int electionRound = positiveInt(required(row, "NR_TURNO"), "NR_TURNO");
            if (electionRound != 1 && electionRound != 2) {
                continue;
            }
            String uf = required(row, "SG_UF").toUpperCase(Locale.ROOT);
            String code = String.valueOf(positiveInt(required(row, "CD_MUNICIPIO"), "CD_MUNICIPIO"));
            String municipality = required(row, "NM_MUNICIPIO");
            int candidateNumber = positiveInt(required(row, "NR_VOTAVEL"), "NR_VOTAVEL");
            long votes = Long.parseLong(required(row, "QT_VOTOS"));
            if (votes < 0) {
                throw new IllegalArgumentException("Votos negativos na linha " + row.getRecordNumber());
            }
            String candidate = optional(row, "NM_VOTAVEL");
            if (candidate.isBlank()) {
                candidate = "Votável " + candidateNumber;
            }
            VoteAggregate key = new VoteAggregate(electionRound, uf, code, municipality,
                    candidateNumber, candidate, 0);
            aggregates.merge(key, votes, Math::addExact);
            accepted++;
        }
        return accepted;
    }

    private boolean isPresident(CSVRecord row) {
        String cargoCode = optional(row, "CD_CARGO");
        String cargoName = optional(row, "DS_CARGO");
        return "1".equals(cargoCode) || "PRESIDENTE".equalsIgnoreCase(cargoName);
    }

    private String required(CSVRecord row, String field) {
        String value = optional(row, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Coluna obrigatória vazia/ausente: " + field
                    + " na linha " + row.getRecordNumber());
        }
        return value;
    }

    private String optional(CSVRecord row, String field) {
        return row.isMapped(field) && row.isSet(field) ? row.get(field).trim() : "";
    }

    private int positiveInt(String value, String column) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Valor inválido de " + column + ": " + value, exception);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = new DigestInputStream(Files.newInputStream(path), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
