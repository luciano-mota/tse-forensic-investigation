package br.com.tseforensic.infra.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component
public class ImportRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportRunner.class);
    private final TseCsvImporter importer;
    private final String sourcePath;

    public ImportRunner(TseCsvImporter importer,
                        @Value("${forensic.import.path:}") String sourcePath) {
        this.importer = importer;
        this.sourcePath = sourcePath;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!StringUtils.hasText(sourcePath)) {
            return;
        }
        Path input = Path.of(sourcePath);
        if (Files.isRegularFile(input)) {
            importer.importFile(input);
            return;
        }
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("FORENSIC_IMPORT_PATH não existe: " + input);
        }
        try (Stream<Path> paths = Files.list(input)) {
            List<Path> sources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().matches("(?i).*\\.(csv|zip)"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path path : sources) {
                LOGGER.info("Importando snapshot: {}", path.getFileName());
                importer.importFile(path);
            }
        }
    }
}
