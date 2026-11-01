package br.com.tseforensic.infra.importer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class UrnaLogImportRunner implements ApplicationRunner {

    private final UrnaLogImporter importer;
    private final String path;
    private final int round;

    public UrnaLogImportRunner(UrnaLogImporter importer,
            @Value("#{environment.getProperty('FORENSIC_LOG_PATH', '')}") String path,
            @Value("#{environment.getProperty('FORENSIC_LOG_ROUND', '2')}") int round) {
        this.importer=importer; this.path=path; this.round=round;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!path.isBlank()) importer.importFile(Path.of(path),round);
    }
}
