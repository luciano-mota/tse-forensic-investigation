# Roadmap de auditoria (incremental)

## Entregue na branch do MVP

- [x] Bootstrap Java 21 / Spring Boot 3 / PostgreSQL / Flyway e Docker/Render
- [x] Estrutura REST versionada `/api/v1`
- [x] Importador de CSV/ZIP de votação por seção para agregações eleitorais municipais
- [x] SHA-256 local por arquivo e importações idempotentes
- [x] Conferência com snapshot publicado do resultado nacional (apenas cobertura completa)
- [x] Dashboard dark com mapa UF, visão nacional/municipal, dois turnos
- [x] Tabela de mudanças de participação por município, com ressalvas
- [x] Fluxo GitHub Actions para build/testes

## Próximos incrementos para auditoria técnica completa

- [ ] Integração automatizada com catálogo CKAN do TSE (manifesto de recursos, ETag, tamanho, data e hashes oficiais).
- [ ] Testes de ingestão com amostra real preservada dos CSVs de 2026; validar schema, encoding e cabeçalho por recurso.
- [ ] Armazenamento de votos por **seção**, não apenas município, para reconciliação determinística.
- [ ] Importadores específicos do BU digital, RDV e logs; verificar assinaturas e versões conforme documentação oficial.
- [ ] Comparador de seção: BU versus votação publicada, trilha de artefatos e classificação dos achados.
- [ ] Códigos municipais TSE ↔ IBGE e mapa municipal.
- [ ] Comparação com quantitativos de aptos, comparecimento, abstenção, brancos e nulos, distinguindo voto válido de comparecimento.
- [ ] Análise pré-registrada de padrões estatísticos com controles para múltiplos testes.
- [ ] Módulo de pesquisas registradas (fonte, metodologia, peso, intervalo de confiança e cenário).
- [ ] Exportação assinada de relatório técnico CSV/PDF e reprodutibilidade em CLI.
- [ ] Gestão de importações com versão, rollback, dataset snapshot e relatórios de qualidade.
- [ ] Testes end-to-end visuais e regras de acessibilidade; cache de geometrias do IBGE.
- [ ] Controle de acesso para tarefas administrativas, sem expor o importador ao público.
- [ ] Revisão de segurança, performance em milhões de linhas e proteção contra consumo excessivo de recursos.

Critério de entrega dos itens futuros: testes determinísticos + documentos de fonte + revisão humana + limites explícitos no painel.

## Incremento 2 — comparecimento e identificação eleitoral
- [x] Persistência por seção de aptos, comparecimento e abstenções e comparação de turnos
- [x] Identificação de mudanças descritivas de abstenção nas mesmas seções entre 1º e 2º turno
- [x] Modelo de agregados de eventos biométricos por seção e API de comparação (entrada derivada; parser direto de logs ainda pendente)
- [x] Importador por seção de voto nominal/BU CSV para detecção preliminar de votos válidos acima do comparecimento
- [x] Dashboard de abstenção e biometria com estados explícitos para dados faltantes
- [x] Ingestão nativa de indicador BWEB `QT_ELEI_BIOM_SEM_HABILITACAO` por seção e comparação entre turnos
- [x] Leitor preliminar de logs oficiais 2026 `-log.jez` com classificação conservadora e testes sintéticos
- [ ] Validar a classificação com logs reais, eventos alternativos, falhas de relógio, contingências e urnas substituídas (módulo não é ainda auditoria certificada)
- [ ] Recontagem RDV e confronto criptográfico com BU original
- [ ] Comparação entre registros da mesa e logs agregados mediante procedimentos legais; nenhuma coleta de CPF ou assinatura individual
- [ ] Verificação de exportações TSE de comparecimento por seção com amostra real, inclusive exterior e transferências

## Incremento 3 — sincronização automatizada e histórico
- [x] Consulta recorrente CKAN TSE em três conjuntos eleitorais de 2026
- [x] Registro histórico independente de consultas e versões de metadados na branch archive-history
- [x] Captura opcional de originais em S3/R2 com chave imutável por SHA-256 e verificação oficial SHA-512 quando disponível
- [x] API de listagem e link de download com assinatura temporária; histórico exibido no dashboard
- [ ] Configurar bucket R2 exclusivo e segredos de GitHub Actions + serviço Render
- [ ] Validar primeiro ciclo de sincronização e consistência do catálogo com resposta real CKAN
- [ ] Expandir captura com orçamento e política de armazenamento para arquivos maiores que 250 MiB
- [ ] Importar automaticamente lotes verificados ao PostgreSQL persistente após confirmação de escopo/layout

## Incremento 4 — modo sem armazenamento de originais
- [x] Leitor streaming de dois recursos nacionais do TSE, com ZIP temporário, hash, filtros por turno/cargo e limites
- [x] Geração de JSON enxuto e histórico de versões dos dados derivados no GitHub
- [x] Endpoint de espelho versionado e integração inicial com mapa, municípios, comparação e abstenção na UI
- [x] Validação mínima de cobertura das 27 UFs e teste de regressão sintético
- [ ] Validar execução agendada real com os dois ZIPs (possíveis erros HTTP do CDN e layout variável)
- [ ] Teste de tela end-to-end da versão Render efetivamente publicada
- [ ] Incluir dados oficiais de seções e biometria em processo adicional, sem extrapolar medidas de fontes diferentes
