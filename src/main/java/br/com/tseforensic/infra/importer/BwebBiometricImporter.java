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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class BwebBiometricImporter {

    private final JdbcTemplate jdbc;

    public BwebBiometricImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long importFile(Path path, Charset charset) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Arquivo ausente: " + path);
        String hash = hash(path);
        Map<Key, Count> records = new HashMap<>();
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
                ZipEntry e;
                while ((e=zip.getNextEntry()) != null) {
                    if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".csv"))
                        parse(zip,charset,records);
                    zip.closeEntry();
                }
            }
        } else {
            try (InputStream stream=Files.newInputStream(path)) {
                parse(stream,charset,records);
            }
        }
        if (records.isEmpty()) throw new IllegalArgumentException("Nenhuma seção presidencial no BWEB.");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM import_batch WHERE sha256=?",
                Integer.class, hash);
        if (exists == null || exists == 0)
            jdbc.update("INSERT INTO import_batch(sha256,filename,row_count) VALUES(?,?,?)",
                    hash,path.getFileName().toString(),records.size());
        Set<String> scopes = new HashSet<>();
        for (Key k:records.keySet()) scopes.add(k.round+"|"+k.uf);
        for (String scope:scopes) {
            String[] values = scope.split("\\|");
            jdbc.update("DELETE FROM bweb_biometric_nonrecognition WHERE election_round=? AND uf=?",
                    Integer.parseInt(values[0]),values[1]);
        }
        jdbc.batchUpdate("""
          INSERT INTO bweb_biometric_nonrecognition
          (election_round,uf,municipality_code,zone,section,turnout,not_recognized,source_sha256)
          VALUES(?,?,?,?,?,?,?,?)
          """,records.entrySet(),500,(s,e) -> {
            Key k=e.getKey(); Count count=e.getValue();
            s.setInt(1,k.round); s.setString(2,k.uf); s.setString(3,k.municipalityCode);
            s.setInt(4,k.zone); s.setInt(5,k.section);
            s.setLong(6,count.turnout); s.setLong(7,count.notRecognized);
            s.setString(8,hash);
        });
        return records.size();
    }

    private void parse(InputStream in,Charset charset,Map<Key,Count> records) throws IOException {
        CSVParser csv = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader()
                .setSkipHeaderRecord(true).setTrim(true).setIgnoreHeaderCase(true).get()
                .parse(new InputStreamReader(in,charset));
        if (!csv.getHeaderMap().containsKey("DS_CARGO_PERGUNTA") &&
            !csv.getHeaderMap().containsKey("CD_CARGO_PERGUNTA") &&
            !csv.getHeaderMap().containsKey("CD_CARGO")) {
            throw new IllegalArgumentException("BWEB sem coluna de cargo; importação bloqueada.");
        }
        for (CSVRecord row:csv) {
            String office = field(row,"DS_CARGO_PERGUNTA");
            String officeId = field(row,"CD_CARGO_PERGUNTA");
            if (!"Presidente".equalsIgnoreCase(office)
                    && !"1".equals(officeId)
                    && !"1".equals(field(row,"CD_CARGO"))) continue;
            int round = number(row,"NR_TURNO");
            if (round != 1 && round != 2) continue;
            Key key=new Key(round,required(row,"SG_UF").toUpperCase(Locale.ROOT),
                    required(row,"CD_MUNICIPIO"),number(row,"NR_ZONA"),number(row,"NR_SECAO"));
            Count value=new Count(quantity(row,"QT_COMPARECIMENTO"),
                    quantity(row,"QT_ELEI_BIOM_SEM_HABILITACAO"));
            if (value.notRecognized > value.turnout)
                throw new IllegalArgumentException("Sem habilitação biométrica excede comparecimento: "+key);
            Count earlier=records.putIfAbsent(key,value);
            if (earlier != null && !earlier.equals(value))
                throw new IllegalArgumentException("BWEB inconsistente na mesma seção: "+key);
        }
    }
    private int number(CSVRecord row,String key) {return Math.toIntExact(quantity(row,key));}
    private long quantity(CSVRecord row,String key) {
        try {
            long n=Long.parseLong(required(row,key));
            if(n<0)throw new NumberFormatException("negative");
            return n;
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("Coluna inválida "+key+" linha "+row.getRecordNumber(),e);
        }
    }
    private String required(CSVRecord row,String key) {
        String value=field(row,key);
        if(value.isBlank())throw new IllegalArgumentException("Campo ausente "+key+" linha "+row.getRecordNumber());
        return value;
    }
    private String field(CSVRecord row,String key) {
        return row.isMapped(key)&&row.isSet(key)?row.get(key).trim():"";
    }
    private String hash(Path path) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new DigestInputStream(Files.newInputStream(path),digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private record Key(int round,String uf,String municipalityCode,int zone,int section) {}
    private record Count(long turnout,long notRecognized) {}
}
