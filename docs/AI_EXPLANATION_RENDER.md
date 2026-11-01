# IA explicativa opcional no Render gratuito

Provider inicial: OpenRouter (modelo configurável, padrão `openrouter/free`). Não hospeda LLM localmente.

## Ativação no Render
Em Web Service -> Environment configure `OPENROUTER_API_KEY` com chave criada em https://openrouter.ai/keys . Opcional: `OPENROUTER_MODEL=openrouter/free`. Nunca exponha a chave no JavaScript, no repositório ou em logs.

A interface envia ao backend um recorte da leitura determinística após o usuário clicar no botão. O backend encaminha JSON reduzido ao provedor externo. **Aviso**: os dados transmitidos, incluindo JSON e textos, serão processados por terceiros; não enviar informação pessoal ou sigilosa. Modelo e limites gratuitos variam, podem recusar requisições (429) e não há SLA. A aplicação continua funcionando sem chave, exibindo indisponibilidade somente no botão opcional.

## Limites e segurança
- Requisições HTTP limitadas a oito por minuto por instância (proteção inicial, não proteção distribuída).
- Tamanho da entrada limitado; token máximo de saída; timeout e falha controlada.
- Prompt instrui a não inferir voto individual nem fraude; a resposta do modelo é **não verificada**.
- **Antes de uso público intensivo**: implementar recálculo e busca da análise diretamente do backend a partir de dados oficiais, rate limiting por IP/identidade, limite diário persistido, cache por hash e validação determinística de números no texto gerado; evitar confiar no JSON fornecido pelo navegador.
- Não integrar texto gerado automaticamente ao PDF como conclusão jurídica/probatória sem revisão humana e rastreabilidade de modelo, versão, prompt, entrada e saída.

## Verificações pendentes
Executar Maven tests, E2E em viewport de 360/390/430px e tablet, conferir rolagem horizontal, seletores, mapa SVG, toque e pinça, endpoint com/sem chave e resposta 429, e validar custos/limites do provedor antes de publicação.
