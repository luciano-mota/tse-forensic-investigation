package br.com.tseforensic.infra.importer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Path;

@Component
public class SectionVoteImportRunner implements ApplicationRunner {

    private final SectionVoteCsvImporter importer;
    private final String path;
    private final String charset;

    public SectionVoteImportRunner(SectionVoteCsvImporter importer,
            @Value("#{environment.getProperty('FORENSIC_SECTION_VOTES_PATH', '')}") String path,
            @Value("#{environment.getProperty('FORENSIC_IMPORT_CHARSET', 'ISO-8859-1')}") String charset) {
        this.importer = importer;
        this.path = path;
        this.charset = charset;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!path.isBlank())
            importer.importFile(Path.of(path), Charset.forName(charset));
    }
}
