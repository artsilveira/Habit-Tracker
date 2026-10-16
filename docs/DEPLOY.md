# Publicar no Render com PostgreSQL no Neon

O Dockerfile compila o React e o Java. Um único serviço Java entrega a interface e a API na mesma URL HTTPS; o banco fica no Neon. Não é necessário contratar um serviço estático separado.

## 1. Criar e preparar o banco

No Neon, crie um projeto no plano Free. Escolha uma região próxima da região que usará no Render e mantenha o nome do banco como `neondb`.

Abra o **SQL Editor** na branch e no banco que serão usados pelo aplicativo. Como proprietário do banco, execute o conteúdo de [`001_initial_schema.sql`](../database/migrations/001_initial_schema.sql) uma única vez, em um banco vazio.

Depois, crie uma conta exclusiva para a aplicação pelo SQL Editor. Substitua a senha do exemplo por uma senha aleatória longa gerada pelo seu gerenciador de senhas antes de executar. Não salve o SQL preenchido no repositório.

```sql
BEGIN;
CREATE ROLE habit_tracker_app LOGIN PASSWORD 'SUBSTITUA_POR_UMA_SENHA_ALEATORIA_LONGA';
GRANT CONNECT ON DATABASE neondb TO habit_tracker_app;
GRANT USAGE ON SCHEMA public TO habit_tracker_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO habit_tracker_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO habit_tracker_app;
COMMIT;
```

Crie essa conta por SQL: contas criadas pelo painel de gerenciamento de roles do Neon recebem privilégios administrativos adicionais. Consulte a [documentação de roles do Neon](https://neon.com/docs/manage/roles).

Para simplificar o preenchimento da URL, uma senha aleatória com pelo menos 32 caracteres entre letras, números, `_` e `-` dispensa codificação. Outros caracteres na senha precisam ser codificados como parte de uma URI; não coloque uma senha com `@`, `:`, `#` ou `/` diretamente na URL.

No botão **Connect**, copie o endereço de conexão **direta**, com pooling desativado. Use o host e o banco exibidos ali, mas troque o usuário e a senha pelos da conta `habit_tracker_app`:

```text
postgresql://habit_tracker_app:SENHA_CODIFICADA@HOST_DIRETO_DO_NEON/neondb?sslmode=require
```

Esse valor será a variável privada `DATABASE_URL` no Render. Não envie a URL com senha em mensagens, capturas de tela ou commits. A aplicação converte a URI para JDBC e exige TLS com validação do certificado e do hostname, mesmo que a URL copiada indique apenas `sslmode=require`. Os parâmetros opcionais da URI são substituídos pela configuração TLS da aplicação. Veja a [documentação de SSL do pgJDBC](https://jdbc.postgresql.org/documentation/ssl/).

Use conexão direta: a aplicação abre conexões JDBC curtas e configura limites de consulta por sessão. Esta configuração não foi validada com o pooler do Neon.

## 2. Criar o serviço no Render

Com as alterações deste projeto publicadas na branch `main`:

1. No Render, escolha **New → Blueprint** e conecte o repositório `artsilveira/Habit-Tracker`.
2. O Render deve encontrar [`render.yaml`](../render.yaml). Confira que existe apenas um **Web Service**, com runtime **Docker** e plano **Free**.
3. Preencha `DATABASE_URL` com a URI privada da etapa anterior e aplique o Blueprint.
4. Aguarde o build e a implantação. A URL pública será exibida pelo Render, no formato `https://NOME.onrender.com`.

Também é possível iniciar pelo botão [Deploy to Render](https://render.com/deploy?repo=https://github.com/artsilveira/Habit-Tracker).

O Render injeta `PORT` e `RENDER_EXTERNAL_URL`. O contêiner escuta em `0.0.0.0`, e a aplicação usa a URL HTTPS informada pela plataforma como origem permitida. `COOKIE_SECURE=true` já está no Blueprint. Não copie a configuração local `APP_ORIGIN=http://localhost:5173` para o serviço publicado.

Se usar um domínio próprio, configure `APP_ORIGIN` com a origem HTTPS exata, sem caminho nem barra final, e use esse domínio para acessar a aplicação.

O esquema SQL não é aplicado durante o build ou a inicialização. Isso evita alterações automáticas no banco a cada deploy. Migrações futuras precisam ser executadas conscientemente com a conta proprietária; novas tabelas também precisam dos grants da conta da aplicação.

## 3. Conferir o primeiro deploy

- Abra `/api/health/live`: deve retornar `{"status":"UP"}`. Essa é a sondagem do Render e não acessa o PostgreSQL.
- Abra `/api/health`: deve retornar HTTP 200 e indicar que o banco está disponível. HTTP 503 nessa rota exige conferir a conexão, as permissões e a migração.
- Crie uma conta, um hábito e um registro de humor. Recarregue, saia e entre novamente para confirmar a persistência.
- No DevTools do navegador, confira o cookie `habit_session`: deve ter `HttpOnly`, `Secure`, `SameSite=Strict` e `Path=/api`.
- Confira os logs do Render e se a interface carrega sem erros de CSP ou requisições para `localhost`.

Os scripts `Test-Api.ps1` e `Test-Security.ps1` são para o banco de desenvolvimento. Eles criam contas temporárias e tentam removê-las usando `psql`; não os execute contra o banco público.

## Limites do plano gratuito

O serviço gratuito do Render suspende após 15 minutos sem tráfego. A primeira visita após a suspensão pode demorar cerca de um minuto. O workspace recebe 750 horas gratuitas por mês, compartilhadas entre seus serviços gratuitos. Veja os [limites do Render Free](https://render.com/docs/free).

O Neon Free tem cotas de armazenamento e computação e suspende o compute ocioso. Confira os valores vigentes no [plano do Neon](https://neon.com/pricing). A sondagem de liveness separada evita despertar o banco a cada verificação do Render.

Esta configuração destina-se a uma demonstração ou projeto pessoal em uma instância. No Render, a autenticação tem limites globais por instância e por conta; cabeçalhos de IP enviados pelo visitante não são confiados. Os limites reiniciam com o processo, e um visitante pode consumir a cota compartilhada. Para ampliar o uso, adote um controle compartilhado de abuso e reveja as cotas dos provedores.

Os dados ficam no PostgreSQL, pois o disco do serviço gratuito é efêmero. Configure backups ou exportações de acordo com suas necessidades; armazenamento no banco não substitui backup. Recuperação de senha e confirmação de email ainda não estão implementadas.

## Build local do contêiner (opcional)

Com Docker instalado, execute na raiz:

```text
docker build -t habit-tracker .
docker run --rm -p 8080:8080 --env-file .env.deploy habit-tracker
```

Crie `.env.deploy` somente na sua máquina, com `DATABASE_URL`, `APP_ORIGIN=http://localhost:8080` e `COOKIE_SECURE=false`. Esse arquivo é ignorado pelo Git e pelo contexto do Docker. Acesse `http://localhost:8080`. Em hospedagem pública, use sempre HTTPS e cookies seguros.
