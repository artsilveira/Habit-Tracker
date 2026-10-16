# Segurança

Este documento descreve os controles da aplicação e suas limitações. Os comandos para executar as verificações estão no [README](README.md#testes-e-build).

## Autenticação e sessões

- Senhas são armazenadas com PBKDF2-HMAC-SHA256, 600 mil iterações e salt aleatório individual.
- O cadastro exige de 15 a 128 caracteres. O login aceita senhas a partir de 8 caracteres para manter compatibilidade com contas anteriores.
- Emails inexistentes também passam pela verificação de senha com um hash fictício pré-calculado.
- A transação de leitura do login termina antes do cálculo de PBKDF2, evitando transações ociosas durante a verificação. Uma nova sessão só é gravada após validar a senha.
- Tokens de sessão usam 32 bytes aleatórios. O banco armazena apenas seus hashes SHA-256.
- As sessões expiram em sete dias, têm limite de dez por conta e são invalidadas no logout. Um novo login substitui o token anterior do navegador.
- O cookie `habit_session` usa `HttpOnly`, `SameSite=Strict` e `Path=/api`. Origens HTTPS exigem `Secure`. Tokens não são armazenados no `localStorage`.

## Proteção das requisições

Mutações verificam o cabeçalho `Origin` quando presente e rejeitam `Sec-Fetch-Site: cross-site`. O parser aceita apenas objetos JSON, rejeita campos duplicados e conteúdo extra, e limita o corpo a 16 KiB. Campos textuais têm limites de tamanho; os que usam a validação de texto também rejeitam caracteres de controle.

A API limita a concorrência a 24 requisições e quatro autenticações. A autenticação tem janelas de 15 minutos e retorna `429` com `Retry-After` ao exceder a cota:

| Ambiente | Cota de origem | Cota por conta |
| --- | --- | --- |
| Conexão direta | 10 cadastros ou 30 logins por IP | 10 tentativas por hash do email |
| Render | 30 cadastros ou 120 logins por instância | 10 tentativas por hash do email |

No Render, o IP da conexão pertence ao proxy. A aplicação usa uma cota por instância e não confia em `X-Forwarded-For` enviado pelo visitante. Os limites ficam em memória, reiniciam com o processo e não são compartilhados entre instâncias. Um visitante pode consumir a cota global.

## Banco de dados

O acesso usa consultas parametrizadas. Hábitos, marcações e humor são consultados no contexto do usuário autenticado. Alterações são transacionais; a ordem dos bloqueios de usuário e hábito evita deadlocks entre edição, exclusão e reordenação. A consulta mensal usa um snapshot consistente.

As conexões têm timeout de dez segundos e leitura de quinze segundos. Consultas têm limite de cinco segundos, espera por bloqueio de dois segundos e transações ociosas de dez segundos. A API utiliza uma conta sem privilégios administrativos; a criação do esquema fica a cargo de uma conta separada.

`DATABASE_URL` exige TLS com validação de certificado e hostname. A senha é separada da URL JDBC, e erros não incluem a URI original. Na configuração por `DB_*`, o TLS deve ser definido em `DB_URL`, conforme o [guia de deploy](docs/DEPLOY.md#alternativa-conexão-em-variáveis-separadas).

Os logs de diagnóstico mostram host, banco, origem da configuração e indicadores de espaços ou aspas nas credenciais. Eles não registram usuário, senha ou a URL completa. Arquivos locais de credenciais são ignorados pelo Git e pelo contexto do Docker.

## Interface e arquivos estáticos

O React renderiza os textos dos usuários sem inserir HTML bruto. O servidor de produção serve apenas o diretório do build e rejeita travessia de diretórios, arquivos ocultos e links simbólicos que apontem para fora dele.

A Content Security Policy restringe scripts, fontes e conexões à própria origem e bloqueia objetos e enquadramento da página. Estilos inline são permitidos para dimensões dinâmicas da grade e do gráfico. As respostas também usam `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` e `Permissions-Policy`. O contêiner executa com usuário sem privilégios administrativos.

`/api/health/live` verifica apenas o processo. `/api/health` verifica a conectividade com o banco; um processo disponível não garante que o banco esteja acessível ou que a migração tenha sido aplicada.

## Verificação e limitações

Os testes Java cobrem datas, senhas, limites de autenticação, configuração, cabeçalhos e arquivos estáticos. Os testes do cliente HTTP cobrem credenciais, erros, timeout e cancelamento. Os scripts de integração verificam isolamento entre contas, persistência, regras de negócio, cookies, validação de origem, JSON e bloqueio de tentativas.

Os scripts HTTP criam contas temporárias e devem ser executados somente no banco de desenvolvimento. Com `DB_*` e `psql` disponíveis, eles removem essas contas ao final.

Recuperação de senha, confirmação de email e limitação compartilhada de abuso ainda não estão implementadas. Backups e recuperação do banco dependem da configuração da hospedagem. A suíte automatizada não equivale a uma auditoria de segurança independente.
