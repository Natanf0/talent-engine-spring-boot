# CI/CD dos backends com GitHub Actions

Após a configuração única abaixo, cada push na `main` testa, constrói e publica uma imagem no GitHub Container Registry (GHCR) e atualiza a VPS por SSH. O Spring atualiza `spring1` e `spring2`; o FastAPI atualiza somente `fastapi`. O frontend mantém seu deploy independente na Cloudflare.

## Comportamento da esteira

1. Spring: Java 21, `mvn verify` e testes do script de deploy. FastAPI: Python 3.10, dependências de desenvolvimento e `pytest`. Pull requests executam testes, sem acesso à VPS.
2. Após testes aprovados na `main`, construir uma imagem `linux/amd64`, publicar a tag `sha-COMMIT` e obter seu digest. A VPS usa `ghcr.io/...@sha256:...`, nunca `latest`. O workflow publica usando o `GITHUB_TOKEN` automático, com `packages: write` somente no job de publicação.
3. Adquirir um bloqueio comum aos dois repositórios na VPS, verificar a versão atual e baixar a nova imagem **antes** da manutenção. Validar os metadados de repositório/commit e conservar as imagens anteriores localmente.
4. Criar `/opt/talentengine/deploy/control/maintenance`. Ambos os Spring passam a responder **503**, com `Retry-After: 30`, para novas requisições da aplicação. Healthchecks e o controle interno continuam disponíveis. Aguardar até **10 minutos** pelas requisições admitidas e pelos matchings aceitos, incluindo a fila. Se não terminarem, abortar sem reiniciar containers e reabrir o atendimento.
5. Atualizar somente o componente solicitado e verificar imagens efetivamente executadas, saúde dos três containers, comunicação Spring → FastAPI e HTTPS do Nginx. Se falhar, restaurar as imagens anteriores sem rebuild. Se o rollback também falhar, conservar a manutenção e bloquear novos deploys até recuperação.
6. Registrar commit, digest, versão anterior e resultado. Retirar a manutenção somente após validação. A transação roda em um serviço systemd: desconectar/cancelar o cliente SSH não interrompe a atualização no meio.

O bloqueio da VPS serializa os dois repositórios; a concorrência do GitHub, sozinha, só protege um repositório. Execuções pendentes intermediárias podem ser substituídas por pushes mais novos. Números de execução já publicados/ultrapassados são ignorados, evitando que um job atrasado reinstale código antigo. Não renomeie/recrie o workflow nem zere `deploy/orders.json` durante operação normal.

O único contrato novo é `GET /internal/deploy/status`, com header `X-Deploy-Token`. Retorna `protocol`, `draining`, `activeRequests` e `pendingMatchings`, sem dados de usuários. Só está habilitado quando o controle de deploy está configurado. O Nginx atual não publica `/internal/`; as portas 3000/3001 continuam vinculadas a `127.0.0.1`. O segredo do controle fica somente na VPS. A contagem HTTP cobre os handlers síncronos atuais; ao introduzir handlers Servlet assíncronos, adapte o acompanhamento até sua conclusão antes de usá-los em produção.

Referências: [publicação de imagens pelo Actions](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images), [autenticação do GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry), [concorrência de workflows](https://docs.github.com/en/actions/concepts/workflows-and-actions/concurrency).

## 1. Publicar os arquivos e as primeiras imagens

Faça commit e push das alterações de **cada repositório** para `main`. No Spring, inclua `.github/`, `deploy/hostinger/`, o controle de manutenção em `src/` e os ajustes em `.gitignore`. No FastAPI, inclua `.github/`, `.gitignore`, README e os testes atualizados. Os `.gitignore` foram ajustados porque antes ignoravam `.github`.

Inicialmente **não** configure `VPS_DEPLOY_ENABLED=true`. Assim, os testes e a publicação das imagens já funcionam, mas o job de deploy permanece desabilitado durante a preparação da VPS.

Em cada repositório, abra **Actions → Spring CI and VPS deploy / FastAPI CI and VPS deploy** e aguarde `test` e `image` passarem. Se Actions estiver desabilitado, habilite-o em Settings → Actions. Em GitHub → Packages, mantenha ambos os pacotes privados e vinculados aos respectivos repositórios. Caso já existam, conceda ao repositório correspondente acesso ao pacote em **Manage Actions access**.

Pacotes esperados:

- `ghcr.io/natanf0/talent-engine-spring-boot`
- `ghcr.io/natanf0/talent-engine-fast-api`

## 2. Preparação única da VPS

Execute como `root`. Os comandos assumem a instalação existente em `/opt/talentengine`, Ubuntu com systemd, Docker Compose ≥ 2.30, certificado HTTPS válido para `api.talentengine.com.br` e arquitetura `x86_64` (`uname -m`). O script verifica o Nginx pelo endereço local com esse domínio e certificado.

```bash
apt update
apt install -y python3 sudo curl openssl
cd /opt/talentengine
git -C spring pull --ff-only
install -d -m 0700 deploy deploy/history
install -d -m 0755 deploy/control
install -o root -g root -m 0755 spring/deploy/hostinger/deploy.py \
  /usr/local/sbin/talentengine-deploy
if [ ! -e secrets/deploy.env ]; then
  (umask 077; printf 'DEPLOY_CONTROL_TOKEN=%s\n' "$(openssl rand -hex 32)" > secrets/deploy.env)
fi
chmod 0600 secrets/deploy.env
```

Se o clone não conseguir fazer `pull`, envie a pasta atual `deploy/hostinger` por SCP como no guia principal. Não coloque credenciais Git em URLs. As próximas atualizações automáticas usarão imagens; não dependerão dos clones na VPS.

Crie um **personal access token classic** no GitHub com `read:packages`, de uma conta que consiga ler os dois pacotes privados. Na VPS, faça login como root (a senha solicitada é esse token):

```bash
docker login ghcr.io -u Natanf0
chmod 0600 /root/.docker/config.json
```

Esse token de leitura fica na VPS. O token automático que publica imagens no Actions não precisa ser copiado nem criado manualmente.

Informe os commits completos das primeiras execuções aprovadas e baixe as imagens:

```bash
TALENT_SPRING_SHA=COMMIT_COMPLETO_DO_SPRING
TALENT_FASTAPI_SHA=COMMIT_COMPLETO_DO_FASTAPI
docker pull "ghcr.io/natanf0/talent-engine-spring-boot:sha-${TALENT_SPRING_SHA}"
docker pull "ghcr.io/natanf0/talent-engine-fast-api:sha-${TALENT_FASTAPI_SHA}"
TALENT_SPRING_IMAGE=$(docker image inspect --format '{{index .RepoDigests 0}}' \
  "ghcr.io/natanf0/talent-engine-spring-boot:sha-${TALENT_SPRING_SHA}")
TALENT_FASTAPI_IMAGE=$(docker image inspect --format '{{index .RepoDigests 0}}' \
  "ghcr.io/natanf0/talent-engine-fast-api:sha-${TALENT_FASTAPI_SHA}")
```

### Primeira migração: instalar a proteção de trabalhos

A versão antiga do Spring ainda não tem contadores de deploy. Portanto, esta primeira troca exige uma janela controlada, sem matchings pendentes. As trocas posteriores serão automáticas.

1. Faça backup da configuração atual do Nginx. No bloco existente `location /api/ { ... }` de `/etc/nginx/sites-available/talentengine-api`, acrescente temporariamente `return 503;`. Execute `nginx -t` e `systemctl reload nginx`. Isso bloqueia novas chamadas e preserva a configuração HTTPS do Certbot; `/healthz` continua disponível. O reload deixa requisições já em andamento terminarem.
2. Aguarde as requisições e todos os matchings aceitos terminarem. Confira nos logs de **ambas** as instâncias os `requestId` de `Matching accepted` e seus respectivos `Matching completed` ou `Matching failed`. Se não conseguir confirmar que a fila está vazia, não reinicie os containers. Não use apenas uma espera fixa de alguns segundos como confirmação.
3. Faça o backup e a troca do Compose e das referências de imagem:

```bash
cd /opt/talentengine
test ! -e compose.before-cicd.yaml && cp -p compose.yaml compose.before-cicd.yaml
test ! -e .env.before-cicd && cp -p .env .env.before-cicd
install -m 0644 spring/deploy/hostinger/compose.registry.yaml compose.yaml
(umask 077; printf 'SPRING_IMAGE=%s\nFASTAPI_IMAGE=%s\n' \
  "$TALENT_SPRING_IMAGE" "$TALENT_FASTAPI_IMAGE" > .env)
docker compose config --quiet
docker compose up -d --no-build --pull never --wait --wait-timeout 240
```

4. Confirme o funcionamento do controle, dos containers, do Nginx e da comunicação interna, sem imprimir o segredo:

```bash
python3 - <<'PY'
import runpy
deploy = runpy.run_path('/usr/local/sbin/talentengine-deploy')
deploy['health'](deploy['control_token'](), False)
print('Bootstrap validado: serviços saudáveis e controle de deploy disponível.')
PY
```

5. Retire somente o `return 503;` temporário do Nginx, valide com `nginx -t` e faça `systemctl reload nginx`. Teste login e PDF. **Não** substitua o site HTTPS pelo exemplo HTTP inicial do repositório.

Se essa primeira migração falhar, mantenha o bloqueio temporário, restaure `compose.before-cicd.yaml` e `.env.before-cicd`, execute `docker compose up -d --no-build --pull never --wait --wait-timeout 240` e valide os serviços antes de retirar o bloqueio. Não habilite deploy automático enquanto as duas instâncias não executarem o novo controle.

## 3. Usuário e chave SSH exclusivos do deploy

Na **sua máquina**, gere uma chave dedicada, sem senha, para uso pelo Actions. Não reutilize sua chave pessoal:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/talentengine-cicd -C talentengine-cicd -N ''
scp ~/.ssh/talentengine-cicd.pub root@IP_DA_VPS:/tmp/talentengine-cicd.pub
```

Na **VPS**, crie o usuário e instale a chave pública. Ele não precisa entrar no grupo `docker` nem escrever nos arquivos da aplicação:

```bash
id talentengine-deploy >/dev/null 2>&1 || useradd --create-home --shell /bin/bash talentengine-deploy
install -d -o talentengine-deploy -g talentengine-deploy -m 0700 /home/talentengine-deploy/.ssh
{ printf 'restrict '; cat /tmp/talentengine-cicd.pub; } > /home/talentengine-deploy/.ssh/authorized_keys
chown talentengine-deploy:talentengine-deploy /home/talentengine-deploy/.ssh/authorized_keys
chmod 0600 /home/talentengine-deploy/.ssh/authorized_keys
printf '%s\n' 'talentengine-deploy ALL=(root) NOPASSWD: /usr/local/sbin/talentengine-deploy' \
  > /etc/sudoers.d/talentengine-deploy
chmod 0440 /etc/sudoers.d/talentengine-deploy
visudo -cf /etc/sudoers.d/talentengine-deploy
```

O executável, o Compose e `/opt/talentengine` devem permanecer sob controle de root. O executável só aceita componentes conhecidos, digests, commits e números de execução validados; não aceita comandos de shell ou caminhos arbitrários. A chave autoriza publicar código desses dois pacotes, portanto limite o acesso aos secrets e à branch `main` aos responsáveis pelo deploy.

No console confiável da VPS, obtenha a chave **pública do servidor**:

```bash
cat /etc/ssh/ssh_host_ed25519_key.pub
```

Ela será usada para validar a identidade da VPS. Não use `StrictHostKeyChecking=no` nem aceite automaticamente uma chave obtida por `ssh-keyscan` sem comparar com a VPS.

## 4. Secrets e variables no GitHub

Configure em **ambos os repositórios**: Settings → Secrets and variables → Actions.

| Tipo | Nome | Valor |
| --- | --- | --- |
| Secret | `VPS_HOST` | IPv4 público da VPS ou hostname apontando diretamente para ela, sem proxy Cloudflare. |
| Secret | `VPS_SSH_KEY` | Conteúdo completo da chave **privada** local `~/.ssh/talentengine-cicd`, incluindo BEGIN/END. |
| Secret | `VPS_KNOWN_HOSTS` | `IP_DA_VPS ssh-ed25519 CHAVE_PUBLICA_DO_SERVIDOR`, obtida no console acima. |
| Variable | `VPS_SSH_PORT` | `22`, ou a porta real liberada nos firewalls Hostinger e Ubuntu. |
| Variable | `VPS_DEPLOY_ENABLED` | `true`, **somente depois** do bootstrap e dos testes manuais. |

Para SSH em porta diferente de 22, use `[IP_DA_VPS]:PORTA ssh-ed25519 CHAVE_PUBLICA_DO_SERVIDOR` em `VPS_KNOWN_HOSTS`. O host deve ser o mesmo texto de `VPS_HOST`.

Crie/valide o environment **production**, limitado à branch `main` e sem aprovação obrigatória se deseja deploy automático. `VPS_DEPLOY_ENABLED` deve ser uma **repository variable**, pois a condição do job é avaliada antes de carregar o environment. Os secrets podem ser de repositório ou desse environment. Não coloque Aura, R2, SMTP, PEMs ou `DEPLOY_CONTROL_TOKEN` no GitHub.

O SSH precisa aceitar a conexão por chave dos runners hospedados pelo GitHub, cujos IPs variam. Sua configuração escolhida permite SSH de qualquer IP com autenticação por chave; se futuramente restringir por IP, adapte a conexão antes de reativar os deploys.

Em Actions, execute **Run workflow**, branch `main`, primeiro no Spring e depois no FastAPI. Depois dessa validação, os próximos pushes já seguirão o mesmo fluxo automaticamente. Apenas criar os arquivos locais não ativa a automação remota.

## 5. Operação e recuperação

Na VPS:

```bash
cd /opt/talentengine
docker compose ps
cat deploy/last-result.json
systemctl list-units 'talentengine-deploy-*' --all
journalctl -u talentengine-deploy-spring-NUMERO-TENTATIVA --no-pager -n 100
```

O GitHub mostra sucesso/falha no job `deploy`. Os detalhes ficam no journal da VPS mesmo se o cliente SSH desconectar. Não inicie deploy manual com `docker compose up` paralelamente à automação.

- **Falha antes da troca ou rollback automático bem-sucedido:** a versão anterior continua atendendo. Corrija a causa e execute novamente o workflow. Se o número de execução já foi publicado, use um novo `Run workflow` (novo número) ou novo push.
- **Rollback falhou, host reiniciou ou existe `deploy/pending.json`:** confirme que o serviço systemd anterior terminou; corrija a causa (disco, credencial, serviço externo etc.) e execute `sudo /usr/local/sbin/talentengine-deploy --recover`. Ele conserva a manutenção, espera o trabalho ainda vivo e restaura as imagens da transação pendente. Se uma instância já estiver parada, não haverá trabalho em memória a recuperar nela. Se uma instância viva não responder ao controle, a recuperação aborta para não interromper trabalho desconhecido; investigue seus logs antes de intervir.
- **Voltar o último deploy que passou nos healthchecks:** execute `sudo /usr/local/sbin/talentengine-deploy --rollback`. O comando usa a mesma trava e espera de trabalhos. Os números de execução permanecem monotônicos, para um job antigo não desfazer esse rollback.

Preserve `deploy/`, `.env`, as imagens anteriores e os backups já descritos no guia principal. Não use `docker image prune -a` indiscriminadamente: imagens antigas podem ser necessárias para rollback. A esteira exige pelo menos 2 GiB livres antes do pull e não remove imagens automaticamente. O backup de `secrets/` inclui agora `deploy.env`.

Depois da migração, a seção de atualização manual com `RELEASE` do guia original deixa de valer: o Compose usa `SPRING_IMAGE` e `FASTAPI_IMAGE` independentes. Alterações futuras no script root, no Compose instalado, nos secrets ou no Nginx exigem atualização explícita da infraestrutura; a pipeline publica **aplicações**, não sobrescreve esses arquivos.

O controle protege trabalhos durante deploys planejados. A fila continua em memória e não resiste a falhas da VPS/processo. O rollback não desfaz alterações de dados no Aura/R2; mudanças nos contratos Spring/FastAPI e no banco precisam ser compatíveis com as versões anteriores durante atualizações independentes. Não há promessa de indisponibilidade zero: durante a manutenção, a API retorna 503 e novas solicitações devem ser repetidas após o deploy.

## Verificação local

```bash
mvn -B --no-transfer-progress verify
python3 -m unittest discover -s deploy/hostinger/tests -v
```

No repositório FastAPI: `python -m pytest -q` com `requirements-dev.txt` instalado. Os testes do script simulam Docker/HTTP para verificar troca independente, timeout de espera, falha de pull, rollback, recuperação e rejeição de execuções antigas. A ativação real ainda deve confirmar permissões SSH, acesso aos pacotes, mounts, TLS, duas submissões simultâneas dos repositórios e um ciclo de deploy/rollback com uma conta de teste.
