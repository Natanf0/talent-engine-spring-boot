# Cache das listagens por empresa

Banco de Talentos, Realocação Interna e Minhas vagas usam Caffeine no backend.
As chaves são o ID da empresa e o tipo de listagem. Administradores, gerentes
e recrutadores da mesma empresa compartilham o cache na mesma instância Spring.
O workspace é extraído da identidade autenticada em cada consulta, antes de
acessar o cache; não é recebido como parâmetro do cliente.

| Configuração | Padrão | Aplicação |
| --- | --- | --- |
| `WORKSPACE_LISTINGS_CANDIDATES_TTL` | `10m` | Banco de Talentos e Realocação Interna |
| `WORKSPACE_LISTINGS_VACANCIES_TTL` | `2m` | Minhas vagas |
| `WORKSPACE_LISTINGS_MAX_RECORDS` | `300` | Máximo de resumos por empresa e listagem |
| `WORKSPACE_LISTINGS_MAX_WORKSPACES` | `64` | Máximo de empresas em cada uma das três listagens |

Cada entrada guarda somente resumos imutáveis, com ID, nome/cargo, telefone
ou os campos da vaga. Currículos, competências, experiências e descrições
completas não são armazenados nesse cache. Com os valores padrão, há no máximo
64 entradas por listagem, de até 300 resumos cada: até 57.600 resumos nas três
listagens. Caffeine remove entradas por capacidade e por expiração.

O primeiro acesso carrega até 300 registros por ordem de cadastro e o total da
empresa. As primeiras páginas sem filtro e sem ordenação específica usam essa
janela. Se o catálogo inteiro couber nela, a pesquisa e a ordenação também
ocorrem em memória, incluindo os rótulos em português dos enums de vagas.
Se houver mais registros, páginas além da janela, pesquisas e ordenações
específicas consultam o banco completo. A lista não fica limitada aos primeiros
300 e não são criadas entradas de cache por página ou por texto pesquisado.

O menu Matching já consulta os mesmos endpoints paginados de candidatos e
vagas; por isso usa as mesmas entradas de cache. A confirmação dos IDs
selecionados e a leitura dos dados completos para o cálculo continuam
consultando o estado atual do banco.

## Atualizações

Criar, editar ou excluir uma vaga invalida a janela da empresa depois do
commit. Assim, o registro e seu link ficam disponíveis na próxima consulta,
sem esperar os dois minutos. Mudanças no perfil invalidam os resumos das
empresas interessadas e das experiências confirmadas. Mudanças de interesse,
candidaturas e confirmação de vínculo também invalidam as listas afetadas.
Rollbacks não invalidam nem publicam dados ainda não confirmados. Leituras
feitas dentro de uma transação existente consultam o banco e não alimentam o
cache com dados dessa transação.

Importações Cypher externas são percebidas após expirar o cache. Reiniciar a
aplicação limpa a memória. Cada instância da aplicação possui seu próprio
cache e sua própria invalidação; outras instâncias enxergam alterações quando
seus caches expiram. Respostas HTTP e dados de autenticação não são cacheados
por este componente.

## Banco de Talentos e seleção de todos no Matching

O Banco de Talentos contém somente interessados que não são colaboradores
atuais confirmados da empresa autenticada. A exclusão exige `confirmed=true`,
`currentJob=true` e `confirmedCompanyId` igual ao workspace na mesma
experiência. Empregos antigos, experiências não confirmadas e vínculos com
outras empresas não causam exclusão. A Realocação Interna mantém sua listagem
de colaboradores atuais confirmados. Confirmar ou encerrar um vínculo invalida
as duas listas afetadas depois do commit.

Na seleção de candidatos do Matching, “Selecionar todos” abrange todas as
páginas e todos os candidatos elegíveis, sem o limite de 300 resumos do cache.
O navegador envia `allCandidates: true` em vez de carregar e enviar todos os
IDs. É possível desmarcar pessoas ou páginas; seus IDs seguem em
`excludedCandidateIds`. “Selecionar todos nesta página” continua restrito à
página visível. Desmarcar a seleção geral ou limpar a seleção remove o modo
“todos”. A seleção de vagas continua independente.

Exemplo de corpo para `POST /api/matching/run`:

```json
{"allCandidates":true,"excludedCandidateIds":[123],"vacancyIds":[456]}
```

Na execução assíncrona, o servidor resolve o Banco de Talentos atual do
workspace, aplica as exclusões e carrega os currículos em lotes. O mesmo
critério de elegibilidade é revalidado para IDs individuais. Não se aceitam
IDs de colaboradores internos nem de outra empresa para o matching de
recrutamento. Não há mudança no acesso aos perfis usados pela Realocação
Interna ou na projeção de similaridade FORA.
