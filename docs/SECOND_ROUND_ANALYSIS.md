# Análise de mudanças entre turnos — requisitos forenses

## Regra central
Mudanças agregadas por candidato, município ou seção **não revelam a escolha individual** de qualquer eleitor e não autorizam a classificação automática de fraude. Apoio público de candidato eliminado não equivale a compromisso de transferência dos eleitores.

## Reconciliação aritmética
Para cada recorte territorial, ambos os turnos devem apresentar, de arquivos TSE identificados por fonte e hash:
- eleitores aptos, comparecimento, abstenções;
- votos nominais por candidatura, brancos, nulos e demais categorias legalmente aplicáveis;
- total de votos contabilizados; resíduos de reconciliação e sinalização de cobertura incompleta.

Defina A_t como comparecimento no turno t; V_{c,t} como votos válidos de cada candidatura; B_t e N_t como brancos e nulos.

Calcule Delta(c)=V_{c,2}-V_{c,1}; Delta(A)=A_2-A_1; Delta(B), Delta(N).
Cheque A_t = soma_c V_{c,t} + B_t + N_t + outras categorias pertinentes, usando exatamente o mesmo escopo e cobertura. Nunca reportar erro de somatório se os conjuntos não tiverem abrangência equivalente.

No 1º turno, considere E_1=soma dos votos de candidaturas que não disputaram o segundo. Uma decomposição possível entre turnos inclui: (a) eleitores de candidaturas eliminadas, (b) brancos/nulos tornando-se válidos, (c) diferenças de comparecimento, (d) eleitores de finalistas trocando de preferência, (e) fluxos em direção oposta que se compensam. Delta(A) **não é** o número de novos votantes: eleitores diferentes podem comparecer nos dois turnos.

## Inferência e cenários
O aplicativo deverá exibir:
1. Evolução observada (exata, sem interpretação causal).
2. Limites matematicamente factíveis de matrizes de transição entre categorias (com estados ausente, branco, nulo, cada candidato), preservando margens dos dois turnos e universo de aptos harmonizado.
3. Cenários hipotéticos com premissas configuráveis e a indicação explícita 'simulação, não medição de transferência de eleitores'.
4. Comparação UF, município, zona e seção; controles de qualidade, cobertura e múltiplas comparações; nenhum índice automático de 'fraude'.
5. Fontes, IDs de recursos, datas UTC de ingestão, hashes e transformações em relatório PDF.

Não atribuir probabilidade às transições sem pesquisa de painel/estimador identificado, pressupostos documentados, incerteza e teste de sensibilidade. Usar retórica partidária e endossos como hipóteses contextuais, não como números de transferência de votos.

## IA opcional (não integrante da cadeia probatória)
Uma IA gratuita/local, por exemplo Ollama, pode auxiliar a **redigir explicações** dos JSONs de cálculos já auditados. Ela não deve inventar fatos, fontes, probabilidades ou executar cálculos que substituam o motor determinístico. A saída deve ser rotulada 'síntese automatizada sujeita à revisão', armazenando modelo, versão, prompt, timestamp, input, output e hash. Sem modelo disponível, relatórios continuam funcionando via template determinístico.

## Critérios de testes
- Testes algébricos de conservação em tabelas sintéticas, inclusive branco/nulo/ausente, variação de aptos e cobertura parcial.
- Teste de busca nacional para municípios homônimos, nomes acentuados, diferenças de caixa, UF não selecionada, índices carregando, falhas parciais de API e troca de turno.
- Testes UI: clicar em UF aproxima imediatamente; malha de municípios substitui UF depois da carga; clicar cidade seleciona resultado; zoom/pan/pinça/reset; mobile/desktop; filtros persistentes e mensagens de erro.
- Teste PDF: valores originais versus calculados, fonte linkada, limites e inexistência de conclusão causal automática.
