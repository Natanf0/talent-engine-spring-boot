# Deploy na VPS Hostinger

**Deploy automático dos backends:** após a instalação inicial, siga [CI/CD com GitHub Actions](CI_CD.md). O guia configura testes, imagens privadas no GHCR, acesso SSH, manutenção com espera pelos matchings e rollback. Há uma preparação única na VPS e nos dois repositórios; depois, os pushes em `main` disparam as atualizações.

Este roteiro instala Nginx no Ubuntu 26.04 LTS e executa dois containers Spring e um FastAPI com Docker Compose. Os comandos da VPS assumem uma sessão `root`; substitua os valores em maiúsculas antes de executá-los.

| Serviço | Endereço | Exposição |
| --- | --- | --- |
| Nginx | `https://api.talentengine.com.br:443` | Público |
| Spring 1 | `127.0.0.1:3000` → container `8081` | Somente na VPS |
| Spring 2 | `127.0.0.1:3001` → container `8081` | Somente na VPS |
| FastAPI | `http://fastapi:8000` | Somente na rede Docker |

O navegador chama o Nginx, que distribui `/api/` entre os dois Spring. O Spring solicita o PDF ao FastAPI. Neo4j Aura, Cloudflare R2 e SMTP permanecem externos. Ambas as instâncias compartilham o mesmo par RSA; o FastAPI recebe somente a chave pública.

## 1. DNS, firewall e acesso

Na Cloudflare, crie um registro **A**, nome **api**, apontando para o IPv4 da VPS. Inicialmente deixe **DNS only** (nuvem cinza). Configure um registro AAAA somente se o IPv6 também estiver operacional.

No [firewall da Hostinger](https://www.hostinger.com/support/8172641-how-to-use-a-managed-vps-firewall-at-hostinger/), permita entrada TCP nas portas **22**, **80** e **443**. Restrinja SSH ao seu IP quando possível. Se você já usa outra porta SSH, permita essa porta antes de ativar o firewall. Mantenha `3000`, `3001`, `8000` e `8081` fechadas externamente.

```bash
ssh root@IP_DA_VPS
apt update
apt upgrade -y
apt install -y ca-certificates curl git nginx openssl ufw snapd nano
timedatectl set-ntp true
```

Se houver solicitação de reboot, reinicie e reconecte antes de continuar. Configure o firewall local, preservando sua porta SSH:

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
ufw status
```

## 2. Docker e repositórios

Instale Docker e Compose pelo [repositório oficial para Ubuntu](https://docs.docker.com/engine/install/ubuntu/):

```bash
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt update
apt install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker nginx
docker version
docker compose version
```

Use o Compose instalado acima: `env_file.format: raw` exige Compose **2.30.0 ou superior**. As portas publicadas pelo Docker podem [contornar regras do UFW](https://docs.docker.com/engine/network/port-publishing/); o Compose vincula explicitamente os dois Spring a `127.0.0.1`.

```bash
install -d -m 0755 /opt/talentengine
cd /opt/talentengine
git clone --branch main \
  https://github.com/Natanf0/talent-engine-spring-boot.git spring
git clone --branch main \
  https://github.com/Natanf0/talent-engine-fast-api.git fastapi
```

Para repositórios privados, use um token de leitura no prompt de senha do GitHub ou uma deploy key; mantenha o token fora da URL. A raiz do clone `fastapi` deve conter seu Dockerfile existente.

Os arquivos deste diretório e o `.dockerignore` da raiz precisam estar no commit publicado no GitHub antes da clonagem. Como alternativa, transfira os arquivos atuais: execute `install -d /opt/talentengine/spring/deploy` na VPS e, **na sua máquina, na raiz do projeto Spring**, execute:

```bash
scp -r deploy/hostinger root@IP_DA_VPS:/opt/talentengine/spring/deploy/
scp .dockerignore root@IP_DA_VPS:/opt/talentengine/spring/.dockerignore
```

Na VPS, prepare os arquivos de operação:

```bash
cd /opt/talentengine
install -m 0644 spring/deploy/hostinger/compose.yaml compose.yaml
install -m 0644 spring/deploy/hostinger/Dockerfile.spring Dockerfile.spring
if [ ! -e .env ]; then
  install -m 0600 spring/deploy/hostinger/release.env.example .env
fi
```

A variável `RELEASE` em `.env` identifica as duas imagens. Não coloque credenciais nesse arquivo. A estrutura final será `/opt/talentengine/{spring,fastapi,secrets,compose.yaml,Dockerfile.spring,.env}`.

## 3. Chaves RSA e credenciais

O bloco abaixo cria as chaves somente quando nenhuma delas existe. Se apenas uma existir, ele para para você recuperar o par correto. Não regenere chaves durante atualizações: isso invalida os tokens existentes.

```bash
cd /opt/talentengine
(
  set -eu
  install -d -m 0700 secrets
  if [ -e secrets/private.pem ] || [ -e secrets/public.pem ]; then
    test -s secrets/private.pem && test -s secrets/public.pem || {
      printf '%s\n' 'Par RSA incompleto; recupere as chaves antes de continuar.' >&2
      exit 1
    }
  else
    umask 077
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
      -out secrets/private.pem
    openssl pkey -in secrets/private.pem -pubout -out secrets/public.pem
  fi
  chown root:10001 secrets/private.pem
  chmod 0640 secrets/private.pem
  chown root:root secrets/public.pem
  chmod 0644 secrets/public.pem
  if [ ! -e secrets/spring.env ]; then
    install -m 0600 spring/deploy/hostinger/spring.env.example secrets/spring.env
  fi
)
nano secrets/spring.env
```

Preencha todas as variáveis `AURA_*`, `EMAIL_*` e `R2_*` do exemplo. Use os valores sem aspas: `format: raw` preserva caracteres como `$` literalmente. Esse arquivo contém credenciais e permanece fora dos contextos de build. Autorize a conexão da VPS nos serviços externos caso eles tenham restrição de origem.

O Compose configura `REPORTS_BASE_URL=http://fastapi:8000`, chaves Spring com prefixo `file:`, chaves FastAPI por caminho e issuer `talentengine` nos dois serviços. `ALLOWED_ORIGINS` e `FRONTEND_URL` apontam para `https://talentengine.com.br`. Se o frontend também usar `www`, acrescente `https://www.talentengine.com.br` a `ALLOWED_ORIGINS`, separada por vírgula, no Compose.

## 4. Construir e iniciar

```bash
cd /opt/talentengine
docker compose config --quiet
docker compose build spring1 fastapi
docker compose up -d --no-build --pull never --wait --wait-timeout 240
docker compose ps
curl -fsS http://127.0.0.1:3000/actuator/health
curl -fsS http://127.0.0.1:3001/actuator/health
docker compose exec spring1 curl -fsS http://fastapi:8000/
docker compose exec spring2 curl -fsS http://fastapi:8000/
```

Avance somente se ambos os Spring responderem `UP` e alcançarem o FastAPI. O Spring 2 utiliza a mesma imagem construída para o Spring 1. O healthcheck do RabbitMQ está desativado porque o fluxo atual não utiliza esse serviço. Os limites iniciais somam 8 GB de memória; valide consumo e latência com carga real antes de ajustá-los.

Se houver falha, examine `docker compose logs --tail=150 spring1 spring2 fastapi`. Evite executar `docker compose config` sem `--quiet`, pois ele pode revelar as credenciais resolvidas.

## 5. Nginx e HTTPS

Instale a configuração inicial, antes de emitir o certificado:

```bash
cd /opt/talentengine
install -m 0644 spring/deploy/hostinger/nginx.conf \
  /etc/nginx/sites-available/talentengine-api
if [ ! -e /etc/nginx/sites-enabled/talentengine-api ]; then
  ln -s /etc/nginx/sites-available/talentengine-api \
    /etc/nginx/sites-enabled/talentengine-api
fi
nginx -t
systemctl reload nginx
curl -fsS -H 'Host: api.talentengine.com.br' http://127.0.0.1/healthz
```

O proxy preserva o prefixo `/api/` e usa `least_conn` para distribuir requisições. Apenas `/api/` e `/healthz` são expostos. O Nginx detecta falhas passivamente; um healthcheck Docker com falha não remove automaticamente o serviço do upstream. [Documentação do balanceamento](https://nginx.org/en/docs/http/load_balancing.html).

Com o DNS apontando para a VPS, ainda com nuvem cinza, emita o certificado usando o [Certbot](https://certbot.eff.org/instructions?os=snap&ws=nginx):

```bash
snap install --classic certbot
/snap/bin/certbot --nginx -d api.talentengine.com.br \
  --redirect --agree-tos --email SEU_EMAIL_DE_CONTATO
nginx -t
curl -fsS https://api.talentengine.com.br/healthz
```

O Certbot configura HTTPS na porta 443, redirecionamento de HTTP e renovação automática. Na Cloudflare:

1. Configure SSL/TLS como **[Full (strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/)**, que valida o certificado do Nginx.
2. Ative o proxy (nuvem laranja) do registro `api`.
3. Crie uma regra de cache com expressão `http.host eq "api.talentengine.com.br"` e ação **Bypass cache**.

Valide HTTPS e a renovação com essa configuração final:

```bash
curl -fsS https://api.talentengine.com.br/healthz
/snap/bin/certbot renew --dry-run
```

Mantenha a porta 80 acessível para renovação. Não reinstale o `nginx.conf` inicial sobre a configuração modificada pelo Certbot: ela contém os listeners e caminhos de certificado adicionados.

## 6. Frontend e testes de aceite

Na Cloudflare, configure a variável de **build de produção** do frontend como `TALENT_API_URL=https://api.talentengine.com.br`, sem `/api`, e faça um novo build/deploy. `https://talentengine.com.br/config.json` deve retornar `apiBaseUrl` com esse endereço.

O frontend é publicado no **Cloudflare Workers**, Worker `talent-engine-front-end`, pelo repositório `Natanf0/talent-engine-front-end`, branch `main`. Mantenha Root directory `/`, Build command `npm run build`, Deploy command `npx wrangler deploy` e o comando de preview `npx wrangler preview`. As variáveis de build dos previews são configuradas separadamente.

O `wrangler.jsonc` na raiz **do repositório do frontend** deve conter:

```json
{
  "name": "talent-engine-front-end",
  "compatibility_date": "2026-10-08",
  "assets": {
    "directory": "./dist/talent-engine/browser",
    "not_found_handling": "single-page-application"
  }
}
```

Essa opção habilita o [fallback SPA do Workers](https://developers.cloudflare.com/workers/static-assets/routing/single-page-application/): o Worker entrega `index.html` ao abrir ou recarregar uma rota do Angular. O arquivo `_redirects` na raiz do frontend não configura este deploy. Um erro HTTP 404 em `talentengine.com.br/empresas-parceiras`, antes de o Angular carregar, deve ser corrigido no Worker. O Nginx da VPS atende somente `api.talentengine.com.br`.

Para publicar a correção, faça commit e push dos arquivos alterados do **frontend**, incluindo `wrangler.jsonc`, `package.json`, `package-lock.json` e os ajustes de autenticação, para `main`. Aguarde o build automático concluir e verifique o domínio personalizado `talentengine.com.br`. Alterar somente este README ou atualizar os containers da VPS não publica o frontend.

Após o deploy:

1. Como candidato, abra e recarregue `/empresas-parceiras`.
2. Em janela anônima, abra uma vaga existente em `/oportunidades/:id`, recarregue e confirme que não exige login.
3. Abra um convite novo `/finalizar-cadastro#token=...` sem sessão e conclua o cadastro. O fragmento é lido pelo Angular, sem ser enviado ao Worker.
4. Entre como administrador, gerente ou recrutador e abra “Saiba mais” do PDF em outra aba do mesmo navegador. `/candidatos/:id` deve reutilizar a sessão válida, confirmada em `/api/auth/me`. Sem sessão, deve pedir login e retornar ao currículo após autenticar.
5. Saia da conta em uma aba e confirme que as outras áreas protegidas também pedem login. Uma vaga pública deve continuar acessível.

A sessão passa a usar `localStorage`, com uma conta por origem e perfil do navegador. O JWT continua expirando em uma hora, sem renovação; outro navegador exige login. Na primeira abertura após a atualização, a sessão antiga por aba é descartada e será necessário entrar novamente. Logout, expiração e troca de conta recarregam as outras abas para limpar os dados anteriores e revalidar as permissões. Nenhum token de autenticação é colocado nos links do PDF.

Teste o preflight CORS:

```bash
curl -i -X OPTIONS https://api.talentengine.com.br/api/auth/login \
  -H 'Origin: https://talentengine.com.br' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type'
```

A resposta deve permitir a origem configurada, POST e os headers solicitados. Faça login pelo frontend, consulte os dados da conta, execute um matching, atualize o histórico e baixe o PDF. No navegador, confira que as chamadas usam somente `api.talentengine.com.br` como backend.

Confirme que o mesmo JWT autentica em ambas as instâncias. Na VPS, cole um access token de uma conta de teste no prompt oculto:

```bash
read -r -s -p 'Access token: ' TALENT_TEST_JWT
printf '\n'
for talent_port in 3000 3001; do
  printf 'Authorization: Bearer %s\n' "$TALENT_TEST_JWT" |
    curl -fsS -H @- "http://127.0.0.1:${talent_port}/api/auth/me"
done
unset TALENT_TEST_JWT
tail -n 100 /var/log/nginx/talentengine.access.log
```

Ambas as chamadas devem autenticar. Após chamadas pelo domínio público, o log deve mostrar os dois upstreams `3000` e `3001`. Em uma janela sem matchings em andamento, pare `spring1`, valide novas chamadas e recupere a instância:

```bash
docker compose stop spring1
curl -fsS https://api.talentengine.com.br/healthz
docker compose up -d --no-build --pull never --wait --wait-timeout 240 spring1
```

De outra máquina, tentativas HTTP para `IP_DA_VPS:3000`, `:3001` e `:8000` devem falhar. Esses testes são verificações a executar no servidor; criar os arquivos locais não comprova que o deploy remoto foi realizado.

## 7. Operação, atualização e rollback

**Se já ativou o CI/CD, use os comandos de [operação e recuperação da esteira](CI_CD.md#5-operação-e-recuperação).** O procedimento manual com `RELEASE` abaixo se aplica apenas à instalação anterior à migração; a esteira usa `SPRING_IMAGE` e `FASTAPI_IMAGE` separadamente.

```bash
cd /opt/talentengine
docker compose ps
docker stats --no-stream
docker compose logs --tail=100 spring1 spring2 fastapi
tail -n 100 /var/log/nginx/talentengine.error.log
```

`restart: unless-stopped` reinicia processos encerrados e recupera containers após reboot, exceto os que foram parados manualmente. O estado `unhealthy`, sozinho, não reinicia um container. Para diagnosticar:

| Sintoma | Verificação |
| --- | --- |
| Spring não inicia ou health retorna DOWN | Credenciais Aura/SMTP, acesso externo, caminhos e permissões dos PEM, logs do Spring. |
| Nginx responde 502 | Saúde dos dois Spring, portas locais 3000/3001 e log de erro do Nginx. |
| Login alterna entre sucesso e 401 | Mesmo par RSA nas duas instâncias e relógio do servidor sincronizado. |
| PDF falha | Logs do Spring/FastAPI, issuer e chave pública compartilhados, URL `http://fastapi:8000`. |
| CORS falha | Origem exata, inclusive `www`, e `TALENT_API_URL` no novo build do frontend. |
| Certificado não é emitido/renovado | DNS A/AAAA, porta 80, regras Cloudflare e `/var/log/letsencrypt/letsencrypt.log`. |

Para atualizar código, suspenda novas submissões e aguarde trabalhos em andamento terminarem. O procedimento abaixo pressupõe o Compose atual; mudanças de infraestrutura devem ser revisadas e copiadas separadamente. Registre os commits dos dois repositórios junto da versão para auditoria.

```bash
cd /opt/talentengine
(
  set -eu
  cp -p .env .env.previous
  git -C spring pull --ff-only
  git -C fastapi pull --ff-only
  git -C spring rev-parse HEAD
  git -C fastapi rev-parse HEAD
  printf 'RELEASE=%s\n' "$(date -u +%Y%m%dT%H%M%SZ)" > .env
  docker compose config --quiet
  docker compose build spring1 fastapi
  docker compose up -d --no-build --pull never --wait --wait-timeout 240
  curl -fsS https://api.talentengine.com.br/healthz
)
```

Pare se qualquer etapa falhar. Preserve as imagens antigas; não execute limpeza de imagens antes de validar a versão. Para retornar às imagens identificadas em `.env.previous`, sem reconstruí-las:

```bash
cd /opt/talentengine
cp -p .env.previous .env
docker compose up -d --no-build --pull never --wait --wait-timeout 240
curl -fsS https://api.talentengine.com.br/healthz
```

O rollback pressupõe Compose e dados compatíveis com a versão anterior; ele não desfaz alterações em Aura/R2. Não inicie outra atualização antes de resolver uma tentativa com falha, pois `.env.previous` guarda apenas uma versão.

Mantenha backup protegido, fora da VPS, de `secrets/`, `.env`, Compose, Dockerfile, configuração Nginx e `/etc/letsencrypt`, além dos backups Aura/R2. Os backups contêm chaves privadas: restrinja o acesso e proteja o armazenamento. Na restauração, recupere as permissões da etapa 3 antes de iniciar os containers.

## Limitações desta topologia

- `/api/matching/executions/latest` usa memória local e pode divergir entre instâncias. O histórico persistido `/api/matching/executions`, usado pelo frontend atual, é apropriado para esta topologia.
- A fila de matching é local e não durável: reiniciar um container pode perder trabalhos aceitos. O desligamento gracioso tem prazo limitado e não substitui aguardar a conclusão das tarefas.
- O Spring espera até 60 segundos pela resposta de leitura do FastAPI; o Nginx está configurado com 110 segundos. A Cloudflare tem limite padrão de leitura de [125 segundos](https://developers.cloudflare.com/fundamentals/reference/connection-limits/). Aumentar só o timeout do Nginx não resolve relatórios que ultrapassem o limite do Spring; esses casos precisam de ajuste na aplicação ou geração assíncrona.
- Duas instâncias distribuem carga e permitem alguma tolerância a falhas de processo. A VPS e o único FastAPI continuam sendo pontos únicos de falha.
