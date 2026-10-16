package br.com.habittracker.http;

import br.com.habittracker.database.Database;
import br.com.habittracker.domain.Rules;
import br.com.habittracker.security.Passwords;
import br.com.habittracker.security.RequestSecurity;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

public final class ApiHandler implements HttpHandler {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Database database;
    private final RequestSecurity security;
    private final Semaphore requests = new Semaphore(24);
    private final Semaphore authentication = new Semaphore(4);

    public ApiHandler(Database database, RequestSecurity security) {
        this.database = database;
        this.security = security;
    }

    private record User(long id, String name, String email, String timezone) {
        LocalDate today() { return LocalDate.now(ZoneId.of(timezone)); }
    }
    private record Habit(long id, String name, LocalDate start, LocalDate end) {}
    private static final class Request {
        JsonNode body;
        String cookie;
    }
    private static final class ApiError extends RuntimeException {
        final int status;
        final String code;
        ApiError(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean acquired = false;
        boolean authAcquired = false;
        try (exchange) {
            try {
                if (!security.allows(exchange)) throw new ApiError(403, "INVALID_ORIGIN", "Origem da requisição não permitida.");
                if (!requests.tryAcquire()) throw new ApiError(503, "SERVER_BUSY", "Servidor ocupado. Tente novamente em instantes.");
                acquired = true;
                Request request = new Request();
                String path = exchange.getRequestURI().getPath();
                boolean isAuth = path.equals("/api/auth/login") || path.equals("/api/auth/register");
                if (isAuth) {
                    requireMethod(exchange, "POST");
                    JsonNode b = request.body = body(exchange);
                    long wait = security.limitAuth(exchange.getRemoteAddress().getAddress().getHostAddress(),
                            digest(email(b)), path.endsWith("register"));
                    if (wait > 0) {
                        exchange.getResponseHeaders().set("Retry-After", Long.toString(wait));
                        throw new ApiError(429, "TOO_MANY_ATTEMPTS", "Muitas tentativas. Aguarde alguns minutos antes de tentar novamente.");
                    }
                    if (!authentication.tryAcquire()) throw new ApiError(503, "SERVER_BUSY", "Servidor ocupado. Tente novamente em instantes.");
                    authAcquired = true;
                }
                // Ler e validar o corpo antes de abrir transações ou bloquear hábitos.
                if (Set.of("POST", "PUT", "PATCH").contains(exchange.getRequestMethod())
                        && !path.equals("/api/auth/logout") && request.body == null) request.body = body(exchange);
                try (Connection connection = database.openConnection()) {
                    if (exchange.getRequestURI().getPath().equals("/api/tracker")) {
                        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    }
                    connection.setAutoCommit(false);
                    Object response;
                    try {
                        response = route(exchange, connection, request);
                        connection.commit();
                    } catch (Exception exception) {
                        connection.rollback();
                        throw exception;
                    }
                    // A resposta só começa após commit; não tentar rollback depois de enviar bytes.
                    send(exchange, 200, response, request.cookie);
                }
            } catch (ApiError exception) {
                send(exchange, exception.status, Map.of("code", exception.code, "message", exception.getMessage()));
            } catch (DateTimeException exception) {
                send(exchange, 400, Map.of("code", "INVALID_DATE", "message", "Data ou fuso horário inválido."));
            } catch (IllegalArgumentException exception) {
                send(exchange, 400, Map.of("code", "INVALID_INPUT", "message",
                        exception instanceof NumberFormatException ? "Identificador ou período inválido." :
                        exception.getMessage() == null ? "Dados inválidos." : exception.getMessage()));
            } catch (SQLException exception) {
                if ("23505".equals(exception.getSQLState())) {
                    send(exchange, 409, Map.of("code", "CONFLICT", "message", "Esse registro já existe."));
                } else {
                    System.err.println("Falha de banco: SQLState=" + exception.getSQLState());
                    send(exchange, 503, Map.of("code", "DATABASE_UNAVAILABLE", "message", "Banco de dados indisponível. Tente novamente."));
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Exception exception) {
                System.err.println("Falha na API: " + exception.getClass().getSimpleName());
                send(exchange, 500, Map.of("code", "INTERNAL_ERROR", "message", "Não foi possível concluir a operação."));
            } finally {
                if (authAcquired) authentication.release();
                if (acquired) requests.release();
            }
        }
    }

    private Object route(HttpExchange x, Connection c, Request request) throws Exception {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        if (path.equals("/api/auth/register")) {
            requireMethod(x, "POST");
            JsonNode b = request.body;
            String name = text(b, "name", 100), email = email(b), password = password(b);
            if (password.length() < 15) throw new IllegalArgumentException("Use uma senha com pelo menos 15 caracteres.");
            String timezone = text(b, "timezone", 50);
            ZoneId.of(timezone);
            String hash = Passwords.hash(password);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO users(name,email,password_hash,timezone) VALUES (?,?,?,?) RETURNING id")) {
                bind(s, name, email, hash, timezone);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    User u = new User(r.getLong(1), name, email, timezone);
                    createSession(x, c, u.id, request);
                    return u;
                }
            }
        }
        if (path.equals("/api/auth/login")) {
            requireMethod(x, "POST");
            JsonNode b = request.body;
            String email = email(b), password = password(b);
            User u = null;
            String hash = null;
            try (PreparedStatement s = c.prepareStatement("SELECT * FROM users WHERE email=?")) {
                bind(s, email);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) { u = user(r); hash = r.getString("password_hash"); }
                }
            }
            // Encerrar a leitura antes do PBKDF2: em CPUs limitadas, o cálculo pode
            // ultrapassar idle_in_transaction_session_timeout. A sessão será
            // gravada em uma nova transação somente após a senha ser validada.
            c.commit();
            if (!Passwords.verify(password, hash)) throw new ApiError(401, "INVALID_CREDENTIALS", "Email ou senha incorretos.");
            createSession(x, c, u.id, request);
            return u;
        }
        if (path.equals("/api/auth/logout")) {
            requireMethod(x, "POST");
            execute(c, "DELETE FROM user_sessions WHERE token_hash=?", digest(sessionToken(x)));
            cookie(request, "", 0);
            return Map.of("ok", true);
        }
        User u = authenticate(x, c);
        if (path.equals("/api/auth/me")) { requireMethod(x, "GET"); return u; }
        if (path.equals("/api/tracker")) {
            requireMethod(x, "GET");
            Map<String, String> query = query(x);
            YearMonth month = YearMonth.of(Integer.parseInt(query.getOrDefault("year", "0")),
                    Integer.parseInt(query.getOrDefault("month", "0")));
            if (month.getYear() < 1900 || month.getYear() > 9999) throw new IllegalArgumentException("Ano inválido.");
            return tracker(c, u, month);
        }
        if (path.equals("/api/habits/order")) {
            requireMethod(x, "PUT");
            lockUser(c, u.id);
            JsonNode ids = request.body.path("ids");
            if (!ids.isArray() || ids.size() > 1000) throw new IllegalArgumentException("Informe a lista de hábitos.");
            Set<Long> seen = new HashSet<>();
            int order = 0;
            for (JsonNode id : ids) {
                if (!id.canConvertToLong() || !id.isIntegralNumber() || !seen.add(id.longValue())) throw new IllegalArgumentException("Lista de hábitos inválida.");
                habit(c, u.id, id.longValue());
                execute(c, "UPDATE habits SET display_order=? WHERE id=? AND user_id=?", order++, id.longValue(), u.id);
            }
            // Other (e.g. archived) habits keep a stable order after the supplied list.
            List<Long> others = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT id FROM habits WHERE user_id=? ORDER BY display_order,id")) {
                bind(s, u.id);
                try (ResultSet r = s.executeQuery()) { while (r.next()) if (!seen.contains(r.getLong(1))) others.add(r.getLong(1)); }
            }
            for (long id : others) execute(c, "UPDATE habits SET display_order=? WHERE id=?", order++, id);
            return Map.of("ok", true);
        }
        if (path.equals("/api/habits")) {
            requireMethod(x, "POST");
            JsonNode b = request.body;
            String name = text(b, "name", 100);
            LocalDate start = date(b, "startDate");
            lockUser(c, u.id);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO habits(user_id,name,start_date,display_order) SELECT ?,?,?,COALESCE(MAX(display_order)+1,0) FROM habits WHERE user_id=? RETURNING id")) {
                bind(s, u.id, name, start, u.id);
                try (ResultSet r = s.executeQuery()) { r.next(); return Map.of("id", r.getLong(1)); }
            }
        }
        String[] p = path.split("/");
        if (p.length >= 4 && p[2].equals("habits")) {
            long id = Long.parseLong(p[3]);
            // Consistent lock order for all habit mutations avoids reorder/delete deadlocks.
            lockUser(c, u.id);
            Habit h = habit(c, u.id, id);
            if (p.length == 4) {
                if (method.equals("DELETE")) {
                    execute(c, "DELETE FROM habits WHERE id=? AND user_id=?", id, u.id);
                    return Map.of("ok", true);
                }
                requireMethod(x, "PATCH");
                JsonNode b = request.body;
                String name = b.has("name") ? text(b, "name", 100) : h.name;
                LocalDate start = b.has("startDate") ? date(b, "startDate") : h.start;
                LocalDate end = b.has("endDate") ? (b.get("endDate").isNull() ? null : date(b, "endDate")) : h.end;
                if (end != null && end.isBefore(start)) throw new IllegalArgumentException("A data final não pode ser anterior à inicial.");
                try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM habit_entries WHERE habit_id=? AND (entry_date < ? OR (?::date IS NOT NULL AND entry_date > ?::date)) LIMIT 1")) {
                    bind(s, id, start, end, end);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) throw new ApiError(409, "PERIOD_CONFLICT", "Existem marcações fora desse período. Ajuste as datas ou remova essas marcações primeiro.");
                    }
                }
                execute(c, "UPDATE habits SET name=?,start_date=?,end_date=? WHERE id=?", name, start, end, id);
                return Map.of("ok", true);
            }
            if (p.length == 6 && p[4].equals("entries")) {
                LocalDate day = LocalDate.parse(p[5]);
                Rules.entryDate(day, h.start, h.end, u.today());
                if (method.equals("DELETE")) {
                    execute(c, "DELETE FROM habit_entries WHERE habit_id=? AND entry_date=?", id, day);
                    return Map.of("ok", true);
                }
                requireMethod(x, "PUT");
                String status = text(request.body, "status", 20);
                if (!Set.of("COMPLETED", "NOT_COMPLETED").contains(status)) throw new IllegalArgumentException("Status inválido.");
                execute(c, "INSERT INTO habit_entries(habit_id,entry_date,status) VALUES (?,?,?) ON CONFLICT(habit_id,entry_date) DO UPDATE SET status=EXCLUDED.status", id, day, status);
                return Map.of("ok", true);
            }
        }
        if (p.length == 4 && p[2].equals("moods")) {
            LocalDate day = LocalDate.parse(p[3]);
            if (day.isAfter(u.today())) throw new IllegalArgumentException("Não é permitido registrar dias futuros.");
            if (method.equals("DELETE")) {
                execute(c, "DELETE FROM mood_entries WHERE user_id=? AND entry_date=?", u.id, day);
                return Map.of("ok", true);
            }
            requireMethod(x, "PUT");
            JsonNode level = request.body.path("level");
            if (!level.isIntegralNumber() || !level.canConvertToInt() || level.intValue() < 1 || level.intValue() > 5) throw new IllegalArgumentException("Escolha um humor de 1 a 5.");
            execute(c, "INSERT INTO mood_entries(user_id,entry_date,level) VALUES (?,?,?) ON CONFLICT(user_id,entry_date) DO UPDATE SET level=EXCLUDED.level", u.id, day, level.intValue());
            return Map.of("ok", true);
        }
        throw new ApiError(404, "NOT_FOUND", "Recurso não encontrado.");
    }

    private Object tracker(Connection c, User u, YearMonth month) throws SQLException {
        // Transaction isolation is set before authentication, so these queries share a snapshot.
        LocalDate first = month.atDay(1), last = month.atEndOfMonth(), today = u.today();
        List<Map<String, Object>> habits = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM habits WHERE user_id=? AND start_date<=? AND (end_date IS NULL OR end_date>=?) ORDER BY display_order,id")) {
            bind(s, u.id, last, first);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("id", r.getLong("id")); h.put("name", r.getString("name"));
                    LocalDate start = r.getObject("start_date", LocalDate.class), end = r.getObject("end_date", LocalDate.class);
                    h.put("startDate", start.toString()); h.put("endDate", end == null ? null : end.toString());
                    h.put("eligibleDays", Rules.eligibleDays(first, last, start, end, today));
                    habits.add(h);
                }
            }
        }
        Map<Long, Map<String, String>> entries = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement("SELECT e.* FROM habit_entries e JOIN habits h ON h.id=e.habit_id WHERE h.user_id=? AND e.entry_date BETWEEN ? AND ?")) {
            bind(s, u.id, first, last);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) entries.computeIfAbsent(r.getLong("habit_id"), ignored -> new LinkedHashMap<>())
                        .put(r.getString("entry_date"), r.getString("status"));
            }
        }
        for (Map<String, Object> h : habits) {
            Map<String, String> marks = entries.getOrDefault((Long) h.get("id"), Map.of());
            long completed = marks.values().stream().filter("COMPLETED"::equals).count();
            long missed = marks.values().stream().filter("NOT_COMPLETED"::equals).count();
            int eligible = (Integer) h.get("eligibleDays");
            h.put("entries", marks); h.put("completed", completed); h.put("notCompleted", missed);
            h.put("unrecorded", eligible - completed - missed);
            h.put("progress", eligible == 0 ? null : Math.round(completed * 100.0 / eligible));
        }
        Map<String, Integer> moods = new LinkedHashMap<>();
        try (PreparedStatement s = c.prepareStatement("SELECT entry_date,level FROM mood_entries WHERE user_id=? AND entry_date BETWEEN ? AND ? ORDER BY entry_date")) {
            bind(s, u.id, first, last);
            try (ResultSet r = s.executeQuery()) { while (r.next()) moods.put(r.getString(1), r.getInt(2)); }
        }
        return Map.of("year", month.getYear(), "month", month.getMonthValue(), "daysInMonth", month.lengthOfMonth(),
                "today", today.toString(), "habits", habits, "moods", moods);
    }

    private User authenticate(HttpExchange x, Connection c) throws Exception {
        String token = sessionToken(x);
        if (!token.isEmpty()) {
            try (PreparedStatement s = c.prepareStatement("SELECT u.* FROM users u JOIN user_sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP")) {
                bind(s, digest(token));
                try (ResultSet r = s.executeQuery()) { if (r.next()) return user(r); }
            }
        }
        throw new ApiError(401, "UNAUTHENTICATED", "Entre na sua conta para continuar.");
    }

    private static User user(ResultSet r) throws SQLException {
        return new User(r.getLong("id"), r.getString("name"), r.getString("email"), r.getString("timezone"));
    }

    private static Habit habit(Connection c, long user, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM habits WHERE id=? AND user_id=? FOR UPDATE")) {
            bind(s, id, user);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new ApiError(404, "NOT_FOUND", "Hábito não encontrado.");
                return new Habit(id, r.getString("name"), r.getObject("start_date", LocalDate.class), r.getObject("end_date", LocalDate.class));
            }
        }
    }

    private static void lockUser(Connection c, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
            bind(s, id);
            try (ResultSet r = s.executeQuery()) { r.next(); }
        }
    }

    private void createSession(HttpExchange x, Connection c, long user, Request request) throws Exception {
        // Rotate any existing browser token and remove expired sessions.
        String old = sessionToken(x);
        if (!old.isEmpty()) execute(c, "DELETE FROM user_sessions WHERE token_hash=?", digest(old));
        execute(c, "DELETE FROM user_sessions WHERE expires_at<=CURRENT_TIMESTAMP");
        byte[] random = new byte[32]; RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        execute(c, "INSERT INTO user_sessions(token_hash,user_id,expires_at) VALUES (?,?,CURRENT_TIMESTAMP + INTERVAL '7 days')", digest(token), user);
        execute(c, "DELETE FROM user_sessions WHERE user_id=? AND token_hash IN (SELECT token_hash FROM user_sessions WHERE user_id=? ORDER BY created_at DESC,token_hash OFFSET 10)", user, user);
        cookie(request, token, 7 * 24 * 3600);
    }

    private void cookie(Request request, String token, int age) {
        request.cookie = security.sessionCookie(token, age);
    }

    private static String sessionToken(HttpExchange x) {
        for (String header : x.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("habit_session") && pair[1].matches("[A-Za-z0-9_-]{43}")) return pair[1];
            }
        }
        return "";
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonNode body(HttpExchange x) throws IOException {
        String type = x.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new ApiError(415, "JSON_REQUIRED", "Envie os dados como JSON.");
        byte[] bytes = x.getRequestBody().readNBytes(16_385);
        if (bytes.length > 16_384) throw new ApiError(413, "BODY_TOO_LARGE", "Requisição muito grande.");
        try {
            JsonNode result = JSON.readTree(bytes);
            if (result == null || !result.isObject()) throw new IllegalArgumentException("Informe um objeto JSON.");
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON inválido.");
        }
    }

    private static String text(JsonNode b, String key, int max) {
        JsonNode value = b.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().strip().length() > max
                || value.asText().codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Campo inválido: " + key);
        return value.asText().strip();
    }

    private static String email(JsonNode b) {
        String email = text(b, "email", 255).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Informe um email válido.");
        return email;
    }

    private static String password(JsonNode b) {
        JsonNode value = b.path("password");
        if (!value.isTextual() || value.asText().length() < 8 || value.asText().length() > 128 || value.asText().isBlank()) throw new IllegalArgumentException("A senha deve ter entre 8 e 128 caracteres.");
        return value.asText();
    }

    private static LocalDate date(JsonNode b, String key) {
        LocalDate value = LocalDate.parse(text(b, key, 10));
        if (value.getYear() < 1900 || value.getYear() > 9999) throw new IllegalArgumentException("Data inválida.");
        return value;
    }

    private static void requireMethod(HttpExchange x, String... methods) {
        if (!List.of(methods).contains(x.getRequestMethod())) {
            x.getResponseHeaders().set("Allow", String.join(", ", methods));
            throw new ApiError(405, "METHOD_NOT_ALLOWED", "Método não permitido.");
        }
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> values = new HashMap<>();
        String query = x.getRequestURI().getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) { bind(s, args); s.executeUpdate(); }
    }

    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
    }

    private static void send(HttpExchange x, int status, Object data) throws IOException {
        send(x, status, data, null);
    }

    private static void send(HttpExchange x, int status, Object data, String cookie) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(data);
        RequestSecurity.headers(x);
        if (cookie != null) x.getResponseHeaders().set("Set-Cookie", cookie);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
}
