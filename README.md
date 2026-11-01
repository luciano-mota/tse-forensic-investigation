# TSE Forensic Investigation

Plataforma **independente e não oficial** para exploração e conferência reproduzível dos dados públicos das eleições presidenciais brasileiras de 2026. Nenhum resultado é exibido sem ingestão de arquivos.

> **Princípio metodológico:** diferença de percentuais, divergência entre pesquisa e apuração, ou anomalia estatística não comprovam fraude. A consistência entre dados publicados também não comprova, isoladamente, a integridade do software executado na urna.

## O que já está implementado (MVP)

- Dashboard dark responsivo com mapa interativo das UFs (geometria pública do IBGE), placar e recortes por estado e município.
- Alternância 1º/2º turnos e comparação *descritiva* de participação eleitoral de cada candidatura entre os dois turnos.
- API REST `/api/v1`, camada de serviço/domínio/repositório, Spring Boot 3 + Java 21.
- Ingestão local de CSV/ZIP oficial de votação por seção, com leitura em streaming, agregação por município, transações, deduplicação e SHA-256 registrado.
- PostgreSQL + Flyway, execução Docker compatível com Render e build automático no GitHub Actions.
- Conferência dos agregados com o total nacional publicado pelo TSE em 26/10/2026, **somente quando houver cobertura territorial completa**.
- Nenhum resultado fictício e nenhuma classificação automática de fraude.

## Limites atuais — leia antes de publicar resultados

Este é um **primeiro incremento funcional**, e não uma auditoria conclusiva.

1. O importador inicialmente aceita arquivos no layout `votacao_secao_2026_*.csv` do conjunto oficial **Resultados — 2026 — Votação por seção eleitoral**. Exige `NR_TURNO`, `SG_UF`, `CD_MUNICIPIO`, `NM_MUNICIPIO`, `CD_CARGO` ou `DS_CARGO`, `NR_VOTAVEL` e `QT_VOTOS`. Verifique o cabeçalho real e a codificação antes de importar. Outros layouts de CSV/BU/RDV precisam de adaptadores específicos.
2. Importações devem conter o **snapshot completo de cada par UF/turno** presente no arquivo: uma nova importação substitui os agregados daquela UF/turno. Não use arquivos parciais.
3. O SHA-256 identifica exatamente o arquivo importado, mas a comparação com o **hash oficialmente publicado pelo TSE** ainda é uma verificação externa/manual. Não use a existência do SHA como prova de autenticidade da fonte.
4. As agregações publicadas *não* conferem individualmente RDV versus BU, não analisam logs de urna e não validam assinatura digital ou código-fonte. Esses módulos estão previstos.
5. As variações calculadas pela API são descritivas e não inferem troca individual de votos, fraude ou causalidade. A participação no 1º turno é calculada sobre todos os candidatos com votos válidos daquele turno.
6. O mapa obtém os limites territoriais diretamente da API do IBGE via navegador. Se o serviço externo estiver indisponível, a interface apresenta seleção alternativa das UFs.
7. O TSE utiliza códigos eleitorais próprios para municípios; o mapa inicial representa UFs, enquanto os municípios são explorados em tabela. Um mapa municipal detalhado exigirá correspondência documentada com códigos IBGE.

## Fontes oficiais

- [Resultados — 2026](https://dadosabertos.tse.jus.br/dataset/resultados-2026)
- [Resultados — 2026 — Boletins de urna](https://dadosabertos.tse.jus.br/dataset/resultados-2026-boletim-de-urna)
- [Arquivos de urna para totalização: BU, RDV, logs](https://dadosabertos.tse.jus.br/dataset/resultados-2026-arquivos-transmitidos-para-totalizacao)
- [Auditoria e fiscalização do TSE](https://www.tse.jus.br/eleicoes/urna-eletronica/seguranca-da-urna/como-realizar-auditoria)
- [Malhas territoriais — IBGE](https://servicodados.ibge.gov.br/api/docs/malhas?versao=3)

## Execução local

Requer Java 21, Maven 3.9+ e PostgreSQL 16+.

```bash
docker run --name tse-postgres -e POSTGRES_DB=tse_forensic \
  -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgres:16
mvn spring-boot:run
```

Acesse `http://localhost:8080/`.

### Ingestão de dados (processo controlado, sem endpoint público)

Baixe o CSV de **Presidente — Votação por seção eleitoral — 2026** ou arquivos estaduais compatíveis diretamente do TSE; confira manualmente o checksum oficial antes de executar. Se o arquivo estiver em UTF-8, altere `FORENSIC_IMPORT_CHARSET=UTF-8`.

```bash
mvn -DskipTests package

FORENSIC_IMPORT_PATH=/dados/votacao_secao_2026_BR.csv \
FORENSIC_IMPORT_CHARSET=ISO-8859-1 \
java -jar target/tse-forensic-investigation-0.1.0.jar \
  --spring.main.web-application-type=none
```

O processo lê somente o arquivo indicado ou os CSV/ZIP de um diretório local, faz as inserções em transação e encerra; não há upload anônimo. `FORENSIC_IMPORT_PATH` vazio desativa ingestão no início da aplicação web.

## Endpoints REST

| Método | Caminho | Descrição |
|---|---|---|
| GET | `/api/v1/elections/overview?round=2` | Totalização sobre dados importados |
| GET | `/api/v1/elections/states?round=1` | Resultados por UF |
| GET | `/api/v1/elections/states/MA/municipalities?round=2` | Resultados municipais |
| GET | `/api/v1/elections/comparison?uf=MA&minVotes=500&thresholdPp=15` | Variações descritivas por município |
| GET | `/api/v1/audits/national-reference` | Compara o segundo turno com a referência nacional TSE de 26/10; exige cobertura integral das 27 UFs + exterior |
| GET | `/api/v1/sources` | Proveniência SHA-256 das importações |
| GET | `/api/v1/methodology` | Limitações declaradas |
| GET | `/actuator/health` | Healthcheck |

## Deploy Render

O projeto contém `Dockerfile` multi-stage, `render.yaml` e healthcheck. Crie um PostgreSQL no Render e configure os segredos `JDBC_DATABASE_URL` (formato `jdbc:postgresql://host:5432/database`), `DB_USER`, `DB_PASSWORD`; não publique credenciais no GitHub. O serviço web começa sem dados até a ingestão em processo administrativo controlado. Para produção, prefira importar com tarefa privada/worker e armazenamento persistente de entrada; o filesystem efêmero do web service não deve ser tratado como depósito probatório.

## Arquitetura

```text
src/main/java/br/com/tseforensic
├── api/                 # contrato HTTP REST versionado
├── domain/              # operações puras e modelos
├── service/             # casos de uso de análise
└── infra/
    ├── importer/        # adapter de arquivos oficiais CSV/ZIP
    └── repository/      # adapter JDBC PostgreSQL
src/main/resources
├── db/migration/        # esquema versionado Flyway
└── static/              # dashboard sem build de front-end
```

A API apenas lê dados publicamente; operações de importação são administrativas/offline. A aplicação não possui credenciais do TSE e não acessa sistemas restritos.

Consulte [protocolo de auditoria](docs/INVESTIGATION_PROTOCOL.md) e [backlog técnico](docs/ROADMAP.md).

## Auditorias por seção — incremento 2

Novas tabelas: `section_turnout`, `section_biometric`, `section_vote`.
- **Comparecimento:** `FORENSIC_TURNOUT_PATH=/dados/detalhe_votacao_secao_2026.zip`.
  Importa por seção os campos `NR_TURNO, SG_UF, CD_MUNICIPIO, NM_MUNICIPIO, NR_ZONA, NR_SECAO, CD_CARGO, QT_APTOS, QT_COMPARECIMENTO, QT_ABSTENCOES` somente para Presidente.
- **Votos por seção:** `FORENSIC_SECTION_VOTES_PATH=/dados/votacao_secao_2026.zip` para conferir `votos válidos <= comparecimento` por seção. Esse teste NÃO é recontagem por RDV ou comparação BU digital.
- **Biometria derivada:** `FORENSIC_BIOMETRIC_PATH=/dados/biometria_agregada_validada.csv`. Este é um formato *normalizado derivado de logs*, **não** um CSV oficial fornecido diretamente pelo TSE. Só importe após documentar a extração e auditar a contagem dos eventos. Cabeçalho esperado: `NR_TURNO;SG_UF;CD_MUNICIPIO;NR_ZONA;NR_SECAO;QT_BIOMETRIA_RECONHECIDA;QT_SEM_CADASTRO_BIOMETRICO;QT_BIOMETRIA_FALHOU;QT_LIBERACOES_MESARIO`; a última coluna é opcional.
- Todos os arquivos devem conter snapshots completos por UF/turno; uma nova importação substitui aquele escopo, por transação.
- **Nenhum dos novos módulos afirma identificar o eleitor ou o voto individual; o sigilo do voto permanece protegido.**

**Novas rotas**: `GET /api/v1/audits/turnout`, `/turnout/comparison`, `/turnout/sections?uf=MA&round=2&limit=100&offset=0`, `/biometrics`, `/biometrics/comparison`, `/sections/reconciliation?uf=MA`.

**Render:** serviço web pode usar banco H2 efêmero apenas para exibir a interface vazia (sem dados); para ingerir e publicar resultados, configure as variáveis privadas `JDBC_DATABASE_URL`, `DB_USER` e `DB_PASSWORD` com o PostgreSQL persistente. Não use H2 para produzir relatórios probatórios.

### BWEB oficial: não reconhecimento biométrico

A variável `FORENSIC_BWEB_PATH=/dados/bweb_2t_2026_MA.zip` importa o BWEB de origem oficial, com `QT_ELEI_BIOM_SEM_HABILITACAO`, `QT_COMPARECIMENTO` e chaves UF/município/zona/seção/turno. Um arquivo deve cobrir integralmente cada par UF/turno que contém; importações substituem o mesmo escopo.

A taxa calculada é **falha de habilitação biométrica em eleitor com biometria cadastrada / comparecimento**, não o total de votantes sem biometria. O BWEB não fornece evidência de assinatura manuscrita individual nem associação do voto a um eleitor.

Endpoints `GET /api/v1/audits/biometrics/bweb?round=2&uf=MA` e `GET /api/v1/audits/biometrics/bweb/comparison?uf=MA&municipalityCode=...`.

### Logs da urna 2026: classificação preliminar de eventos

`FORENSIC_LOG_PATH=/dados/pacote_logs_2026_MA.zip FORENSIC_LOG_ROUND=2` aceita um ZIP de arquivos `*-log.jez` oficiais de 2026 ou um único `*-log.jez`, cada um com `logd.dat` (latin-1). Importação *offline*, sem API pública de upload.

O importador considera somente eventos VOTA na data do turno, distingue habilitação por biometria, solicitação de habilitação manual após falha, confirmação biográfica sem biometria cadastrada e eventos não classificáveis, **contando sessões cujo voto foi efetivamente computado**. Exclui identificadores e mensagens completas do banco.

- Não há identificação do votante e **não há associação ao candidato escolhido**.
- São preservados: total de votos computados no log, contagens por categoria, não classificados, eventos após 17h **do relógio local da urna**, número de arquivos e SHA-256.
- Para a análise depois das 17h do horário de Brasília, a conversão de fusos e os ajustes de relógio das urnas **ainda não estão implementados**; não interpretar o contador local como horário oficial de encerramento.
- Seções com mais de um log (contingência) exigem reconciliação manual. A soma bruta pode incluir eventos repetidos; a API sinaliza essas seções.
- As contagens preliminares devem ser confrontadas com os campos do BWEB, o comparecimento e as ocorrências da mesa.
- Os dados de biometria derivados dos logs substituem o mesmo escopo UF/turno do importador de agregados normalizados para evitar números misturados.
- Código de município é normalizado como número decimal sem zeros à esquerda, para casar com o nome codificado nos arquivos de log.

API: `GET /api/v1/audits/urna-logs/overview?round=2&uf=MA` e
`GET /api/v1/audits/urna-logs/bweb-reconciliation?round=2&uf=MA`.

## Sincronizador automático do TSE e acervo histórico

Desde o incremento 3, o workflow `.github/workflows/tse-catalog-sync.yml` consulta os três conjuntos oficiais do TSE via CKAN a cada 6 horas UTC. Não é necessário baixar arquivos manualmente para **monitorar o catálogo**.

Cada consulta grava um evento UTC na branch exclusiva `archive-history`, e qualquer mudança de metadados cria um snapshot com conteúdo imutável. Assim, mudanças no catálogo não são apagadas, e commits da branch de histórico não provocam redeploy da aplicação web.

**Originais ZIP/CSV**: para gravar cópias dos bytes, habilite um bucket S3 compatível (recomenda-se Cloudflare R2 dedicado). No repositório GitHub, configure `TSE_ARCHIVE_BUCKET`, `TSE_ARCHIVE_ENDPOINT`, `TSE_ARCHIVE_REGION`, `TSE_ARCHIVE_ACCESS_KEY_ID` e `TSE_ARCHIVE_SECRET_ACCESS_KEY` em **Actions Secrets**; configure as mesmas variáveis privadas no serviço web do Render para assinar downloads.

Arquivos baixados recebem SHA-256 calculado e checksum oficial SHA-512 verificado quando a correspondente `.sha512` é encontrada. A cópia será sempre preservada em chave derivada do hash, não substituindo versão anterior. Limites iniciais conservadores: até quatro arquivos de até 250 MiB a cada seis horas. Arquivos maiores ficam pendentes, com indicação de cobertura incompleta.

Endpoints:
- `GET /api/v1/archive/history`: lista pública do acervo, horário da consulta e status de cópias.
- `GET /api/v1/archive/original?snapshot=snapshots/...<sha256>.json`: redireciona para URL privada assinada por 10 minutos **somente se o original foi armazenado com êxito**.

Não confundir **metadata version** com **versão de bytes**; um repositório pode anunciar mudança sem disponibilizar outro binário e vice-versa. Uma versão eliminada antes da primeira coleta bem-sucedida não pode ser recuperada retrospectivamente.

Veja também [manual do arquivo](archive/README.md) e [protocolo forense](docs/INVESTIGATION_PROTOCOL.md).

## Modo gratuito: leitura periódica diretamente do TSE

O projeto agora possui uma modalidade **sem bucket R2/S3** que recebe temporariamente os arquivos nacionais `votacao_candidato_munzona_2026.zip` e `detalhe_votacao_munzona_2026.zip`, ambos obtidos de URLs oficializadas pelo catálogo CKAN do TSE. O GitHub Actions **não conserva os ZIPs**: lê seus CSVs com `csv.DictReader`, filtra `CD_CARGO=1`/Presidente, separa turnos e municípios, calcula votos de cada candidato, aptos, comparecimento e abstenções e apaga os temporários ao final. O procedimento é agendado a cada **6 horas**, portanto **não equivale a apuração em tempo real instantâneo**. Pode haver atrasos, indisponibilidade e cache do CDN; todos precisam ser relatados.

O pipeline guarda apenas agregados JSON compactos e hashes SHA-256 dos bytes efetivamente recebidos (não há cópia binária verificável). A primeira extração gera `archive/derived/current.json` e `archive/derived/versions/<hash>/atlas.json` na branch `archive-history`. Cada versão nova de arquivo original cria nova chave; versões antigas dos **dados derivados** não são sobrescritas. O endpoint `GET /api/v1/live/atlas` expõe esses agregados à interface quando a base local está vazia. A UI oferece mapa, votos por UF/município, comparação 1º×2º turno e abstenção municipal. Comparação por seção, biometria e reconstrução RDV continuam exigindo fontes próprias e **não aparecem como confirmadas**.

Validações: origem HTTPS TSE, limites de tamanho e número de linhas, filtro presidencial, UFs válidas, ausência de duplicatas de zona/candidato, cobertura das 27 UFs em ambos os turnos, totalizadores oficiais de referência reportados separadamente. Quando o arquivo é rejeitado, uma versão parcial não substitui uma válida. Não associar qualquer diferença isolada a irregularidades.

Os metadados do catálogo continuam monitorados com registro de cada consulta na branch `archive-history`. A ausência de cópia ZIP significa que só podemos reproduzir o mesmo processamento enquanto o arquivo ainda estiver disponível no TSE. Para custódia forense duradoura, armazenamento imutável de **originais** permanece recomendado.
