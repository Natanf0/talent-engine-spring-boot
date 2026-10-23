# Candidatura pela página pública de vaga

O frontend usa `/oportunidades/{id}` como página de divulgação. Este repositório
implementa a API; os botões, a modal e a página de confirmação pertencem ao frontend.

## Fluxo da interface

1. Carregar a vaga com `GET /api/careermatcher/vagas/{id}`. Essa consulta é pública,
   inclusive quando o navegador ainda possui um token expirado.
2. Restaurar a sessão usando `GET /api/auth/me` com o bearer token, quando houver.
   Para uma sessão válida com `role: "CANDIDATO"`, consultar o estado da candidatura
   na API abaixo. Se `applied` for `false`, mostrar **Aplicar**. Se for `true`,
   mostrar um botão desabilitado com **Você já aplicou para esta oportunidade! Deixe
   o restante conosco.** Não liberar a aplicação enquanto o estado for desconhecido.
   Sem sessão válida, mostrar **Faça Login para aplicar!**, levando à tela de login
   existente, que oferece o cadastro de conta. Preservar a URL da vaga para o retorno.
   Perfis de colaboradores não podem enviar candidaturas.
3. **Aplicar** revalida o estado e, se ainda não houver candidatura, abre uma modal
   com **Salário bruto esperado (R$)** obrigatório.
   Abrir ou cancelar a modal não grava interesse nem candidatura.
4. **Confirmar** envia a requisição abaixo. Converter a moeda exibida em português
   para número JSON: `R$ 5.500,75` deve ser enviado como `5500.75`.
   Desabilitar a confirmação enquanto a requisição estiver em andamento.
5. Somente após HTTP 200, exibir uma página de confirmação com um ícone de check e
   a mensagem retornada, usando renderização de texto. O nome da empresa vem da API.
   Se a sessão expirar durante a confirmação (401), encaminhar ao login mantendo
   a URL da vaga. Erros de validação (400) devem manter a modal aberta para correção.

## Consultar candidatura existente

```http
GET /api/me/vacancies/{vacancyId}/application-status
Authorization: Bearer <accessToken>
```

Resposta HTTP 200: `{"applied": true}` ou `{"applied": false}`. A consulta é restrita
a candidatos e verifica exclusivamente a relação `APPLIED TO` do candidato da
sessão com a vaga selecionada. Interesse na empresa não caracteriza candidatura
à vaga. Vaga inexistente ou sem publicação retorna 404. A consulta não altera o grafo.

O frontend consulta ao abrir ou recarregar a vaga e revalida antes de abrir a modal,
sem consultas periódicas. Uma falha deve permitir nova verificação, sem liberar uma
reaplicação com estado desconhecido. A confirmação com check continua aparecendo
após envio bem-sucedido; recarregar a página mostra a vaga e o aviso de candidatura existente.

## Confirmar candidatura

```http
POST /api/me/vacancies/{vacancyId}/applications
Authorization: Bearer <accessToken>
Content-Type: application/json

{"income": 5500.75}
```

`income` é obrigatório, positivo, com até duas casas decimais e até 12 dígitos na
parte inteira. O candidato é obtido exclusivamente da autenticação; a empresa é
obtida da relação `PUBLISHES` da vaga. Não enviar IDs de candidato ou de empresa.

Resposta HTTP 200, tanto na primeira candidatura quanto na repetição:

```json
{
  "vacancyId": 123,
  "companyId": 456,
  "companyName": "Empresa Exemplo",
  "income": 5500.75,
  "message": "Parabéns, sua candidatura foi enviada com sucesso a Empresa Exemplo."
}
```

Em uma única transação, a confirmação cria ou reutiliza:

- ``(candidate)-[:`INTERESTED IN`]->(company)``, incluindo o candidato no banco de talentos;
- ``(candidate)-[:`APPLIED TO` {income: 5500.75}]->(vacancy)``, registrando o salário esperado.

O tipo existente no banco é `INTERESTED IN`, com espaço. Ele é reutilizado para
preservar o banco de talentos, as permissões de consulta e a seleção para matching.
Repetições e confirmações concorrentes não criam novas relações para o mesmo par.
Uma nova confirmação para a mesma vaga atualiza `income`. Candidaturas a vagas
diferentes da mesma empresa têm salários independentes e compartilham o vínculo
de interesse na empresa.

| HTTP | Significado |
| --- | --- |
| 200 | Candidatura confirmada e vínculo com a empresa garantido |
| 400 | Salário ausente ou inválido, JSON inválido ou ID com formato inválido |
| 401 | Login ausente, inválido ou expirado |
| 403 | Perfil diferente de candidato |
| 404 | Vaga inexistente, sem empresa publicadora ou candidato inexistente |

O frontend deve usar esta confirmação no lugar da ação antiga **Tenho Interesse**.
Não é necessário chamar a rota de interesse separadamente.
