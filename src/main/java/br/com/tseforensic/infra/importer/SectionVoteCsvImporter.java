package br.com.tseforensic.infra.importer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SectionVoteCsvImporter {

    private static final String INSERT = """
        INSERT INTO section_vote(election_round,uf,municipality_code,zone,section,
                                 candidate_number,votes,source_sha256)
        VALUES (?,?,?,?,?,?,?,?)
        ON CONFLICT(election_year,election_round,uf,municipality_code,zone,section,candidate_number)
        DO UPDATE SET votes=EXCLUDED.votes,source_sha256=EXCLUDED.source_sha256
        """;
    private final JdbcTemplate jdbc;

    public SectionVoteCsvImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long importFile(Path path, Charset charset) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Arquivo ausente: " + path);
        String hash = sha256(path);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM import_batch WHERE sha256=?",
                Integer.class, hash);
        if (exists == null || exists == 0)
            jdbc.update("INSERT INTO import_batch(sha256,filename,row_count) VALUES(?,?,0)",
                    hash, path.getFileName().toString());
        ImportState state = new ImportState();
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
                ZipEntry entry;
                while ((entry=zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv"))
                        parse(zip, charset, hash, state);
                    zip.closeEntry();
                }
            }
        } else {
            try (InputStream in=Files.newInputStream(path)) {
                parse(in, charset, hash, state);
            }
        }
        flush(state);
        if (state.rows == 0)
            throw new IllegalArgumentException("Nenhuma seção presidencial encontrada: " + path);
        return state.rows;
    }

    private void parse(InputStream in, Charset encoding, String hash, ImportState state) throws IOException {
        CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader()
                .setSkipHeaderRecord(true).setTrim(true).setIgnoreHeaderCase(true).get()
                .parse(new InputStreamReader(in, encoding));
        for (CSVRecord r : parser) {
            if (!"1".equals(field(r,"CD_CARGO"))
                    && !"PRESIDENTE".equalsIgnoreCase(field(r,"DS_CARGO"))) continue;
            int round = integer(r,"NR_TURNO");
            if (round != 1 && round != 2) continue;
            String uf = required(r,"SG_UF").toUpperCase(Locale.ROOT);
            String scope = round + "|" + uf;
            if (state.scopes.add(scope)) {
                jdbc.update("DELETE FROM section_vote WHERE election_round=? AND uf=?", round, uf);
            }
            state.buffer.add(new VoteRow(round,uf,required(r,"CD_MUNICIPIO"),
                    integer(r,"NR_ZONA"), integer(r,"NR_SECAO"),
                    integer(r,"NR_VOTAVEL"), longValue(r,"QT_VOTOS"),hash));
            state.rows++;
            if (state.buffer.size() >= 1000) flush(state);
        }
    }

    private void flush(ImportState state) {
        if (state.buffer.isEmpty()) return;
        jdbc.batchUpdate(INSERT, state.buffer, 1000, (s,v) -> {
            s.setInt(1,v.round); s.setString(2,v.uf); s.setString(3,v.municipality);
            s.setInt(4,v.zone); s.setInt(5,v.section); s.setInt(6,v.candidate);
            s.setLong(7,v.votes); s.setString(8,v.source);
        });
        state.buffer.clear();
    }

    private int integer(CSVRecord r,String k) { return Math.toIntExact(longValue(r,k)); }
    private long longValue(CSVRecord r,String k) {
        try {
            long n = Long.parseLong(required(r,k));
            if (n < 0) throw new NumberFormatException("negative");
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Coluna numérica inválida: " + k + " linha " + r.getRecordNumber(),e);
        }
    }
    private String required(CSVRecord r,String k) {
        String value = field(r,k);
        if (value.isBlank())
            throw new IllegalArgumentException("Campo ausente: " + k + " linha " + r.getRecordNumber());
        return value;
    }
    private String field(CSVRecord r,String k) {
        return r.isMapped(k) && r.isSet(k) ? r.get(k).trim() : "";
    }
    private String sha256(Path path) throws IOException {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            try (InputStream in=new DigestInputStream(Files.newInputStream(path),md)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static class ImportState {
        final Set<String> scopes=new HashSet<>();
        final List<VoteRow> buffer=new ArrayList<>();
        long rows;
    }
    private record VoteRow(int round,String uf,String municipality,int zone,
                           int section,int candidate,long votes,String source) { }
}
