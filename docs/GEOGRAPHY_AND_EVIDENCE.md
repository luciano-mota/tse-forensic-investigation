# Atlas geográfico e interpretação verificável

## Navegação

O mapa é um SVG interativo de autoria do projeto, com geometria territorial local (GeoJSON), em vez de consultar a API de malhas do IBGE no navegador. Todos os **27 polígonos de UF** e os **5.570 municípios** (incluindo Brasília/DF no conjunto municipal) são fornecidos a partir de arquivos estáticos versionados em \`src/main/resources/static/geo\`. O usuário usa scroll para ampliar ou afastar, arrasta com mouse/dedo e usa pinça no mobile. Clique UF → malha municipal → clique município → zoom e votos municipais. Ícone "↶ Brasil" retorna à vista nacional.

**Fontes cartográficas:** os contornos estaduais/municipais foram gerados a partir de dados geográficos públicos do IBGE e disponibilizados em GeoJSON pelo repositório \`henriquemalvar/br-geojson\`. A geometria é ilustrativa e pode ser simplificada; não é instrumento de determinação de fronteira legal. Municípios são vinculados aos registros oficiais por **nome normalizado dentro da UF**; nome ausente ou ambíguo permanece sem dados eleitorais atribuídos. Um futuro incremento deverá construir correspondência auditável IBGE↔TSE.

## Fotografias

O painel dos resultados apresenta as fotografias das duas candidaturas do segundo turno (números 13 e 22) a partir de arquivos com atribuição disponível no Wikimedia Commons:
- Luiz Inácio Lula da Silva — Ricardo Stuckert / PR, fotografia de 09/07/2026, licença CC BY-SA 4.0: https://commons.wikimedia.org/wiki/File:09.07.2026_-_Presidente_Lula.jpg
- Flávio Bolsonaro — Tribunal Superior Eleitoral, foto de candidato 2026, licença CC BY 4.0: https://commons.wikimedia.org/wiki/File:2026_FLAVIO_BOLSONARO_CANDIDATO_PRESIDENTE_TSE_(280002551544)_(cropped).jpg

Quando uma foto não está validada, o sistema mostra **o número da candidatura**, nunca uma imagem inventada ou da pessoa errada. As cores representam identidades partidárias aproximadas para fins de navegação e **não influenciam as métricas**. O 1º turno mostra os nomes de todas as 12 candidaturas com o respectivo número/partido.

## Análise reprodutível

A seção "Evidências, não suposições" usa apenas as agregações derivadas dos ZIPs oficiais por município/zona, separados por turno, e é recalculada para Brasil, UF ou município:

- **Taxa de abstenção:** \`100 × QT_ABSTENCOES / QT_APTOS\` em cada turno, usando o denominador bruto do arquivo, sem ajuste oculto.
- **Diferença de taxas:** \`Taxa 2º turno − Taxa 1º turno\` em pontos percentuais (**p.p.**).
- **Variação no comparecimento:** \`QT_COMPARECIMENTO do 2º − QT_COMPARECIMENTO do 1º\` (pessoas).
- **Participação nominal:** \`100 × votos nominais da candidatura / votos válidos do turno\`.
- **Variação da participação:** diferença entre as participações (p.p.). **Não mede migração individual de votos**.
- **Margem entre candidatos:** diferença absoluta entre votos nominais no segundo turno e sua razão sobre os votos válidos.
- **Conferência aritmética:** \`aptos − comparecimento − abstenções\` com detalhamento da diferença e necessidade de explicação documental caso não zere.
- **Conferência externa:** apenas na escala nacional, compara votos registrados com valores de referência publicados pelo TSE; valores conferidos **não provam integridade do software da urna**.

Os alertas são descritivos: nenhum agregado sozinho revela identidade, fraude, intenção, biometria ou causalidade. O TSE documentou 39 registros vinculados duas vezes durante transferências temporárias no cadastro de aptos de 2026; por isso o sistema indica o denominador de registros do arquivo quando calcula taxas.

As fontes do TSE e seus SHA-256 são exibidos em cada leitura. As cópias ZIP originais são processadas temporariamente e descartadas. Histórico de dados derivados fica na branch \`archive-history\`.

### Verificações

Testes Python garantem a presença dos arquivos geográficos para 27 UFs e seus municípios. Os testes Node verificam as fórmulas, mudança de recorte e que dados incompletos não produzem interpretação. CI compila Java, verifica sintaxe JavaScript e executa regressões. A publicação no Render só deve ser declarada após status **LIVE** e logs de inicialização.
