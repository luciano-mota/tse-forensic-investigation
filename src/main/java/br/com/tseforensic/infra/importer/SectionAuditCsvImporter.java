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
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SectionAuditCsvImporter {

    public enum Kind { TURNOUT, BIOMETRIC_AGGREGATE }

    private final JdbcTemplate jdbc;

    public SectionAuditCsvImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long importFile(Path file, Kind kind, Charset encoding) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Arquivo ausente: " + file);
        String hash = hash(file);
        Map<SectionKey, Counts> items = new HashMap<>();
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv"))
                        parse(zip, kind, encoding, items);
                    zip.closeEntry();
                }
            }
        } else {
            try (InputStream stream = Files.newInputStream(file)) {
                parse(stream, kind, encoding, items);
            }
        }
        if (items.isEmpty()) throw new IllegalArgumentException("Nenhum registro reconhecido no arquivo " + file);
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM import_batch WHERE sha256 = ?",
                Integer.class, hash);
        if (existing == null || existing == 0)
            jdbc.update("INSERT INTO import_batch(sha256,filename,row_count) VALUES (?,?,?)",
                    hash, file.getFileName().toString(), items.size());
        String table = kind == Kind.TURNOUT ? "section_turnout" : "section_biometric";
        Set<String> scopes = new HashSet<>();
        items.keySet().forEach(k -> scopes.add(k.round + "|" + k.uf));
        for (String scope : scopes) {
            String[] pair = scope.split("\\|");
            jdbc.update("DELETE FROM " + table + " WHERE election_round=? AND uf=?",
                    Integer.parseInt(pair[0]), pair[1]);
        }
        if (kind == Kind.TURNOUT) {
            jdbc.batchUpdate("""
                INSERT INTO section_turnout(election_round,uf,municipality_code,municipality,
                  zone,section,registered,turnout,abstentions,source_sha256)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, items.entrySet(), 500, (s, item) -> {
                SectionKey k = item.getKey();
                Counts v = item.getValue();
                s.setInt(1,k.round); s.setString(2,k.uf); s.setString(3,k.municipalityCode);
                s.setString(4,v.municipality); s.setInt(5,k.zone); s.setInt(6,k.section);
                s.setLong(7,v.a); s.setLong(8,v.b); s.setLong(9,v.c);
                s.setString(10,hash);
            });
        } else {
            jdbc.batchUpdate("""
                INSERT INTO section_biometric(election_round,uf,municipality_code,zone,section,
                  biometric_recognized,no_biometric_enrollment,biometric_failed,
                  operator_authorizations,source_sha256)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, items.entrySet(), 500, (s, item) -> {
                SectionKey k = item.getKey();
                Counts v = item.getValue();
                s.setInt(1,k.round); s.setString(2,k.uf); s.setString(3,k.municipalityCode);
                s.setInt(4,k.zone); s.setInt(5,k.section);
                s.setLong(6,v.a); s.setLong(7,v.b); s.setLong(8,v.c);
                if (v.d == null) s.setNull(9,java.sql.Types.BIGINT);
                else s.setLong(9,v.d);
                s.setString(10,hash);
            });
        }
        return items.size();
    }

    private void parse(InputStream stream, Kind kind, Charset encoding,
                       Map<SectionKey,Counts> items) throws IOException {
        // The CSVParser is intentionally not closed: closing would close the containing ZIP.
        CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(';')
                .setHeader().setSkipHeaderRecord(true).setTrim(true).setIgnoreHeaderCase(true)
                .get().parse(new InputStreamReader(stream, encoding));
        for (CSVRecord row : parser) {
            if (kind == Kind.TURNOUT && !"1".equals(optional(row,"CD_CARGO"))
                    && !"PRESIDENTE".equalsIgnoreCase(optional(row,"DS_CARGO"))) continue;
            int round = intValue(row,"NR_TURNO");
            if (round != 1 && round != 2) continue;
            SectionKey key = new SectionKey(round, required(row,"SG_UF").toUpperCase(Locale.ROOT),
                    String.valueOf(intValue(row,"CD_MUNICIPIO")), intValue(row,"NR_ZONA"), intValue(row,"NR_SECAO"));
            Counts values = kind == Kind.TURNOUT
                    ? new Counts(required(row,"NM_MUNICIPIO"),
                        quantity(row,"QT_APTOS"),quantity(row,"QT_COMPARECIMENTO"),
                        quantity(row,"QT_ABSTENCOES"),null)
                    : new Counts("",quantity(row,"QT_BIOMETRIA_RECONHECIDA"),
                        quantity(row,"QT_SEM_CADASTRO_BIOMETRICO"),
                        quantity(row,"QT_BIOMETRIA_FALHOU"),
                        optional(row,"QT_LIBERACOES_MESARIO").isBlank()
                            ? null : quantity(row,"QT_LIBERACOES_MESARIO"));
            Counts prior = items.putIfAbsent(key,values);
            if (prior != null && !prior.equals(values))
                throw new IllegalArgumentException("Seção duplicada com contagens divergentes: " + key);
        }
    }

    private int intValue(CSVRecord row,String name) {
        return Math.toIntExact(quantity(row,name));
    }
    private long quantity(CSVRecord row,String name) {
        String value = required(row,name);
        try {
            long result = Long.parseLong(value);
            if (result < 0) throw new NumberFormatException("negativo");
            return result;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Número inválido: " + name + " linha " + row.getRecordNumber(),ex);
        }
    }
    private String required(CSVRecord row,String name) {
        String value = optional(row,name);
        if (value.isBlank()) throw new IllegalArgumentException("Coluna ausente/vazia: " + name +
                " linha " + row.getRecordNumber());
        return value;
    }
    private String optional(CSVRecord row,String name) {
        return row.isMapped(name) && row.isSet(name) ? row.get(name).trim() : "";
    }
    private String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file),digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record SectionKey(int round,String uf,String municipalityCode,int zone,int section) { }
    private record Counts(String municipality,long a,long b,long c,Long d) { }
}
