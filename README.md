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
