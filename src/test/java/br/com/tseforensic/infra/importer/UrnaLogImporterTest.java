package br.com.tseforensic.infra.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:forensic_log_ci;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "FORENSIC_IMPORT_PATH=",
        "FORENSIC_LOG_PATH="
})
class UrnaLogImporterTest {

    @Autowired
    private UrnaLogImporter importer;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path temp;

    @Test
    void parsesNestedOfficialStyleLogsWithoutPersistingIndividualVoterData() throws Exception {
        String log = "25/10/2026 08:01:00\tINFO\t123\tVOTA\tAguardando digitação do identificador do eleitor\tHASH\n"
                + "25/10/2026 08:01:10\tINFO\t123\tVOTA\tTipo de habilitação do eleitor [biométrica]\tHASH\n"
                + "25/10/2026 08:01:40\tINFO\t123\tVOTA\tO voto do eleitor foi computado\tHASH\n"
                + "25/10/2026 08:02:00\tINFO\t123\tVOTA\tAguardando digitação do identificador do eleitor\tHASH\n"
                + "25/10/2026 08:02:05\tINFO\t123\tVOTA\tSolicitação de dado pessoal do eleitor para habilitação manual\tHASH\n"
                + "25/10/2026 08:02:15\tINFO\t123\tVOTA\tAno de nascimento digitado é igual ao do cadastro\tHASH\n"
                + "25/10/2026 08:02:50\tINFO\t123\tVOTA\tO voto do eleitor foi computado\tHASH\n";

        ByteArrayOutputStream nestedBytes = new ByteArrayOutputStream();
        try (ZipOutputStream nested = new ZipOutputStream(nestedBytes)) {
            nested.putNextEntry(new ZipEntry("logd.dat"));
            nested.write(log.getBytes(StandardCharsets.ISO_8859_1));
            nested.closeEntry();
        }
        Path outer = temp.resolve("urnas.zip");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(outer))) {
            archive.putNextEntry(new ZipEntry("o03220ma0728500020088-log.jez"));
            archive.write(nestedBytes.toByteArray());
            archive.closeEntry();
        }
        assertThat(importer.importFile(outer, 2)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT votes_computed FROM section_log_audit WHERE uf='MA' AND zone=2 AND section=88",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT biometric_failed FROM section_log_audit WHERE uf='MA' AND zone=2 AND section=88",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT unclassified FROM section_log_audit WHERE uf='MA' AND zone=2 AND section=88",
                Long.class)).isZero();
    }
}
