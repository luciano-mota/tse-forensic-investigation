package br.com.tseforensic.infra.importer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Path;

@Component
public class SectionAuditImportRunner implements ApplicationRunner {

    private final SectionAuditCsvImporter importer;
    private final String turnout;
    private final String biometrics;
    private final String encoding;

    public SectionAuditImportRunner(SectionAuditCsvImporter importer,
            @Value("#{environment.getProperty('FORENSIC_TURNOUT_PATH', '')}") String turnout,
            @Value("#{environment.getProperty('FORENSIC_BIOMETRIC_PATH', '')}") String biometrics,
            @Value("#{environment.getProperty('FORENSIC_IMPORT_CHARSET', 'ISO-8859-1')}") String encoding) {
        this.importer = importer;
        this.turnout = turnout;
        this.biometrics = biometrics;
        this.encoding = encoding;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!turnout.isBlank())
            importer.importFile(Path.of(turnout), SectionAuditCsvImporter.Kind.TURNOUT, Charset.forName(encoding));
        if (!biometrics.isBlank())
            importer.importFile(Path.of(biometrics), SectionAuditCsvImporter.Kind.BIOMETRIC_AGGREGATE, Charset.forName(encoding));
    }
}
