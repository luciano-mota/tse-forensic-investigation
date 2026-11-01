package br.com.tseforensic.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Neutral technical and legal-context report over a verified immutable snapshot.
 * This is NOT a court expert opinion or a determination of election fraud.
 */
@Service
public class ElectionPdfReportService {
    private static final Color BG = new Color(6, 12, 17);
    private static final Color WHITE = new Color(231, 242, 241);
    private static final Color MUTED = new Color(162, 189, 195);
    private static final Color AQUA = new Color(150, 232, 223);
    private static final Color RED = new Color(223, 69, 87);
    private static final Color BLUE = new Color(54, 141, 215);
    private static final Color BORDER = new Color(48, 78, 89);
    private static final Color SAND = new Color(229, 192, 139);
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm 'BRT'", Locale.forLanguageTag("pt-BR"))
                    .withZone(ZoneId.of("America/Sao_Paulo"));
    private static final Locale BRAZIL = Locale.forLanguageTag("pt-BR");
    private static final String TSE_SOURCE = "https://dadosabertos.tse.jus.br/dataset/resultados-2026";
    private static final String TSE_NORMS = "https://www.tse.jus.br/eleicoes/eleicoes-2026-content/normas-e-documentacoes";
    private static final String TSE_AUDIT = "https://www.tse.jus.br/legislacao/compilada/res/2026/resolucao-no-23-769-de-3-de-agosto-de-2026";
    private static final String CONSTITUTION = "https://www4.planalto.gov.br/legislacao/portal-legis/legislacao-1/constituicao";
    private final LiveElectionMirrorService mirror;

    public ElectionPdfReportService(LiveElectionMirrorService mirror) { this.mirror = mirror; }

    public byte[] generate(String uf, String municipalityCode) {
        String state = uf == null ? "" : uf.trim().toUpperCase(Locale.ROOT);
        String code = municipalityCode == null ? "" : municipalityCode.trim();
        if (!state.isEmpty() && !state.matches("(?:AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO|ZZ)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UF inválida");
        }
        if (!code.isEmpty() && (state.isEmpty() || !code.matches("[0-9]{1,10}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Município inválido");
        }
        JsonNode atlas = mirror.current();
        if (!"DERIVED_FROM_OFFICIAL_FILES_NOT_ORIGINAL_ARCHIVE".equals(atlas.path("status").asText())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sem dados oficiais validados para emitir um relatório");
        }
        String version = atlas.path("derived_version").asText();
        if (!version.matches("[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Snapshot oficial sem versão auditável");
        }
        Scope first = readScope(atlas.path("rounds").path("1"), state, code);
        Scope second = readScope(atlas.path("rounds").path("2"), state, code);
        if (first == null || second == null || first.valid() == 0 || second.valid() == 0
                || first.registered() == 0 || second.registered() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Dados completos dos dois turnos indisponíveis neste recorte");
        }
        String name = state.isEmpty() ? "Brasil" : (code.isEmpty() ? state : first.scopeName());
        if (code.isEmpty() && !state.isEmpty()) name = ufName(state) + " (" + state + ")";
        try (PDDocument doc = new PDDocument()) {
            doc.getDocumentInformation().setTitle("TSE Forensic | Relatório técnico e contextual - " + name);
            doc.getDocumentInformation().setAuthor("TSE Forensic Investigation | análise independente de dados públicos");
            doc.getDocumentInformation().setSubject("Análise reprodutível das eleições presidenciais de 2026");
            PdfWriter w = new PdfWriter(doc);
            cover(w, atlas, first, second, name, version, state, code);
            methods(w, atlas, first, second, name);
            numeric(w, first, second, name);
            interpretation(w, atlas, first, second, name, state.isEmpty());
            legal(w, atlas, first, second, name);
            provenance(w, atlas, version);
            w.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível compor o PDF técnico", e);
        }
    }

    private void cover(PdfWriter w, JsonNode atlas, Scope a, Scope b, String name,
                       String version, String uf, String code) throws IOException {
        w.newPage("RELATÓRIO TÉCNICO / 2026", true);
        w.spacer(44);
        w.label("TSE  /  FORENSIC  /  INVESTIGATION", AQUA);
        w.spacer(16);
        w.heading("Cada dado deixa", 37, WHITE);
        w.heading("um vestígio.", 37, AQUA);
        w.spacer(22);
        w.paragraph("RELATÓRIO DE ANÁLISE ELEITORAL DESCRITIVA", BOLD, 13, WHITE, 18);
        w.paragraph("Recorte geográfico: " + name, REGULAR, 12, WHITE, 20);
        w.paragraph("Eleição presidencial brasileira de 2026 | primeiro e segundo turnos", REGULAR, 11, MUTED, 19);
        w.rule();
        w.spacer(15);
        w.kv("Votos válidos | 1º turno", fmt(a.valid()));
        w.kv("Votos válidos | 2º turno", fmt(b.valid()));
        w.kv("Abstenção | 1º turno", rate(a.absent(), a.registered()) + "%");
        w.kv("Abstenção | 2º turno", rate(b.absent(), b.registered()) + "%");
        w.kv("Mudança da abstenção", deltaRate(a, b) + " p.p.");
        w.spacer(17);
        w.paragraph("SINTESE: " + conclusion(a, b, name), BOLD, 11, AQUA, 17);
        w.spacer(12);
        w.paragraph("Este documento é um relatório técnico de dados públicos, não uma perícia judicial, "
                + "não certifica a integridade das urnas e não conclui pela ocorrência de fraude.",
                REGULAR, 10, SAND, 15);
        w.spacer(18);
        w.label("IDENTIFICAÇÃO REPRODUZÍVEL", AQUA);
        w.paragraph("Snapshot: " + version, REGULAR, 9, MUTED, 13);
        w.paragraph("Captura/extração: " + timestamp(atlas.path("observed_at").asText()), REGULAR, 10, MUTED, 17);
        w.paragraph("Emissão deste relatório: " + DATE.format(Instant.now()), REGULAR, 10, MUTED, 15);
        w.spacer(9);
        w.link("Consultar dados de origem no Portal do TSE", TSE_SOURCE);
    }

    private void methods(PdfWriter w, JsonNode atlas, Scope a, Scope b, String name) throws IOException {
        w.section("01", "Objeto, método e cadeia de transformação");
        w.paragraph("FINALIDADE", BOLD, 11, AQUA, 18);
        w.paragraph("Examinar, de modo reproduzível, os agregados oficiais da votação presidencial dos dois turnos de 2026 "
                + "no recorte " + name + ". O objetivo é descrever participação, votos nominais, abstenção, "
                + "diferenças percentuais e verificações aritméticas, sem inferir conduta individual.", REGULAR, 11, WHITE, 17);
        w.spacer(10);
        w.paragraph("PROCEDIMENTO COMPUTACIONAL", BOLD, 11, AQUA, 17);
        w.numbered("1", "Consulta automatizada ao catálogo CKAN do Portal de Dados Abertos do TSE e identificação dos recursos oficiais.");
        w.numbered("2", "Transferência temporária dos ZIPs nacionais de votação nominal por município/zona e detalhe da apuração.");
        w.numbered("3", "Cálculo do SHA-256 dos bytes efetivamente recebidos, registro de horário e verificação de integridade de formato.");
        w.numbered("4", "Leitura streaming do CSV nacional canônico; rejeição de duplicidades e filtros por cargo Presidente (CD_CARGO=1).");
        w.numbered("5", "Agrupamento por turno, UF, município e candidatura; cálculo de aptos, comparecimento, abstenções e votos nominais.");
        w.numbered("6", "Conferência de cobertura das 27 UFs e comparação nacional de votos do segundo turno com referência oficial.");
        w.numbered("7", "Produção de JSON derivado imutável com identificador de versão. Os arquivos ZIP são descartados.");
        w.spacer(8);
        w.rule();
        w.paragraph("GRANULARIDADE E LIMITAÇÃO", BOLD, 11, AQUA, 18);
        w.paragraph("A base aqui utilizada é agregada por município e zona eleitoral. Não inclui verificação individual "
                + "do eleitor, biometria, assinaturas, votos individualizados ou auditoria de software. "
                + "Uma diferença entre totais não demonstra, por si só, causa, irregularidade ou fraude.",
                REGULAR, 11, WHITE, 18);
        w.paragraph("Cobertura de municípios com comparecimento: 1º turno " + fmt(a.covered())
                + " / " + fmt(a.municipalities()) + "; 2º turno " + fmt(b.covered())
                + " / " + fmt(b.municipalities()) + ".", REGULAR, 10, MUTED, 16);
        w.spacer(8);
        w.paragraph("Observação de qualidade: o TSE informa que 39 registros vinculados a transferências temporárias "
                + "permaneceram também nas seções de origem no conjunto nacional de aptos. "
                + "As taxas deste relatório usam o denominador bruto do CSV para evitar ajuste não documentado por recorte.",
                REGULAR, 10, SAND, 16);
    }

    private void numeric(PdfWriter w, Scope a, Scope b, String name) throws IOException {
        w.section("02", "Resultados eleitorais e cálculos");
        w.paragraph("RECORTE: " + name.toUpperCase(Locale.ROOT), BOLD, 10, AQUA, 16);
        w.kv("Aptos | 1º / 2º turno", fmt(a.registered()) + " / " + fmt(b.registered()));
        w.kv("Comparecimento | 1º / 2º", fmt(a.turnout()) + " / " + fmt(b.turnout()));
        w.kv("Abstenções | 1º / 2º", fmt(a.absent()) + " / " + fmt(b.absent()));
        w.kv("Votos nominais válidos | 1º / 2º", fmt(a.valid()) + " / " + fmt(b.valid()));
        w.rule();
        w.spacer(11);
        w.paragraph("FÓRMULAS E SUBSTITUIÇÕES", BOLD, 11, AQUA, 17);
        w.formula("Taxa de abstenção no 1º turno",
                "100 x " + fmt(a.absent()) + " / " + fmt(a.registered()), rate(a.absent(), a.registered()) + "%");
        w.formula("Taxa de abstenção no 2º turno",
                "100 x " + fmt(b.absent()) + " / " + fmt(b.registered()), rate(b.absent(), b.registered()) + "%");
        w.formula("Variação percentual em pontos percentuais",
                rate(b.absent(), b.registered()) + " - " + rate(a.absent(), a.registered()), deltaRate(a, b) + " p.p.");
        w.formula("Variação absoluta de comparecimento",
                fmt(b.turnout()) + " - " + fmt(a.turnout()), signed(b.turnout()-a.turnout()) + " eleitores");
        w.formula("Variação absoluta de abstenções",
                fmt(b.absent()) + " - " + fmt(a.absent()), signed(b.absent()-a.absent()) + " registros");
        w.spacer(17);
        w.paragraph("VOTOS NOMINAIS POR CANDIDATURA", BOLD, 11, AQUA, 17);
        w.tableHeader("NÚMERO", "1º TURNO", "2º TURNO", "PARTICIPAÇÃO 2º");
        TreeMap<String, Long> union = new TreeMap<>(a.candidates());
        for (String n : b.candidates().keySet()) union.putIfAbsent(n, 0L);
        for (String n : union.keySet()) {
            long av = a.candidates().getOrDefault(n,0L), bv=b.candidates().getOrDefault(n,0L);
            w.tableRow(n, fmt(av), fmt(bv), bv==0?"-":rate(bv,b.valid())+"%");
        }
        w.spacer(14);
        w.paragraph("A participação de uma candidatura é 100 x votos nominais / votos válidos do respectivo turno. "
                + "Não se pode inferir migração individual de votos comparando totais agregados entre turnos.",
                REGULAR, 10, MUTED, 16);
    }

    private void interpretation(PdfWriter w, JsonNode atlas, Scope a, Scope b,
                                String name, boolean national) throws IOException {
        w.section("03", "Interpretação estatística e qualidade");
        w.paragraph("CONCLUSÃO DESCRITIVA", BOLD, 11, AQUA, 19);
        w.paragraph(conclusion(a,b,name), BOLD, 13, WHITE, 19);
        w.spacer(8);
        w.paragraph("FUNDAMENTAÇÃO MATEMÁTICA", BOLD, 11, AQUA, 18);
        w.paragraph("O primeiro turno possui " + fmt(a.turnout()) + " registros de comparecimento, enquanto o segundo "
                + "possui " + fmt(b.turnout()) + ". A diferença é " + signed(b.turnout()-a.turnout()) + ". "
                + "As taxas de abstenção são " + rate(a.absent(),a.registered()) + "% e "
                + rate(b.absent(),b.registered()) + "%; a mudança é " + deltaRate(a,b)
                + " ponto(s) percentual(is). Essa leitura é quantitativa e não prova a causa da variação.",
                REGULAR, 11, WHITE, 18);
        w.spacer(9);
        w.paragraph("CONFERÊNCIA APTO - PRESENTE - AUSENTE", BOLD, 11, AQUA, 18);
        long d1=a.registered()-a.turnout()-a.absent(),d2=b.registered()-b.turnout()-b.absent();
        if (d1==0 && d2==0) {
            w.paragraph("Os agregados deste recorte fecham aritmeticamente em ambos os turnos: "
                    + "aptos = comparecimento + abstenções.", REGULAR, 11, AQUA, 17);
        } else {
            w.paragraph("Há diferença aritmética não reconciliada. No primeiro turno: " + signed(d1)
                    + " registro(s). No segundo turno: " + signed(d2)
                    + " registro(s). Não é possível atribuir causa sem avaliar as regras de transferência, "
                    + "habilitação, totalização e os arquivos primários de cada seção.",
                    REGULAR, 11, SAND, 18);
        }
        w.spacer(10);
        w.paragraph("CONTROLE DE CONVERGÊNCIA NACIONAL", BOLD, 11, AQUA, 18);
        String reference=atlas.path("national_reference").path("status").asText();
        w.paragraph(national ? (
            "MATCHES_TSE_PUBLISHED_TOTALS".equals(reference)
             ? "Os votos nominais totais de segundo turno convergem com a referência nacional do TSE cadastrada "
               + "na versão de processamento. Isso valida a soma dentro do escopo e não o software eleitoral."
             : "A conferência dos totais nacionais exige revisão dos dados e do manifesto de transformação.")
            : "O controle nacional não é uma auditoria independente de cada município ou zona. "
              + "O recorte apresentado deve ser conferido com relatórios e boletins locais.",
            REGULAR, 11, WHITE, 18);
        w.spacer(9);
        w.paragraph("CLASSIFICAÇÃO DO ACHADO", BOLD, 11, AQUA, 17);
        w.paragraph("Leitura descritiva sustentada por agregados oficiais. "
                + "Classificação de fraude: NÃO DETERMINADA. "
                + "Possível inconsistência de dados: a apurar somente se uma discrepância continuar "
                + "após reconciliação documental independente.", REGULAR, 11, SAND, 18);
    }

    private void legal(PdfWriter w, JsonNode atlas, Scope a, Scope b, String name) throws IOException {
        w.section("04", "Enquadramento jurídico-eleitoral e probatório");
        w.paragraph("NATUREZA DO DOCUMENTO", BOLD, 11, AQUA, 19);
        w.paragraph("Trata-se de relatório técnico independente produzido sobre dados eleitorais públicos. "
                + "Não equivale a laudo pericial judicial ou auditoria oficial da Justiça Eleitoral. "
                + "A conclusão de ilicitude depende de fatos específicos, prova idônea, contraditório, competência, "
                + "procedimento adequado e apreciação da autoridade competente.",
                REGULAR, 11, WHITE, 18);
        w.spacer(9);
        w.paragraph("REFERÊNCIAS NORMATIVAS", BOLD, 11, AQUA, 18);
        w.numbered("A", "Constituição Federal, art. 14: soberania popular, sufrágio universal e voto direto e secreto. "
                + "É o fundamento constitucional do processo eleitoral.");
        w.numbered("B", "Código Eleitoral (Lei nº 4.737/1965) e Lei das Eleições (Lei nº 9.504/1997): "
                + "regime legal eleitoral. A aplicação de regras concretas exige exame de fatos e jurisprudência.");
        w.numbered("C", "Resolução TSE nº 23.673/2021 e alterações de 2026, inclusive Resolução nº 23.769/2026: "
                + "regras de fiscalização e auditoria do sistema eletrônico de votação.");
        w.numbered("D", "Lei nº 13.709/2018 (LGPD): eventual tratamento de dados pessoais deve observar "
                + "finalidade, minimização, segurança e base legal apropriada.");
        w.spacer(8);
        w.paragraph("O QUE UM ACHADO PODE E NÃO PODE SIGNIFICAR", BOLD, 11, AQUA, 18);
        w.paragraph("Uma mudança de abstenção, uma variação percentual ou uma divergência aritmética "
                + "constitui, no máximo, uma observação a verificar. Não prova identificação irregular, "
                + "votação por terceiro, adulteração de urna ou resultado fraudulento. Para avaliar uma hipótese "
                + "específica são necessários dados independentes, contexto normativo e confirmação técnica.",
                REGULAR, 11, WHITE, 18);
        w.spacer(12);
        w.paragraph("PROCEDIMENTO EM CASO DE DIVERGÊNCIA PERSISTENTE", BOLD, 11, AQUA, 18);
        w.numbered("1", "Reproduzir a transformação com os arquivos, hashes, versões e filtros indicados.");
        w.numbered("2", "Confrontar os agregados com arquivos oficiais por seção, boletins de urna e logs, "
                + "respeitando regras especiais do cadastro e de transferência temporária.");
        w.numbered("3", "Documentar o escopo exato, materialidade, hipótese alternativa, eventuais erros de ETL "
                + "e grau de certeza do achado.");
        w.numbered("4", "Submeter os fatos a avaliação técnica e jurídica especializada; prazos, legitimidade "
                + "e instrumento processual dependem do caso.");
        w.spacer(11);
        w.paragraph("ATENÇÃO: interpretação jurídico-contextual, não aconselhamento jurídico individual. "
                + "O presente relatório não imputa crime ou responsabilidade a pessoa, partido ou órgão.",
                REGULAR, 10, SAND, 17);
        w.link("Normas e resoluções do TSE para as eleições de 2026", TSE_NORMS);
        w.link("Texto constitucional oficial (art. 14)", CONSTITUTION);
        w.link("Resolução TSE nº 23.769/2026", TSE_AUDIT);
    }

    private void provenance(PdfWriter w, JsonNode atlas, String version) throws IOException {
        w.section("05", "Fontes, hashes, rastreabilidade e limitações");
        w.paragraph("METADADOS DA COLETA", BOLD, 11, AQUA, 18);
        w.kv("Versão derivada SHA-256", shortHash(version));
        w.paragraph("Versão completa: " + version, REGULAR, 9, MUTED, 15);
        w.kv("Horário do snapshot", timestamp(atlas.path("observed_at").asText()));
        w.kv("Objeto analisado", "Presidente / município-zona / turnos 1 e 2");
        w.spacer(13);
        JsonNode sources = atlas.path("sources");
        sources.fields().forEachRemaining(entry -> {
            try {
                JsonNode s = entry.getValue();
                w.paragraph("ARQUIVO " + entry.getKey().toUpperCase(Locale.ROOT), BOLD, 11, AQUA, 16);
                w.paragraph("Identificador de recurso: " + s.path("id").asText("não informado"),
                        REGULAR, 9, MUTED, 15);
                w.paragraph("SHA-256 original: " + s.path("sha256").asText("não disponível"),
                        REGULAR, 9, WHITE, 15);
                w.paragraph("Tamanho recebido: " + fmt(s.path("size").asLong()) + " bytes",
                        REGULAR, 10, MUTED, 15);
                w.paragraph("Extração: " + timestamp(s.path("downloaded_at").asText()),
                        REGULAR, 10, MUTED, 15);
                String url=s.path("url").asText();
                if (url.startsWith("https://") && URI.create(url).getHost().endsWith(".tse.jus.br")) {
                    w.link("Abrir arquivo original do TSE",url);
                }
                w.spacer(10);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        w.rule();
        w.spacer(9);
        w.paragraph("REPRODUTIBILIDADE", BOLD, 11, AQUA, 16);
        w.paragraph("Qualquer pessoa pode obter os recursos atuais do TSE, verificar os hashes quando os mesmos "
                + "bytes ainda estiverem disponíveis, filtrar o cargo Presidente e refazer as agregações "
                + "documentadas. A aplicação guarda as versões dos dados DERIVADOS, mas não preserva "
                + "cópias históricas dos ZIPs. Uma publicação original substituída no TSE pode não ser recuperável.",
                REGULAR, 11, WHITE, 18);
        w.spacer(9);
        w.paragraph("LIMITES DO ESCOPO", BOLD, 11, AQUA, 16);
        w.paragraph("Este relatório não analisa assinatura eletrônica das urnas, cadeia de custódia dos "
                + "boletins individuais, RDV, registros de habilitação biométrica, livros de votação, "
                + "intenção dos eleitores, autoria de atos ou provas judiciais. "
                + "Resultados estatísticos não são, por si sós, demonstração de fraude.",
                REGULAR, 11, WHITE, 18);
        w.spacer(16);
        w.link("Portal de Dados Abertos do TSE | Resultados 2026", TSE_SOURCE);
        w.link("Código-fonte e histórico de transformações", "https://github.com/luciano-mota/tse-forensic-investigation");
        w.link("Metadados e versões derivadas da coleta",
                "https://github.com/luciano-mota/tse-forensic-investigation/tree/archive-history/archive/derived");
        w.spacer(16);
        w.paragraph("RELATÓRIO GERADO AUTOMATICAMENTE A PARTIR DO SNAPSHOT VALIDADO. "
                + "Nenhum dado fictício foi introduzido para preencher lacunas.",
                BOLD, 10, AQUA, 18);
    }

    private static Scope readScope(JsonNode rounds, String uf, String code) {
        if (!rounds.isObject()) return null;
        long registered=0,turnout=0,absent=0,covered=0,municipalities=0;
        String scopeName=code;
        Map<String,Long> candidates=new TreeMap<>();
        List<JsonNode> regions=new ArrayList<>();
        if (!uf.isEmpty()) {
            if (!rounds.has(uf)) return null;
            regions.add(rounds.path(uf));
        } else rounds.elements().forEachRemaining(regions::add);
        for (JsonNode region:regions) {
            JsonNode cities=region.path("municipalities");
            if (!cities.isObject()) return null;
            var iter=cities.fields();
            while (iter.hasNext()) {
                var entry=iter.next();
                if(!code.isEmpty() && !code.equals(entry.getKey()))continue;
                JsonNode city=entry.getValue();
                scopeName=city.path("name").asText(scopeName);
                municipalities++;
                for (var cand=city.path("candidates").fields();cand.hasNext();) {
                    var item=cand.next();
                    candidates.merge(item.getKey(),item.getValue().asLong(),Long::sum);
                }
                JsonNode t=city.path("turnout");
                if(t.isObject()) {
                    registered+=t.path("registered").asLong();
                    turnout+=t.path("turnout").asLong();
                    absent+=t.path("abstentions").asLong();
                    covered++;
                }
            }
        }
        long valid=candidates.values().stream().mapToLong(Long::longValue).sum();
        return municipalities==0?null:new Scope(registered,turnout,absent,valid,
                municipalities,covered,candidates,scopeName);
    }
    private static String conclusion(Scope a,Scope b,String name) {
        double change=100.0*b.absent()/b.registered()-100.0*a.absent()/a.registered();
        return "Em " + name + ", a taxa de abstenção " +
                (change>0?"aumentou":change<0?"diminuiu":"permaneceu estável") +
                " entre os dois turnos (" + deltaRate(a,b) + " p.p.). "
                + "O comparecimento variou " + signed(b.turnout()-a.turnout())
                + " registros. Esse resultado descreve os totais e não identifica causa ou fraude.";
    }
    private static String rate(long numerator,long denominator) {
        return denominator<=0?"não aplicável":NumberFormat.getNumberInstance(BRAZIL).format(
                BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP));
    }
    private static String deltaRate(Scope a,Scope b) {
        BigDecimal first=BigDecimal.valueOf(a.absent()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(a.registered()),2,RoundingMode.HALF_UP);
        BigDecimal second=BigDecimal.valueOf(b.absent()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(b.registered()),2,RoundingMode.HALF_UP);
        return NumberFormat.getNumberInstance(BRAZIL).format(second.subtract(first));
    }
    private static String fmt(long x) { return NumberFormat.getIntegerInstance(BRAZIL).format(x); }
    private static String signed(long x) {return (x>0?"+":"")+fmt(x);}
    private static String shortHash(String x){return x.length()>22?x.substring(0,22)+"...":x;}
    private static String timestamp(String iso) {
        try {return DATE.format(Instant.parse(iso));}
        catch(Exception e){return iso.isBlank()?"não informado":iso;}
    }
    private static String ufName(String uf) {
        return Map.ofEntries(Map.entry("MA","Maranhão"),Map.entry("SP","São Paulo"),
                Map.entry("RJ","Rio de Janeiro"),Map.entry("MG","Minas Gerais"),
                Map.entry("BA","Bahia"),Map.entry("PA","Pará"),Map.entry("DF","Distrito Federal"))
                .getOrDefault(uf,uf);
    }
    private record Scope(long registered,long turnout,long absent,long valid,
                         long municipalities,long covered, Map<String,Long> candidates,String scopeName){}

    private static final class PdfWriter {
        private static final float LEFT=46,RIGHT=46,PAGE_W=PDRectangle.A4.getWidth();
        private static final float BOTTOM=65;
        private final PDDocument doc;
        private PDPage page;
        private PDPageContentStream cs;
        private float y;
        private int pageNo=0;
        PdfWriter(PDDocument doc){this.doc=doc;}
        void finish() throws IOException {if(cs!=null)cs.close();}
        void newPage(String code,boolean cover) throws IOException {
            if(cs!=null)cs.close();
            page=new PDPage(PDRectangle.A4);
            doc.addPage(page);
            pageNo++;
            cs=new PDPageContentStream(doc,page);
            cs.setNonStrokingColor(BG);
            cs.addRect(0,0,PAGE_W,PDRectangle.A4.getHeight());
            cs.fill();
            cs.setNonStrokingColor(AQUA);
            cs.addRect(LEFT,PDRectangle.A4.getHeight()-52,25,3);
            cs.fill();
            text(LEFT,PDRectangle.A4.getHeight()-75,"◉  TSE FORENSIC  /  ELEIÇÕES 2026",BOLD,11,AQUA);
            text(LEFT,PDRectangle.A4.getHeight()-91,code,REGULAR,8,MUTED);
            cs.setStrokingColor(BORDER);cs.setLineWidth(.8f);
            cs.moveTo(LEFT,47);cs.lineTo(PAGE_W-RIGHT,47);cs.stroke();
            text(LEFT,31,"FONTE: TSE / ANÁLISE INDEPENDENTE / NÃO É LAUDO JUDICIAL",REGULAR,7,MUTED);
            text(PAGE_W-RIGHT-43,31,"PÁG. "+pageNo,REGULAR,8,AQUA);
            y=cover?PDRectangle.A4.getHeight()-143:PDRectangle.A4.getHeight()-124;
        }
        void ensure(float needed) throws IOException {
            if(y-needed<BOTTOM)newPage("CONTINUAÇÃO  /  EVIDÊNCIAS",false);
        }
        void spacer(float h) throws IOException {ensure(h);y-=h;}
        void text(float x,float y,String v,PDType1Font font,float size,Color color) throws IOException {
            cs.beginText();cs.setNonStrokingColor(color);cs.setFont(font,size);
            cs.newLineAtOffset(x,y);cs.showText(clean(v));cs.endText();
        }
        void heading(String value,float size,Color color) throws IOException {
            ensure(size+15);text(LEFT,y,value,BOLD,size,color);y-=size+9;
        }
        void label(String value,Color color) throws IOException {
            ensure(16);text(LEFT,y,value,BOLD,9,color);y-=18;
        }
        void section(String num,String title) throws IOException {
            if(y<350)newPage("SEÇÃO "+num,false);
            else spacer(20);
            label("SEÇÃO "+num+"  /  RELATÓRIO REPRODUZÍVEL",AQUA);
            paragraph(title,BOLD,23,WHITE,29);spacer(14);rule();spacer(21);
        }
        void rule() throws IOException {
            ensure(4);cs.setStrokingColor(BORDER);cs.setLineWidth(1);
            cs.moveTo(LEFT,y);cs.lineTo(PAGE_W-RIGHT,y);cs.stroke();y-=7;
        }
        void kv(String key,String value) throws IOException {
            ensure(25);
            text(LEFT,y,key,REGULAR,10,MUTED);
            float tw=BOLD.getStringWidth(clean(value))/1000f*11;
            text(PAGE_W-RIGHT-Math.min(tw,250),y,value,BOLD,11,WHITE);
            y-=25;
        }
        void paragraph(String value,PDType1Font font,float size,Color color,float leading) throws IOException {
            float max=PAGE_W-LEFT-RIGHT;
            List<String> lines=wrap(value,font,size,max);
            for(String line:lines){
                ensure(leading);
                text(LEFT,y,line,font,size,color);
                y-=leading;
            }
        }
        void numbered(String number,String content) throws IOException {
            ensure(20);text(LEFT,y,number,BOLD,11,AQUA);
            float max=PAGE_W-LEFT-RIGHT-30;
            for(String line:wrap(content,REGULAR,10,max)){
                ensure(16);
                text(LEFT+27,y,line,REGULAR,10,WHITE);y-=16;
            }
            spacer(7);
        }
        void formula(String name,String expression,String result) throws IOException {
            ensure(65);
            text(LEFT,y,name,BOLD,10,AQUA);y-=17;
            text(LEFT,y,expression,REGULAR,10,WHITE);y-=16;
            text(LEFT,y,"Resultado: "+result,BOLD,10,SAND);y-=24;
        }
        void tableHeader(String a,String b,String c,String d) throws IOException {
            ensure(22);
            text(LEFT,y,a,BOLD,9,AQUA);text(LEFT+118,y,b,BOLD,9,AQUA);
            text(LEFT+237,y,c,BOLD,9,AQUA);text(LEFT+350,y,d,BOLD,9,AQUA);y-=19;
        }
        void tableRow(String a,String b,String c,String d) throws IOException {
            ensure(20);
            text(LEFT,y,a,REGULAR,10,WHITE);text(LEFT+118,y,b,REGULAR,10,WHITE);
            text(LEFT+237,y,c,REGULAR,10,WHITE);text(LEFT+350,y,d,REGULAR,10,WHITE);y-=19;
        }
        void link(String label,String url) throws IOException {
            ensure(24);
            String txt=clean(label)+"  >";
            float size=10f;
            text(LEFT,y,txt,REGULAR,size,AQUA);
            PDAnnotationLink annotation=new PDAnnotationLink();
            PDRectangle rectangle=new PDRectangle();
            rectangle.setLowerLeftX(LEFT);rectangle.setLowerLeftY(y-3);
            rectangle.setUpperRightX(Math.min(PAGE_W-RIGHT,
                    LEFT+REGULAR.getStringWidth(txt)/1000f*size+2));
            rectangle.setUpperRightY(y+11);
            annotation.setRectangle(rectangle);
            PDActionURI action=new PDActionURI();action.setURI(url);
            annotation.setAction(action);
            page.getAnnotations().add(annotation);
            y-=24;
        }
        private static List<String> wrap(String input, PDType1Font font,float size,float width) throws IOException{
            List<String> out=new ArrayList<>();
            StringBuilder line=new StringBuilder();
            for(String raw:clean(input).split("\\s+")){
                StringBuilder part=new StringBuilder();
                for(int i=0;i<raw.length();i++){
                    String next=part.toString()+raw.charAt(i);
                    if(!part.isEmpty() && font.getStringWidth(next)/1000f*size>width){
                        String chunk=part.toString();
                        if(!line.isEmpty()){out.add(line.toString());line=new StringBuilder();}
                        out.add(chunk);part=new StringBuilder();
                    }
                    part.append(raw.charAt(i));
                }
                String word=part.toString();
                String candidate=line.isEmpty()?word:line+" "+word;
                if(font.getStringWidth(candidate)/1000f*size>width && !line.isEmpty()){
                    out.add(line.toString());line=new StringBuilder(word);
                }else line=new StringBuilder(candidate);
            }
            if(!line.isEmpty())out.add(line.toString());
            return out;
        }
        private static String clean(String value) {
            if(value==null)return "";
            return value.replace("◉","O").replace("→","->").replace("×","x")
                    .replace("–","-").replace("—","-").replace("−","-")
                    .replace("º","o").replace("ª","a").replace("’","'")
                    .replace("“","\"").replace("”","\"");
        }
    }
}
