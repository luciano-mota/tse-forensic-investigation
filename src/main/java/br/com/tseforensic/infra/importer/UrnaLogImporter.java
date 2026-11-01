package br.com.tseforensic.infra.importer;

import br.com.tseforensic.domain.UrnaLogClassifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads 2026 -log.jez (ZIP containing logd.dat) from an official TSE package.
 * Does not store raw lines, voter IDs, CPF, titles, signatures or fingerprints.
 * The current classifier is deliberately conservative and not a forensic attestation.
 */
@Service
public class UrnaLogImporter {

    private static final Pattern FILENAME =
            Pattern.compile("(?i)^.*?[a-z][0-9]{5}([a-z]{2})([0-9]{5})([0-9]{4})([0-9]{4})-log\\.jez$");
    private final JdbcTemplate jdbc;

    public UrnaLogImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long importFile(Path input, int round) throws IOException {
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("Arquivo não encontrado: " + input);
        if (round != 1 && round != 2) throw new IllegalArgumentException("Turno inválido");
        Map<Key,UrnaLogClassifier.Summary> collected = new HashMap<>();
        String name=input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith("-log.jez")) {
            ingestJez(Files.newInputStream(input),name,round,collected);
        } else if (name.endsWith(".zip")) {
            try (ZipInputStream outer=new ZipInputStream(Files.newInputStream(input))) {
                ZipEntry entry;
                while ((entry=outer.getNextEntry())!=null) {
                    if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith("-log.jez")) {
                        // Prevent closing the containing archive while processing one entry.
                        InputStream borrowed=new FilterInputStream(outer) {
                            @Override public void close() { }
                        };
                        ingestJez(borrowed,entry.getName(),round,collected);
                    }
                    outer.closeEntry();
                }
            }
        } else {
            throw new IllegalArgumentException("Esperado .zip de urnas 2026 ou um arquivo -log.jez");
        }
        if (collected.isEmpty()) throw new IllegalArgumentException("Nenhum log de urna válido encontrado");
        String hash=sha256(input);
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM import_batch WHERE sha256=?",
                Integer.class,hash);
        if (exists == null || exists==0) jdbc.update(
                "INSERT INTO import_batch(sha256,filename,row_count) VALUES(?,?,?)",
                hash,input.getFileName().toString(),collected.size());
        Set<String> scopes=new HashSet<>();
        collected.keySet().forEach(k -> scopes.add(k.round+"|"+k.uf));
        for (String scope:scopes) {
            String[] pair=scope.split("\\|");
            jdbc.update("DELETE FROM section_log_audit WHERE election_round=? AND uf=?",
                    Integer.parseInt(pair[0]),pair[1]);
            jdbc.update("DELETE FROM section_biometric WHERE election_round=? AND uf=?",
                    Integer.parseInt(pair[0]),pair[1]);
        }
        jdbc.batchUpdate("""
            INSERT INTO section_log_audit(election_round,uf,municipality_code,zone,section,
                 votes_computed,biometric_recognized,biometric_failed,no_enrollment,
                 unclassified,after_17_local,log_files,source_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,collected.entrySet(),500,(s,e)->{
            Key k=e.getKey(); UrnaLogClassifier.Summary v=e.getValue();
            s.setInt(1,k.round);s.setString(2,k.uf);s.setString(3,k.municipalityCode);
            s.setInt(4,k.zone);s.setInt(5,k.section);
            s.setLong(6,v.counted());s.setLong(7,v.recognized());
            s.setLong(8,v.manualFailed());s.setLong(9,v.noEnrollment());
            s.setLong(10,v.unknown());s.setLong(11,v.after17Local());
            s.setLong(12,v.files());s.setString(13,hash);
        });
        jdbc.batchUpdate("""
            INSERT INTO section_biometric(election_round,uf,municipality_code,zone,section,
                biometric_recognized,no_biometric_enrollment,biometric_failed,
                operator_authorizations,source_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,collected.entrySet(),500,(s,e)->{
            Key k=e.getKey();UrnaLogClassifier.Summary v=e.getValue();
            s.setInt(1,k.round);s.setString(2,k.uf);s.setString(3,k.municipalityCode);
            s.setInt(4,k.zone);s.setInt(5,k.section);
            s.setLong(6,v.recognized());s.setLong(7,v.noEnrollment());
            s.setLong(8,v.manualFailed());
            s.setNull(9,java.sql.Types.BIGINT);s.setString(10,hash);
        });
        return collected.size();
    }

    private void ingestJez(InputStream source,String filename,int round,
                           Map<Key,UrnaLogClassifier.Summary> collected) throws IOException {
        String basename=filename.replace('\\','/');
        basename=basename.substring(basename.lastIndexOf('/')+1);
        Matcher match=FILENAME.matcher(basename);
        if (!match.matches()) throw new IllegalArgumentException("Log com nome inesperado: "+basename);
        Key key=new Key(round,match.group(1).toUpperCase(Locale.ROOT),
                String.valueOf(Integer.parseInt(match.group(2))),
                Integer.parseInt(match.group(3)),Integer.parseInt(match.group(4)));
        UrnaLogClassifier classifier=new UrnaLogClassifier(round);
        boolean read=false;
        try (ZipInputStream jez=new ZipInputStream(source)) {
            ZipEntry nested;
            while ((nested=jez.getNextEntry()) != null) {
                if (!nested.isDirectory() && nested.getName().toLowerCase(Locale.ROOT).endsWith("logd.dat")) {
                    if (read) throw new IllegalArgumentException("Mais de um logd.dat no arquivo "+basename);
                    read=true;
                    BufferedReader reader=new BufferedReader(new InputStreamReader(jez,StandardCharsets.ISO_8859_1));
                    String line;
                    long lines=0;
                    while ((line=reader.readLine())!=null) {
                        if (++lines>500_000) throw new IllegalArgumentException("Log excede limite de linhas: "+basename);
                        classifier.accept(line);
                    }
                }
                jez.closeEntry();
            }
        }
        if (!read) throw new IllegalArgumentException("logd.dat ausente em "+basename);
        collected.merge(key,classifier.summary(),UrnaLogClassifier.Summary::plus);
    }

    private String sha256(Path input) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new DigestInputStream(Files.newInputStream(input),digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }

    private record Key(int round,String uf,String municipalityCode,int zone,int section) {}
}
