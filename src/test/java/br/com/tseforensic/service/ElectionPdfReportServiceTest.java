package br.com.tseforensic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import org.apache.pdfbox.pdmodel.PDPage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElectionPdfReportServiceTest {

    private static final String FIXTURE = """
      {
        "status":"DERIVED_FROM_OFFICIAL_FILES_NOT_ORIGINAL_ARCHIVE",
        "schema":1,
        "derived_version":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "observed_at":"2026-11-01T04:48:00Z",
        "national_reference":{"status":"MATCHES_TSE_PUBLISHED_TOTALS"},
        "sources":{
          "votes":{"id":"official-votes","url":"https://cdn.tse.jus.br/e2026/votes.zip",
                   "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "size":455000000,"downloaded_at":"2026-11-01T04:47:00Z"},
          "turnout":{"id":"official-turnout","url":"https://cdn.tse.jus.br/e2026/turnout.zip",
                     "sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                     "size":28000000,"downloaded_at":"2026-11-01T04:47:40Z"}
        },
        "rounds":{
          "1":{"MA":{"municipalities":{"12000":{"name":"CIDADE TESTE",
              "candidates":{"13":120,"22":80,"70":15},
              "turnout":{"registered":300,"turnout":240,"abstentions":60}}}}},
          "2":{"MA":{"municipalities":{"12000":{"name":"CIDADE TESTE",
              "candidates":{"13":160,"22":90},
              "turnout":{"registered":300,"turnout":270,"abstentions":30}}}}}
        }
      }
      """;

    private ElectionPdfReportService service(String json) throws Exception {
        JsonNode data = new ObjectMapper().readTree(json);
        LiveElectionMirrorService mirror = mock(LiveElectionMirrorService.class);
        when(mirror.current()).thenReturn(data);
        return new ElectionPdfReportService(mirror);
    }

    @Test
    void pdfIsReadableAndIncludesBrandedLegalStatisticalMethodAndEvidenceLinks() throws Exception {
        byte[] raw = service(FIXTURE).generate("MA","12000");
        assertTrue(raw.length > 6000);
        assertArrayEquals(new byte[]{'%', 'P', 'D', 'F'},Arrays.copyOf(raw,4));
        try (PDDocument pdf = Loader.loadPDF(raw)) {
            assertTrue(pdf.getNumberOfPages() >= 5);
            String contents = new PDFTextStripper().getText(pdf);
            assertTrue(contents.contains("CIDADE TESTE"));
            assertTrue(contents.contains("Absten"));
            assertTrue(contents.contains("MÉTODO") || contents.contains("método") ||
                       contents.contains("Método") || contents.contains("PROCEDIMENTO"));
            assertTrue(contents.contains("23.769/2026"));
            assertTrue(contents.contains("SHA-256"));
            assertTrue(contents.contains("Lei"));
            assertTrue(contents.contains("100 x 60 / 300"));
            assertTrue(contents.contains("100 x 30 / 300"));
            assertTrue(contents.contains("20%"));
            assertTrue(contents.contains("10%"));
            assertTrue(contents.contains("10 p.p."));
            assertTrue(contents.contains("NÃO DETERMINADA"));
            int links=0;
            for (PDPage page: pdf.getPages()) links+=page.getAnnotations().size();
            assertTrue(links >= 4);
        }
    }

    @Test
    void invalidScopeIsRejectedAndReportCannotInventData() throws Exception {
        ElectionPdfReportService reports=service(FIXTURE);
        assertThrows(ResponseStatusException.class,()->reports.generate("XX",""));
        assertThrows(ResponseStatusException.class,()->reports.generate("","12000"));
        assertThrows(ResponseStatusException.class,()->reports.generate("MA","9999"));
        assertThrows(ResponseStatusException.class,()->reports.generate("MA","1 OR 1=1"));
        assertThrows(ResponseStatusException.class,()->service("{}").generate("MA","12000"));
    }
}
