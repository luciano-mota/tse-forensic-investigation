# Arquivo de consultas e originais TSE

O histórico é mantido na branch separada `archive-history`, que não aciona deploys da aplicação. A branch `main` contém código e documentação.

- `archive/index.json`: índice de versões disponíveis, origem dos dados, SHA da metadata e referências aos objetos.
- `archive/polls/YYYY/MM/DD/HHMMSS.json`: comprovantes da consulta (data UTC, itens vistos e falhas).
- `archive/snapshots/<dataset>/<resource_id>/<sha256>.json`: descrição do recurso conforme visto na consulta; cada snapshot é imutável.
- Arquivos ZIP/CSV originais ficam em um bucket R2/S3 separado **somente se os segredos estiverem configurados**, caso contrário serão exibidos como *não arquivados*. O código não finge que um snapshot de metadados equivale aos bytes históricos.

Ativação do armazenamento: cadastrar no GitHub Actions Secrets do repositório `TSE_ARCHIVE_BUCKET`, `TSE_ARCHIVE_ENDPOINT`, `TSE_ARCHIVE_REGION` (normalmente `auto` no R2), `TSE_ARCHIVE_ACCESS_KEY_ID` e `TSE_ARCHIVE_SECRET_ACCESS_KEY`. Não reutilizar bucket de outro produto. Configurar também no Render para permitir link de download privado assinado.

Política inicial: consulta a cada seis horas UTC; quatro novas cópias por execução, 250 MiB por arquivo; ajustar após monitorar cotas. O processo usa `SHA-256` dos bytes recebidos, compara `SHA-512` quando houver um arquivo de checksum correspondente, e usa ETag/Last-Modified condicionais quando disponíveis. Nunca sobrescreve um objeto de hash diferente. Falha de download não gera entrada de cópia realizada.

**Limitação:** não é possível recuperar uma versão apagada pelo TSE antes da primeira coleta bem-sucedida. A API do catálogo é CKAN, não uma garantia de preservação histórica.
