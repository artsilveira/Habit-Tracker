# Revisão de segurança

Revisão de 15 de outubro de 2026, com preparação de deploy em 16 de outubro de 2026. Escopo: código local, dependências, API com
PostgreSQL e interface no Chrome. Não foi realizado teste contra uma hospedagem pública.

## Correções aplicadas

| Problema | Correção |
|---|---|
| Bibliotecas Java antigas | Jackson atualizado para 2.18.11 e pgJDBC para 42.7.14. |
| Login sem limite | Limites por IP e por hash do email; resposta 429 com Retry-After. |
| Custo de autenticação sem controle | Até 4 autenticações e 24 requisições da API simultâneas. |
| Conexões e consultas sem limites completos | Limites de conexão, cabeçalhos, leitura HTTP, consultas e espera por locks. |
| API usando o administrador do banco local | Conta habit_tracker_app sem superuser, createdb ou createrole. |
| Configuração de cookie insegura em HTTPS | Origem validada na inicialização e exigência de Secure em HTTPS. |
| JSON ambíguo | Rejeição de campos duplicados, conteúdo após o objeto, corpos acima de 16 KiB e caracteres de controle em campos textuais. |
| Credenciais locais herdando permissões da pasta | Arquivo local restrito ao usuário atual no Windows; proteção no gitignore. |
| Classpath podia incluir jars antigos | Inicialização usa a lista exata de dependências resolvidas pelo Maven. |
| Erros de conexão ocultados na interface | Tela de erro com tentativa novamente; dados antigos ficam indisponíveis se a atualização após salvar falhar. |

O cadastro exige senhas de 15 a 128 caracteres. O login preserva compatibilidade
com contas anteriores de pelo menos 8 caracteres. Senhas usam PBKDF2-HMAC-SHA256,
600 mil iterações e salt aleatório individual. Tokens de sessão têm 32 bytes
aleatórios e são armazenados como hashes SHA-256. Os cookies são HttpOnly e
SameSite=Strict; o servidor compara Origin em mutações quando enviado e rejeita
Sec-Fetch-Site cross-site. Não há tokens no localStorage.

## Verificações

- Testes Java: datas, anos bissextos, senhas, limites e configuração dos cookies.
- 5 testes do cliente HTTP: credenciais, respostas inválidas, status, timeout e cancelamento.
- Testes da API: isolamento entre contas, marcação única, datas, período, ordem, humor, histórico e exclusão.
- Testes de segurança: CSRF, cookies, JSON, tamanho do corpo, senha, SQL como texto literal e bloqueio de tentativas.
- Chrome: cadastro, HTML malicioso exibido como texto, edição com conflito, humor, recuperação de falhas, modal, exclusão e logout.
- Inspeção visual em desktop e celular, com navegação da tabela por rolagem.
- Build de produção carregado no Chrome sem violações de CSP, com as três fontes locais.
- Auditoria pnpm: nenhum aviso conhecido retornado para o lockfile revisado.
- Consulta OSV das 5 dependências Java de execução atualizadas: nenhum aviso retornado.

Na preparação de deploy de 16 de outubro, o Maven executou 17 testes: 16 passaram
e um teste de symlink foi pulado por restrição do Windows. A suíte cobre também
arquivos estáticos, travessia de diretórios, configuração da URI do Neon, origem do
Render, limites atrás de proxy e liveness sem banco. Os 5 testes do cliente HTTP,
o formatador, o build React e os testes HTTP de integração e segurança passaram.
O Chrome validou o build servido pelo Java com PostgreSQL local, incluindo hábito,
marcação, humor, persistência, logout/login, fontes locais e CSP.

A imagem Docker não foi construída localmente porque o Docker não estava instalado.
O primeiro build no Render ainda precisa confirmar o empacotamento no ambiente Linux.

## Referências das correções

As versões anteriores do driver constam nos [avisos do pgJDBC](https://jdbc.postgresql.org/security/).
Jackson tem uma [correção de negação de serviço em 2.18.11](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24).
A presença de uma versão em um aviso não significa que todos os caminhos afetados
estavam expostos neste aplicativo; a atualização remove essa dependência de configuração.

O armazenamento de senhas segue os parâmetros de PBKDF2 da
[OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
Os controles de autenticação consideram o
[guia de autenticação](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html).

## Limites desta revisão

Ausência de avisos em scanners não garante ausência de vulnerabilidades. Os limites
de tentativas estão na memória de uma instância e reiniciam com o processo. Localmente
usam o IP da conexão e o hash do email. No Render, usam uma cota global por instância
(30 cadastros ou 120 logins a cada 15 minutos) e 10 tentativas por conta no mesmo período.
Não confiam em X-Forwarded-For. Um visitante pode consumir a cota compartilhada;
uso com mais tráfego exige limitação de abuso compartilhada no proxy ou banco.

HTTPS público, proxy, backups e várias instâncias não foram validados em uma
hospedagem pública. Recuperação de senha e confirmação de email não estão implementadas. A conta administrativa do
cluster local é exclusiva do script de preparação; ela não deve ser usada pela API.

O servidor Java de arquivos estáticos aplica CSP com scripts, fontes e conexões da
própria origem, frame-ancestors 'none', X-Frame-Options e nosniff. Estilos inline são
permitidos para as medidas dinâmicas da grade. O handler serve apenas o diretório
do build e rejeita travessia de diretórios, arquivos ocultos e symlinks externos.

A configuração DATABASE_URL do Neon exige TLS com certificado e hostname validados
pelo pgJDBC e separa a senha da URL JDBC. Erros de configuração não exibem a URI
com credenciais. O contêiner roda com UID sem privilégios administrativos; arquivos
locais de credenciais não entram no contexto de build. A conta SQL do Neon deve
ser criada sem privilégios administrativos conforme o [guia de deploy](docs/DEPLOY.md).

A sondagem /api/health/live não consulta o banco; /api/health é a verificação de
conectividade. Um processo saudável não garante que a migração ou a conexão estejam corretas.
