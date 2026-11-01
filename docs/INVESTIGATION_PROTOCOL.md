# Protocolo de investigação reproduzível

## Hipóteses tecnicamente distintas

- **H1 — corrupção na totalização:** BU emitido difere do total exibido. Comparar arquivos da mesma seção e versão e a totalização publicada.
- **H2 — alteração antes da emissão do BU:** o software registraria voto diferente da seleção exibida. Comparar apenas BU e RDV, se ambos já contiverem a alteração, **não** detectaria esse cenário. Exige análise independente de código, assinaturas, cadeia de custódia, testes de integridade e evidências adicionais obtidas por vias autorizadas.
- **H3 — corrupção de publicação:** arquivo público ou dashboard diverge do registro oficial. Preservar cópias datadas, hashes e registros de retificação.
- **H4 — mudança entre turnos:** diferença de participação ou voto de candidatos não permite inferir fraude. Turnos têm composição eleitoral e denominadores diferentes.
- **H5 — discrepâncias pesquisas versus votos:** exige amostras, datas, metodologia, pesos, margem, voto provável e incerteza. Divergência não é prova de adulteração.

## Hierarquia e classificação

1. **Inconsistência determinística:** arquivos com o mesmo escopo e versão apresentam contagens diferentes; reportar com os dois artefatos, hashes, identificação da seção e regras utilizadas.
2. **Evidência operacional:** logs assinados ou arquivos de execução demonstram uma ação identificada; validar origem/autenticidade e eventuais explicações operacionais.
3. **Anomalia estatística:** diferença de frequência ou distribuição sem erro determinístico. Etiqueta **sinal para verificação**, nunca evidência conclusiva de manipulação.
4. **Sem observações suficientes:** dados faltantes, amostras incomparáveis ou método de coleta incompatível. Não chamar de conformidade.

## Custódia e reprodutibilidade

- Preservar fonte oficial, URL, identificação do recurso, data UTC, tamanho, SHA-256 do arquivo bruto, checksum oficial (quando disponível), código de análise, versão, parâmetros e logs.
- Documentar transformações (encoding, delimitador, normalização, deduplicação) e manter arquivo original imutável.
- Fazer conferência independente por mais de uma implementação quando houver achado crítico.
- Submeter contraprovas, hipóteses alternativas e crítica de pares antes de qualquer alegação pública.
- Não publicar dados sensíveis ou informações que possam identificar o voto individual de alguém.
- Para logs e executáveis não disponíveis publicamente, usar pedidos e auditorias autorizadas; não tentar acesso não autorizado.

## Verificações previstas (não implementadas no MVP)

- Comparação section-level: BU vs registros de totalização e resultado oficial por seção.
- Recontagem por RDV e comparação com BU da mesma urna, quando o formato e ferramenta oficial forem compatíveis.
- Verificação de assinaturas digitais e correspondência de versões autorizadas.
- Coerência cronológica de logs da urna / transportador / receptor / banco de totalização.
- Reconciliação de seções substituídas, anuladas, pendentes ou retificadas.
- Testes estatísticos predefinidos, análises de sensibilidade e controle de múltiplas comparações.
- Pesquisas: registro TSE, amostra, pesos, método, data, intervalo de confiança.
- Comparação independente com boletins físicos coletados por fiscais.

## Conclusões permitidas

Usar expressões como **divergência reproduzida**, **hipótese não testada**, **evidência insuficiente**, **conferência parcial**. Nunca concluir fraude pela existência de uma diferença estatística isolada. Nunca concluir integridade completa a partir de dados que não cobrem software e equipamento.

## H6 — suposição de habilitação sem identificação adequada

A hipótese de substituição indevida de eleitor exige identificar eventos de habilitação,
o tipo de validação (biometria, sem cadastro, falha de reconhecimento), evidências
documentais autorizadas e explicações alternativas. Não é possível inferir quem votou
nem associar escolhas eleitorais a esses eventos. CPF/título no caderno não são
prova de votação irregular: a identificação exige outras verificações da mesa.

Os registros no caderno e as atas não devem ser publicados com informações pessoais.
Solicitações de documentação devem respeitar a LGPD, o sigilo do voto e o acesso legal.

## H7 — relato de local com pouca circulação

Filas ausentes em determinado horário não estimam comparecimento diário.
Testar variação turno a turno nas mesmas zonas e seções com aptos, comparecimento,
abstenção e possíveis mudanças de localização/contingência; quando disponível,
usar eventos horários agregados dos logs sem identificar eleitores.
