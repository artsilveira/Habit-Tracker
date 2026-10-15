# Habit Tracker

Habit tracker mensal com registro de humor, inspirado em uma tabela de papel.
React e Tailwind CSS no frontend; Java sem framework e PostgreSQL no backend.

## Executar localmente

Requisitos: JDK 21+, Maven 3.9+, PostgreSQL com psql/initdb/pg_ctl no PATH,
Node.js 22+ e npm ou pnpm. Os scripts usam PowerShell 7.

No primeiro terminal, a partir da raiz do projeto:

```powershell
# Se o JDK não estiver no PATH, defina JAVA_HOME para sua instalação.
./scripts/Start-Database.ps1
./scripts/Start-Backend.ps1
```

Start-Database cria um cluster exclusivo em .tools/postgres-data, na porta 55432,
restrito a 127.0.0.1. Não altera uma instalação PostgreSQL existente. Na primeira
execução, cria as tabelas; nas seguintes, preserva os registros. A API usa a conta
habit_tracker_app, sem privilégios administrativos. A conta habit_tracker é usada
somente pelo script para criar o banco, o esquema e as permissões.

As senhas locais ficam em .tools/local-db.json, fora do versionamento e com acesso
restrito ao usuário atual no Windows. Não copie .tools para um repositório ou hospedagem.
O script define DB_URL, DB_USER e DB_PASSWORD no terminal atual.

Se os binários não estiverem no PATH, informe a pasta de instalação:

```powershell
./scripts/Start-Database.ps1 -PostgresBin 'C:\Program Files\PostgreSQL\18\bin'
```

Start-Backend usa Maven no PATH ou a cópia local em .tools/apache-maven-3.9.11, caso
ela exista. O Maven baixa as bibliotecas na primeira execução. Não há framework Java:
o servidor usa HttpServer do JDK, JDBC e Jackson para JSON.

No segundo terminal:

```powershell
cd frontend
npm install
npm run dev
```

Também é possível usar pnpm install e pnpm dev. O lockfile versionado é do pnpm.
Com as dependências já instaladas, também é possível iniciar pela raiz usando
`./scripts/Start-Frontend.ps1`, sem precisar de npm no terminal.
Abra **http://localhost:5173** e crie sua conta. Contas novas começam vazias.
A API atende em localhost:8080, e o Vite encaminha as chamadas /api.
Use localhost no navegador para corresponder à origem configurada.

Ctrl+C encerra cada servidor. Para parar o banco:

```powershell
./scripts/Stop-Database.ps1
```

## Banco existente

Crie um banco vazio e aplique a migração com uma conta responsável pelo esquema:

```powershell
psql -h localhost -U SEU_USUARIO -d habit_tracker -v ON_ERROR_STOP=1 -f database/migrations/001_initial_schema.sql
```

Não reaplique a migração sobre tabelas existentes. Ela é transacional e não é
executada automaticamente pelo servidor Java. Para o acesso da aplicação, use uma
conta separada sem permissões de criar bancos, alterar o esquema ou administrar roles.

Defina as variáveis antes de iniciar a API:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/habit_tracker'
$env:DB_USER = 'SEU_USUARIO_DA_APLICACAO'
$dbCredential = Get-Credential -UserName $env:DB_USER -Message 'Senha do PostgreSQL'
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
./scripts/Start-Backend.ps1
```

backend/.env.example documenta as variáveis. Arquivos .env não são carregados pelo Java.

## Funcionalidades e regras

- Hábitos diários com início e fim inclusivos; continuam entre os meses até a data final.
- Grade com a quantidade real de dias do mês, incluindo anos bissextos.
- Uma marcação por hábito e dia: concluído ou não realizado. Ausência significa sem registro.
- Datas futuras e dias fora do período do hábito não aceitam marcação.
- Alterar um período deixando registros fora dele retorna conflito, sem apagar o histórico.
- Renomear altera o nome em todo o histórico. Excluir remove o hábito e suas marcações.
- Ordenação manual das linhas; hábitos fora da lista mantêm sua ordem relativa após ela.
- Um humor opcional por dia, entre 1 (muito mal) e 5 (muito bem), independente dos hábitos.
- O gráfico deixa lacunas nos dias sem humor registrado.
- Progresso considera os dias previstos já decorridos, incluindo hoje e dias sem registro.
- Sem dias elegíveis, o progresso aparece como um traço.
- Hoje é calculado no backend pelo fuso registrado no cadastro.
- Cada usuário acessa apenas os próprios dados.

## Testes

Com Maven no PATH:

```powershell
mvn -f backend/pom.xml test
```

Com banco e API em execução, no terminal em que DB_* está definido:

```powershell
./scripts/Test-Api.ps1
./scripts/Test-Security.ps1
```

Os testes HTTP criam contas temporárias e as removem ao final quando DB_* está
configurado. Execute-os no banco de desenvolvimento. Repetições em sequência podem
atingir os limites de autenticação; nesse caso, aguarde a janela de 15 minutos.

No frontend:

```powershell
cd frontend
npm test
npm run format:check
npm run build
```

A revisão e seus limites estão documentados em [SECURITY.md](SECURITY.md).

## API

Prefixo /api. Corpos em JSON; datas em YYYY-MM-DD. Erros contêm code e message.

| Método | Rota | Função |
|---|---|---|
| GET | /health | Conexão com o banco; resultado em cache por 2 segundos |
| POST | /auth/register | name, email, password e timezone |
| POST | /auth/login | email e password |
| POST | /auth/logout | Encerrar sessão; pode ser repetido |
| GET | /auth/me | Usuário autenticado |
| GET | /tracker?year=2026&month=10 | Dados e resumo do mês |
| POST | /habits | name e startDate |
| PATCH | /habits/{id} | name, startDate ou endDate; null reabre o período |
| PUT | /habits/order | ids em ordem |
| DELETE | /habits/{id} | Excluir hábito e registros |
| PUT | /habits/{id}/entries/{date} | status: COMPLETED ou NOT_COMPLETED |
| DELETE | /habits/{id}/entries/{date} | Limpar marcação |
| PUT | /moods/{date} | level: 1 a 5 |
| DELETE | /moods/{date} | Limpar humor |

Novas senhas exigem 15 a 128 caracteres. Contas criadas anteriormente com senhas de
8 a 14 caracteres continuam podendo entrar. Não há recuperação de senha nesta versão.
Sessões duram 7 dias, com no máximo 10 sessões por conta.

## Organização

```text
backend/src/main/java/br/com/habittracker/
  config/       # variáveis de ambiente
  database/     # conexões JDBC e limites de consultas
  domain/       # regras de datas e progresso
  security/     # senhas, cookies, origem e limites de autenticação
  http/         # rotas, transações e respostas
frontend/src/
  App.jsx       # verificação da sessão
  api.js        # cliente HTTP e tratamento de falhas
  calendar.js   # datas e níveis de humor
  components/   # login, tabela, resumo, gráfico e formulários
  fonts.css     # fontes latinas locais
  styles.css    # tokens visuais e layout
database/migrations/
scripts/
```

As consultas SQL são parametrizadas. Mutações de hábitos bloqueiam o usuário e o
hábito sempre na mesma ordem. A leitura mensal usa um snapshot consistente.

## Design

Papel quente, vinho e mostarda. Fraunces nos títulos, Manrope nos textos e IBM Plex
Mono nos dias e números. As cores ficam em frontend/src/styles.css. As fontes são
servidas localmente, com licenças OFL em frontend/public/fonts/licenses. A bonfire é
um SVG próprio simplificado a partir da referência fornecida para o projeto.

## Antes de hospedar

O ambiente atual é local. Para hospedar, use HTTPS e um proxy que sirva frontend e
/api na mesma origem. Defina APP_ORIGIN para a origem real, sem barra final; cookies
Secure são obrigatórios quando essa origem usa HTTPS. A API rejeita configuração
HTTP fora de localhost. A origem padrão é http://localhost:5173.

O build inclui CSP que permite scripts e fontes da própria origem. Configure também
no proxy frame-ancestors 'none', X-Content-Type-Options: nosniff e Referrer-Policy:
no-referrer. Estilos inline são permitidos para as medidas dinâmicas da tabela.

Os limites de autenticação são locais a uma instância e reiniciam com o processo.
Uma hospedagem com várias instâncias precisa de controle compartilhado no proxy ou
banco. Recuperação de senha, confirmação de email, backups e operação de produção
ficam fora desta versão.

## Nome da pasta

Para renomear para Habit Tracker, pare os servidores e o banco, feche os programas
que usam a pasta e execute no diretório pai:

```powershell
Rename-Item -LiteralPath '.\todo list' -NewName 'Habit Tracker'
```

Abra a nova pasta no editor. Os scripts calculam os caminhos a partir da raiz do projeto.
