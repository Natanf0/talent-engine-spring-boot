# Busca de candidatos similares — serviço, endpoint e frontend

O ponto de entrada é `CandidateSimilarityService.findSimilar(CandidateDTO, Integer)`.
A busca está exposta por `POST /api/careermatcher/candidatos/similares` e integrada ao Banco de talentos no repositório vizinho `talent-engine-front-end`.
Matching candidato-vaga, rankers, Gale-Shapley e persistência de execuções não são utilizados nem alterados.

## Executar

```bash
./mvnw -Dtest='ForaTest,SimilarityServiceTest,SimilaritySnapshotRepositoryTest' test
```

Os testes de extração usam Neo4j Harness 5.26.0 local, sem banco externo. Precisam de permissão para abrir porta Bolt local. `ForaTest` testa o algoritmo sem Spring ou banco.

Em código Spring, com o contexto de segurança da requisição já autenticada:

```java
CandidateDTO selection = new CandidateDTO("123", null, null, null, null, null);
SimilarityResponse response = similarityService.findSimilar(selection, 10);
```

`123` deve ser substituído por um ID persistido e visível. O DTO real está em `DTOs.matching.CandidateDTO`, é um record e seu `id` é **String**. O contrato mínimo do POST é:

```json
{"id":"123"}
```

Os demais campos desse DTO são `name`, `about`, `professionalTitle`, `phone`, `email`; todos são ignorados como entrada pela busca. Não há validação de cadastro, senha ou mutação de perfil. Nenhum `companyId` ou papel é recebido pelo serviço.

O teste `acceptsExistingCandidateDtoAtRootAndReturnsControlledBudgetFailure` desserializa esse formato mínimo e grava uma resposta real reproduzível em `target/similarity-example.json`. Uma cópia está em `docs/candidate-similarity-example.json`, com IDs sintéticos do fixture de teste, sem dados pessoais reais.

## Acesso

O serviço e a extração chamam `CurrentActor.requireCompany()`: autenticado e staff da própria empresa. Papéis reais: `ADMINISTRADOR`, `RECRUTADOR`, `GERENTE`. O conversor JWT valida o usuário e seus vínculos atuais no banco e gera `ROLE_ADMINISTRADOR`, `ROLE_RECRUTADOR`, `ROLE_GERENTE`. `CANDIDATO` é recusado. Não se usa `@PreAuthorize` nem se pressupõe method security habilitada.

A regra aplicada a **todos** os candidatos é a mesma de `GraphCrudRepository.scope(CANDIDATE, ...)`: candidato com relação de saída `INTERESTED IN` para a empresa autenticada. Administrador também fica nesse escopo. Candidaturas `APPLIED TO` não substituem essa regra existente. Inexistente e fora do escopo retornam o mesmo 404. Ausência de autenticação gera 401, candidato comum gera 403, seleção/k inválidos geram `ValidationException`. Antes de qualquer leitura, o serviço valida identidade e k. Sem rodada completa, gera `ResponseStatusException` 503.

O `CandidateSimilarityController` recebe `@RequestBody CandidateDTO` e query parameter opcional `k`. O advice `CrudExceptionHandler` trata validação, corpo ausente/malformado e erros de status. O filtro de segurança existente autoriza os três papéis em `/api/careermatcher/**`. A validação do serviço continua aplicada em chamadas diretas.

```bash
curl -X POST 'http://localhost:8080/api/careermatcher/candidatos/similares?k=10' \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"id":"123"}'
```

Use um token staff válido e um ID visível para sua empresa.

## Evidências e extração

Uma única consulta Neo4j lê perfis públicos mínimos e fatos de todos os candidatos visíveis. Usa `id(node)` numérico, conforme os `@Id @GeneratedValue` e consultas existentes; nomes de skills não substituem IDs canônicos. Caminhos de saída explícitos:

- `(CANDIDATE)-[:HAS]->(RESUME)-[:CandidateHasSkill]->(SKILL)`; origem `RESUME_DECLARATION`, ID do currículo.
- `(CANDIDATE)-[:HAS]->(RESUME)-[:HasExperience]->(EXPERIENCE)-[:DEVELOPS_SKILL]->(SKILL)`; origem `EXPERIENCE`, ID da experiência.
- `(CANDIDATE)-[:HAS]->(RESUME)-[:GRADUATED_IN]->(UNDERGRADUATE)-[:DEVELOPS_A]->(PROJECT)-[:DEVELOPS_SKILL]->(SKILL)`.
- Caminho análogo com `POSTGRADUATED_IN` e `GRADUATE`. Em ambos, origem `PROJECT`, ID do projeto.

Não existem outros vínculos de projeto no schema atual. Não se percorrem instituições, vagas, outros candidatos ou caminhos de comprimento variável para extrair skills. Formação conectada a currículos de candidatos diferentes tem atribuição de projetos ambígua: esses projetos são conservadoramente excluídos por `NOT EXISTS`, inclusive quando o outro candidato está fora do escopo. Para atribuir projetos individuais em uma formação compartilhada, o modelo precisará de vínculo explícito de autoria. Duas formações pessoais podem alcançar o mesmo projeto: conta uma vez.

`f(c,s)` é a quantidade de chaves distintas `(candidateId, skillId, sourceType, sourceId)`. A query e a projeção deduplicam. Cada declaração, experiência ou projeto distinto contribui 1. Java declarado no currículo, em duas experiências e dois projetos soma 5. Repetições em textos e atributos `weight` preexistentes não entram no cálculo. Não foi encontrada materialização automática da lista de skills do currículo a partir das outras fontes no código inspecionado; caso um importador externo a materialize, a declaração continua contando além das experiências e projetos, conforme a regra solicitada.

A proveniência completa fica na projeção; a resposta expõe contagens por tipo, sem IDs de experiências/projetos nem entidades recursivas.

## Projeção e FORA

A projeção local contém exclusivamente candidatos e skills, em namespaces diferentes. Cada par tem arcos nos dois sentidos de peso inteiro `f(c,s)`. Nenhum arco é persistido. A adjacência é ordenada por tipo/ID; skills de todos os candidatos visíveis são incluídas, mesmo sem vínculo direto com as skills iniciais. Nós isolados usam self-loop lógico de peso 1.

`sigma(s)=f(c0,s)/sum_t(f(c0,t))`, com massa inicial somente nas skills de origem. Por exemplo, 5/3/2 produz .5/.3/.2. Tanto push quanto caminhadas usam `P(v,u)=w(v,u)/sum_x(w(v,x))`. A representação comprimida equivale a um multigrafo com arcos unitários paralelos; grau ponderado e M contam multiplicidades, incluindo ambos os sentidos e self-loops. Essa é uma adaptação ponderada da formulação do artigo, sem alocar arcos paralelos fisicamente.

Referência consultada: [Wang et al., 2019](https://arxiv.org/html/1908.10583), algoritmos 1, 2, 5, seção 3.2 e extensão para distribuições da seção 6.2. Não havia `PPR.pdf` no repositório.

Alpha é probabilidade de terminar: `pi = alpha*sigma + (1-alpha)*transpose(P)*pi`.
Push usa fila, `r[v]/degree[v] > rMax`, zera resíduo antes de distribuir e conserva reserva + resíduo. Na correção, `B=(2+2e/3)*ln(2/pUnit)/(e²*delta)` e cada origem residual recebe `ceil(r[v]*B)` caminhadas com contribuição **individual** `r[v]/n_v`. Terminações em skills e caminhadas de comprimento zero participam da massa; não há término forçado em candidato. `rMax=1/sqrt(M*B)`, com alternativa `1/B` quando `M*rMax>1`.

Alcançabilidade é calculada por BFS sem limite de saltos. T contém só outros candidatos visíveis na componente de origem. Top-k é selecionado diretamente em T; k efetivo é min(k, tamanho de T). Rodadas recalculam desde sigma, com e=epsilon/2, pUnit=failureProbability/(n*maxRounds), delta inicial=1/k efetivo. Atinge a meta quando há k efetivo scores positivos e o último >=(1+epsilon)*delta; senão divide delta por 2, inclusive abaixo de 1/n. O controle de erro é conjunto entre todos os nós e rodadas por união dos eventos; sob esse evento, `(1-e)/(1+e)>=1-epsilon`. Isso visa valores aproximados, sem assegurar o mesmo conjunto em empates ou valores próximos. Não usa estabilidade de listas como critério.

Scores ordenam em ordem decrescente, com ID numérico estável para empates exatos. Não se normalizam os scores só entre candidatos. São proximidade no grafo, sem interpretação de percentual de compatibilidade, domínio, senioridade ou chance de contratação. Contagens de evidências explicam os pesos, não uma decomposição exata do PPR.

## Configuração e orçamento

Propriedades Spring Boot sob `similarity` (relaxed binding também aceita variáveis de ambiente):

| Propriedade | Padrão |
|---|---:|
| alpha | 0.15 |
| epsilon | 0.10 |
| failure-probability | 0.000001 |
| default-k | 10 |
| max-k | 100 |
| max-rounds | 32 |
| max-walks | 2000000 |
| max-millis | 5000 |
| seed | 20261031 |

Os limites de caminhadas e tempo cobrem todas as rodadas da execução do núcleo FORA. Extração e montagem são medidas separadamente e **não** estão incluídas nos 5 segundos. Não há índice de caminhadas, cache, consultas por passo nem pares candidato-candidato materializados. O tempo máximo aceito é um dia e maxRounds deve estar entre 1 e 1024. Alpha, epsilon e failureProbability devem estar estritamente entre 0 e 1. A configuração é copiada e validada na entrada do serviço, antes da leitura. Não há estado aleatório global; cada requisição tem gerador reproduzível, com fluxo distinto por rodada.

Quando o orçamento ou maxRounds termina, só retorna a última rodada completa, com `BUDGET_EXCEEDED` e `approximationTargetMet=false`. Não adiciona scores zero. Rodada interrompida é descartada; nó atual de caminhada interrompida não conta como término. Se nenhuma rodada terminou, há falha controlada 503. Meta satisfeita produz `APPROXIMATION_TARGET_MET`/true. Vazios têm `NOT_APPLICABLE`/false e motivo `NO_SKILLS` ou `NO_SIMILAR_CANDIDATES`; nunca executa PageRank global como fallback.

Metadados internos em debug incluem versão de extração `evidence-v1`, configuração, tempos, tamanho, M, pushes, caminhadas, rodadas, delta e qualidade. Não se registra o DTO ou dados pessoais. Contadores incluem trabalho de rodadas descartadas quando aplicável.

## Limites atuais

Todos os fatos são carregados em uma única instrução read-only e congelados em memória; origem e demais candidatos usam essa mesma projeção durante todo o processamento. Entretanto, o isolamento read-committed padrão do Neo4j **não oferece snapshot MVCC estrito contra escritas concorrentes durante a extração**. Garantia estrita exige política de versionamento/snapshot de dados ou coordenação com todos os escritores, não adicionada nesta entrega. Não há teste de concorrência validando essa garantia. Sem cache, mudanças de escopo são observadas na próxima consulta, mas não revalidadas durante a caminhada em memória.

IDs internos do Neo4j seguem o contrato atual e podem ser reutilizados após exclusão de entidades; não representam identidades estáveis entre recriações ou migrações. A projeção inteira e as evidências são materializadas em memória; capacidade em escala de produção e latências p95/p99 ainda precisam de medição com dados reais. A escolha conservadora de rMax não é alegada como ótima para a adjacência comprimida.

O frontend Angular fica no repositório vizinho `talent-engine-front-end`. A última coluna do Banco de talentos oferece “Encontrar similares” aos três papéis staff. O clique abre `/candidatos/:id/similares`, que envia o POST com `{id}` na raiz, usa o interceptor de autenticação existente e exibe a lista ordenada, frequências, score bruto, afinidade indireta e links de perfil. Há estados de carregamento, erro com nova tentativa, vazio e orçamento esgotado; cliques de repetição são bloqueados durante a busca e respostas antigas são ignoradas após mudança de candidato ou destruição da tela. Não foi feita migração da stack nem introduzida dependência nova.

## Arquivos entregues

Criados em `src/main/java/br/com/careermatcher/similarity/`:

- `CandidateSimilarityService.java`: entrada autenticada, validação, resposta e métricas internas.
- `SimilaritySnapshotRepository.java`: extração Neo4j em lote e escopo de empresa.
- `SimilaritySnapshot.java`: fatos persistidos e tipos de proveniência.
- `SkillProjection.java`: deduplicação, frequências, adjacência, sigma e alcançabilidade.
- `Fora.java`: push, Monte Carlo, rodadas e orçamento, sem execução de Spring/banco.
- `SimilaritySettings.java`: configuração e validação de parâmetros.
- `SimilarityResponse.java`: contrato público sem entidades completas.

Criados em `src/test/java/br/com/careermatcher/similarity/`: `ForaTest.java`, `SimilarityServiceTest.java`, `SimilaritySnapshotRepositoryTest.java`.

Criados os dois documentos `docs/candidate-similarity-fora.md` e `docs/candidate-similarity-example.json`. `.gitignore` foi alterado somente para permitir versionar esses documentos na pasta atualmente restrita. O advice de erros foi estendido ao novo controller; os fluxos de matching e cadastro não foram alterados.

## Verificação executada

- `./mvnw -q -DskipTests compile`: sucesso.
- `./mvnw -q test`: suíte completa, 428 testes, zero falhas/erros, antes dos últimos ajustes de proteção numérica/deadline e dois testes adicionais.
- `./mvnw -q -Dtest='ForaTest,SimilarityServiceTest,SimilaritySnapshotRepositoryTest' package`: sucesso após os ajustes finais, 17 testes de similares, zero falhas/erros, pacote Spring Boot gerado.
- Revisão de todos os arquivos novos e `git diff --check`: sem erros de whitespace.

A execução dos testes Neo4j exigiu permissão para abrir socket local fora do sandbox. O harness emite avisos sobre tokens ausentes nos fixtures e depreciações Cypher (`id` e sintaxe de subquery), sem falhas. Nenhuma dependência foi adicionada.

O oráculo por iteração usa a mesma matriz ponderada até erro L1 entre iterações menor que 1e-14. Nos grafos pequenos, foram verificados os invariantes do push e reconstrução do PPR, massa estimada, aproximação dos scores com tolerância absoluta .005 em três seeds fixas, reproduzibilidade, caminhada zero, self-loop, nós desconectados, amostragem ponderada, subconjunto de candidatos, afinidade indireta, delta abaixo de 1/n, orçamento e descarte de rodada incompleta. Isso não é uma certificação empírica da probabilidade de falha configurada em grafos arbitrários.

Extração real verificada para declaração, experiência, projetos de graduação/pós, Java frequência 5, duplicações de relacionamento/caminho, formação compartilhada e empresa distinta. O serviço foi testado para os três papéis staff, candidato proibido, ausência de autenticação, 404 de origem invisível/inexistente, id/k inválidos, contrato mínimo, campos do DTO ignorados, resposta sem dados de autenticação e motivos vazios. Os testes HTTP adicionais verificam o DTO na raiz, os três papéis, autenticação ausente, candidato proibido, corpo e k inválidos, escopo e resultado vazio.

Medição sintética local na execução final: 100 candidatos, 50 experiências por candidato, uma skill popular, 5.000 fatos. Extração: **981,70 ms**; projeção + FORA: **177,28 ms**; 5.757 pushes, 343.840 caminhadas, 6 rodadas, `APPROXIMATION_TARGET_MET`. São tempos pontuais de teste, com compilação Cypher/cache/JIT não controlados; não representam benchmark de produção. Nenhuma frequência foi limitada ou normalizada para acelerar a execução.


## Integração HTTP e interface (atualização)

Criados `controllers/CandidateSimilarityController.java` e `controllers/CandidateSimilarityControllerTest.java`; alterado `controllers/CrudExceptionHandler.java` para reutilizar o contrato de erros.

No frontend foram alterados `core/api.ts`, `core/models.ts`, `core/auth.ts`, `routes.ts` e `shared/entity-list.ts`, com nova tela `features/candidate-similarity.ts`. O parâmetro k é opcional no cliente e a interface usa o padrão do servidor (10). A rota exige STAFF e o retorno após login preserva o destino de similares. Testes adicionados em `core/api.spec.ts` e `e2e/candidate-similarity.spec.ts`.

Verificações desta integração: build Angular aprovado, 184 testes unitários Angular aprovados, 7 testes Playwright aprovados (três papéis, coluna final, POST e token, abertura de perfil, carregamento/erro/retry/orçamento, dois motivos vazios, bloqueio de candidato comum). Playwright usa respostas de API simuladas; não representa validação ponta a ponta com um banco de produção. Build Maven aprovado; reexecução final de controller, serviço, núcleo e extração Neo4j: 23 testes aprovados. A limitação de snapshot sob escritas concorrentes permanece a descrita acima.
