# TSE Forensic — Experiência editorial / Atlas

## Princípio

A aplicação é uma **mesa de investigação**, não um painel administrativo de cartões. A interface segue uma narrativa livre por dados e recortes, enquanto o usuário se move por um **índice de deriva** flutuante, compacto e acessível, sem barra lateral.

A cartografia tem prioridade; os totais do recorte são exibidos na mesma superfície visual, em vez de seções isoladas. As métricas aparecem como sinais contínuos e os módulos posteriores formam trilhas de investigação, não cards numerados.

## Percurso

- Introdução tipográfica: o que fazemos e limites da inferência.
- Atlas principal: mapa oficial por UF, seletor de turno, filtros e placar carregado.
- Sinais: votos válidos, candidaturas, unidades e lotes, sempre indicando se dados foram efetivamente importados.
- Leitura comparada: comparação dos turnos, municípios.
- Presença e identificação: abstenção, logs e indicador BWEB, sem dados pessoais.
- Arquivo do tempo: recursos oficiais encontrados, versões de catálogo e originais preservados, quando disponíveis.
- Epílogo metodológico: cadeia de custódia, limites e código aberto.

## Cores, voz e comportamento

- Fundo carvão profundo `#060c11`, tinta `#dce9ed`, marcadores azul-petróleo, verde-água `#96e8df`, roxo suave apenas como categoria de dados.
- Tipografia Space Grotesk + DM Sans, com legendas monoespaçadas para rastreabilidade.
- Sem narrativa acusatória, sem falsas métricas, sem simulações apresentadas como apuração.
- Foco por teclado evidente, texto de estado vazio, atalho de pular para o atlas, navegação de âncoras, versão reduzida de animações e legenda do mapa.
- Índice flutuante responsivo; em telas pequenas, rótulos curtos e módulos empilhados.
- A página funciona com os mesmos endpoints REST existentes, em `/api/v1`, sem endpoints falsos.

## Diferenciação

Não reproduz código, marca, textos, arranjos ou assets de serviços de apuração. Usa linguagem de atlas documental, ritmo editorial, linhas topográficas e pistas de proveniência como identidade própria. A referência pública serviu somente para validar que a navegação centrada no mapa é valiosa.
