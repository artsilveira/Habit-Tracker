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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

public final class ApiHandler implements HttpHandler {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_BODY_BYTES = 16_384;
    private final Database database;
    private final RequestSecurity security;
    private final Semaphore requests = new Semaphore(24);
    private final Semaphore authentication = new Semaphore(4);

    public ApiHandler(Database database, RequestSecurity security) {
        this.database = database;
        this.security = security;
    }

    private record User(long id, String name, String email, String timezone) {
        LocalDate today() {
            return LocalDate.now(ZoneId.of(timezone));
        }
    }

    private record Habit(long id, String name, LocalDate startDate, LocalDate endDate) {}

    private static final class Request {
        JsonNode body;
        String cookie;
    }

    private static final class ApiError extends RuntimeException {
        final int status;
        final String code;

        ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean requestPermitAcquired = false;
        boolean authPermitAcquired = false;
        try (exchange) {
            try {
                if (!security.allows(exchange)) {
                    throw new ApiError(403, "INVALID_ORIGIN", "Origem da requisição não permitida.");
                }
                if (!requests.tryAcquire()) {
                    throw new ApiError(503, "SERVER_BUSY", "Servidor ocupado. Tente novamente em instantes.");
                }
                requestPermitAcquired = true;
                Request request = new Request();
                String path = exchange.getRequestURI().getPath();
                boolean authenticationRoute = path.equals("/api/auth/login") || path.equals("/api/auth/register");
                if (authenticationRoute) {
                    requireMethod(exchange, "POST");
                    request.body = readJsonBody(exchange);
                    long retryAfterSeconds = security.limitAuth(exchange.getRemoteAddress().getAddress().getHostAddress(),
                            digest(email(request.body)), path.endsWith("register"));
                    if (retryAfterSeconds > 0) {
                        exchange.getResponseHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
                        throw new ApiError(429, "TOO_MANY_ATTEMPTS", "Muitas tentativas. Aguarde alguns minutos antes de tentar novamente.");
                    }
                    if (!authentication.tryAcquire()) {
                        throw new ApiError(503, "SERVER_BUSY", "Servidor ocupado. Tente novamente em instantes.");
                    }
                    authPermitAcquired = true;
                }
                // Ler e validar o corpo antes de abrir transações ou bloquear hábitos.
                if (Set.of("POST", "PUT", "PATCH").contains(exchange.getRequestMethod())
                        && !path.equals("/api/auth/logout") && request.body == null) {
                    request.body = readJsonBody(exchange);
                }
                try (Connection connection = database.openConnection()) {
                    if (exchange.getRequestURI().getPath().equals("/api/tracker")) {
                        // Autenticação e consulta mensal devem ler o mesmo snapshot.
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
                if (authPermitAcquired) {
                    authentication.release();
                }
                if (requestPermitAcquired) {
                    requests.release();
                }
            }
        }
    }

    private Object route(HttpExchange exchange, Connection connection, Request request) throws Exception {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/auth/register")) {
            requireMethod(exchange, "POST");
            return register(exchange, connection, request);
        }
        if (path.equals("/api/auth/login")) {
            requireMethod(exchange, "POST");
            return login(exchange, connection, request);
        }
        if (path.equals("/api/auth/logout")) {
            requireMethod(exchange, "POST");
            return logout(exchange, connection, request);
        }
        User user = authenticate(exchange, connection);
        if (path.equals("/api/auth/me")) {
            requireMethod(exchange, "GET");
            return user;
        }
        if (path.equals("/api/tracker")) {
            requireMethod(exchange, "GET");
            Map<String, String> query = readQueryParameters(exchange);
            YearMonth month = YearMonth.of(Integer.parseInt(query.getOrDefault("year", "0")),
                    Integer.parseInt(query.getOrDefault("month", "0")));
            if (month.getYear() < 1900 || month.getYear() > 9999) {
                throw new IllegalArgumentException("Ano inválido.");
            }
            return readMonthlyTracker(connection, user, month);
        }
        if (path.equals("/api/habits/order")) {
            requireMethod(exchange, "PUT");
            return reorderHabits(connection, user, request);
        }
        if (path.equals("/api/habits")) {
            requireMethod(exchange, "POST");
            return createHabit(connection, user, request);
        }
        String[] pathSegments = path.split("/");
        if (pathSegments.length >= 4 && pathSegments[2].equals("habits")) {
            long id = Long.parseLong(pathSegments[3]);
            // Bloquear usuário antes do hábito evita deadlocks com reordenação e exclusão.
            lockUser(connection, user.id);
            Habit habit = findHabitForUpdate(connection, user.id, id);
            if (pathSegments.length == 4) {
                return changeHabit(exchange, connection, request, user, habit);
            }
            if (pathSegments.length == 6 && pathSegments[4].equals("entries")) {
                return changeHabitEntry(exchange, connection, request, user, habit, pathSegments[5]);
            }
        }
        if (pathSegments.length == 4 && pathSegments[2].equals("moods")) {
            return changeMood(exchange, connection, request, user, pathSegments[3]);
        }
        throw new ApiError(404, "NOT_FOUND", "Recurso não encontrado.");
    }

    private Object changeHabit(HttpExchange exchange, Connection connection, Request request,
                               User user, Habit habit) throws SQLException {
        long id = habit.id;
        String method = exchange.getRequestMethod();
        if (method.equals("DELETE")) {
            execute(connection, "DELETE FROM habits WHERE id=? AND user_id=?", id, user.id);
            return Map.of("ok", true);
        }
        requireMethod(exchange, "PATCH");
        JsonNode requestBody = request.body;
        String name = requestBody.has("name") ? text(requestBody, "name", 100) : habit.name;
        LocalDate startDate = requestBody.has("startDate") ? date(requestBody, "startDate") : habit.startDate;
        LocalDate endDate = habit.endDate;
        if (requestBody.has("endDate")) {
            endDate = requestBody.get("endDate").isNull() ? null : date(requestBody, "endDate");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("A data final não pode ser anterior à inicial.");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM habit_entries WHERE habit_id=? AND (entry_date < ? OR (?::date IS NOT "
                + "NULL AND entry_date > ?::date)) LIMIT 1")) {
            bind(statement, id, startDate, endDate, endDate);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new ApiError(409, "PERIOD_CONFLICT", "Existem marcações fora desse período. Ajuste as datas ou remova essas marcações primeiro.");
                }
            }
        }
        execute(connection, "UPDATE habits SET name=?,start_date=?,end_date=? WHERE id=?", name, startDate, endDate, id);
        return Map.of("ok", true);
    }

    private Object changeHabitEntry(HttpExchange exchange, Connection connection, Request request,
                                    User user, Habit habit, String date) throws SQLException {
        long id = habit.id;
        String method = exchange.getRequestMethod();
        LocalDate day = LocalDate.parse(date);
        Rules.entryDate(day, habit.startDate, habit.endDate, user.today());
        if (method.equals("DELETE")) {
            execute(connection, "DELETE FROM habit_entries WHERE habit_id=? AND entry_date=?", id, day);
            return Map.of("ok", true);
        }
        requireMethod(exchange, "PUT");
        String status = text(request.body, "status", 20);
        if (!Set.of("COMPLETED", "NOT_COMPLETED").contains(status)) {
            throw new IllegalArgumentException("Status inválido.");
        }
        execute(connection,
                "INSERT INTO habit_entries(habit_id,entry_date,status) VALUES (?,?,?) "
                        + "ON CONFLICT(habit_id,entry_date) DO UPDATE SET status=EXCLUDED.status",
                id, day, status);
        return Map.of("ok", true);
    }

    private Object changeMood(HttpExchange exchange, Connection connection, Request request,
                              User user, String date) throws SQLException {
        String method = exchange.getRequestMethod();
        LocalDate day = LocalDate.parse(date);
        if (day.isAfter(user.today())) {
            throw new IllegalArgumentException("Não é permitido registrar dias futuros.");
        }
        if (method.equals("DELETE")) {
            execute(connection, "DELETE FROM mood_entries WHERE user_id=? AND entry_date=?", user.id, day);
            return Map.of("ok", true);
        }
        requireMethod(exchange, "PUT");
        JsonNode level = request.body.path("level");
        if (!level.isIntegralNumber() || !level.canConvertToInt() || level.intValue() < 1 || level.intValue() > 5) {
            throw new IllegalArgumentException("Escolha um humor de 1 a 5.");
        }
        execute(connection,
                "INSERT INTO mood_entries(user_id,entry_date,level) VALUES (?,?,?) "
                        + "ON CONFLICT(user_id,entry_date) DO UPDATE SET level=EXCLUDED.level",
                user.id, day, level.intValue());
        return Map.of("ok", true);
    }

    private User register(HttpExchange exchange, Connection connection, Request request) throws Exception {
        JsonNode requestBody = request.body;
        String name = text(requestBody, "name", 100);
        String email = email(requestBody);
        String password = password(requestBody);
        if (password.length() < 15) {
            throw new IllegalArgumentException("Use uma senha com pelo menos 15 caracteres.");
        }
        String timezone = text(requestBody, "timezone", 50);
        ZoneId.of(timezone);
        String hash = Passwords.hash(password);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users(name,email,password_hash,timezone) VALUES (?,?,?,?) RETURNING id")) {
            bind(statement, name, email, hash, timezone);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                User user = new User(resultSet.getLong(1), name, email, timezone);
                createSession(exchange, connection, user.id, request);
                return user;
            }
        }
    }

    private User login(HttpExchange exchange, Connection connection, Request request) throws Exception {
        JsonNode requestBody = request.body;
        String email = email(requestBody);
        String password = password(requestBody);
        User user = null;
        String hash = null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM users WHERE email=?")) {
            bind(statement, email);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    user = readUser(resultSet);
                    hash = resultSet.getString("password_hash");
                }
            }
        }
        // PBKDF2 pode exceder o timeout de transação ociosa em CPUs limitadas.
        // A sessão é criada em outra transação, após validar a senha.
        connection.commit();
        if (!Passwords.verify(password, hash)) {
            throw new ApiError(401, "INVALID_CREDENTIALS", "Email ou senha incorretos.");
        }
        createSession(exchange, connection, user.id, request);
        return user;
    }

    private Object logout(HttpExchange exchange, Connection connection, Request request) throws Exception {
        execute(connection, "DELETE FROM user_sessions WHERE token_hash=?", digest(sessionToken(exchange)));
        setSessionCookie(request, "", 0);
        return Map.of("ok", true);
    }

    private Object reorderHabits(Connection connection, User user, Request request) throws SQLException {
        lockUser(connection, user.id);
        JsonNode ids = request.body.path("ids");
        if (!ids.isArray() || ids.size() > 1000) {
            throw new IllegalArgumentException("Informe a lista de hábitos.");
        }
        Set<Long> orderedHabitIds = new HashSet<>();
        int order = 0;
        for (JsonNode id : ids) {
            if (!id.canConvertToLong() || !id.isIntegralNumber() || !orderedHabitIds.add(id.longValue())) {
                throw new IllegalArgumentException("Lista de hábitos inválida.");
            }
            findHabitForUpdate(connection, user.id, id.longValue());
            execute(connection, "UPDATE habits SET display_order=? WHERE id=? AND user_id=?", order++, id.longValue(), user.id);
        }
        List<Long> remainingHabitIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM habits WHERE user_id=? ORDER BY display_order,id")) {
            bind(statement, user.id);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long habitId = resultSet.getLong(1);
                    if (!orderedHabitIds.contains(habitId)) {
                        remainingHabitIds.add(habitId);
                    }
                }
            }
        }
        for (long id : remainingHabitIds) {
            execute(connection, "UPDATE habits SET display_order=? WHERE id=?", order++, id);
        }
        return Map.of("ok", true);
    }

    private Object createHabit(Connection connection, User user, Request request) throws SQLException {
        JsonNode requestBody = request.body;
        String name = text(requestBody, "name", 100);
        LocalDate startDate = date(requestBody, "startDate");
        lockUser(connection, user.id);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO habits(user_id,name,start_date,display_order) SELECT "
                + "?,?,?,COALESCE(MAX(display_order)+1,0) FROM habits WHERE user_id=? RETURNING id")) {
            bind(statement, user.id, name, startDate, user.id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return Map.of("id", resultSet.getLong(1));
            }
        }
    }

    private Object readMonthlyTracker(Connection connection, User user, YearMonth month) throws SQLException {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        LocalDate today = user.today();
        List<Map<String, Object>> habits = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM habits WHERE user_id=? AND start_date<=? AND (end_date IS NULL OR "
                + "end_date>=?) ORDER BY display_order,id")) {
            bind(statement, user.id, last, first);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> habit = new LinkedHashMap<>();
                    habit.put("id", resultSet.getLong("id"));
                    habit.put("name", resultSet.getString("name"));
                    LocalDate startDate = resultSet.getObject("start_date", LocalDate.class);
                    LocalDate endDate = resultSet.getObject("end_date", LocalDate.class);
                    habit.put("startDate", startDate.toString());
                    habit.put("endDate", endDate == null ? null : endDate.toString());
                    habit.put("eligibleDays", Rules.eligibleDays(first, last, startDate, endDate, today));
                    habits.add(habit);
                }
            }
        }
        Map<Long, Map<String, String>> entries = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT e.* FROM habit_entries e JOIN habits h ON h.id=e.habit_id WHERE h.user_id=? "
                + "AND e.entry_date BETWEEN ? AND ?")) {
            bind(statement, user.id, first, last);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.computeIfAbsent(resultSet.getLong("habit_id"), ignored -> new LinkedHashMap<>())
                            .put(resultSet.getString("entry_date"), resultSet.getString("status"));
                }
            }
        }
        for (Map<String, Object> habit : habits) {
            Map<String, String> dailyEntries = entries.getOrDefault((Long) habit.get("id"), Map.of());
            long completed = dailyEntries.values().stream().filter("COMPLETED"::equals).count();
            long notCompleted = dailyEntries.values().stream().filter("NOT_COMPLETED"::equals).count();
            int eligibleDays = (Integer) habit.get("eligibleDays");
            habit.put("entries", dailyEntries);
            habit.put("completed", completed);
            habit.put("notCompleted", notCompleted);
            habit.put("unrecorded", eligibleDays - completed - notCompleted);
            habit.put("progress", eligibleDays == 0 ? null : Math.round(completed * 100.0 / eligibleDays));
        }
        Map<String, Integer> moods = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT entry_date,level FROM mood_entries WHERE user_id=? AND entry_date BETWEEN ? "
                + "AND ? ORDER BY entry_date")) {
            bind(statement, user.id, first, last);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    moods.put(resultSet.getString(1), resultSet.getInt(2));
                }
            }
        }
        return Map.of("year", month.getYear(), "month", month.getMonthValue(), "daysInMonth", month.lengthOfMonth(),
                "today", today.toString(), "habits", habits, "moods", moods);
    }

    private User authenticate(HttpExchange exchange, Connection connection) throws Exception {
        String token = sessionToken(exchange);
        if (!token.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT u.* FROM users u JOIN user_sessions s ON s.user_id=u.id WHERE s.token_hash=? "
                    + "AND s.expires_at>CURRENT_TIMESTAMP")) {
                bind(statement, digest(token));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return readUser(resultSet);
                    }
                }
            }
        }
        throw new ApiError(401, "UNAUTHENTICATED", "Entre na sua conta para continuar.");
    }

    private static User readUser(ResultSet resultSet) throws SQLException {
        return new User(resultSet.getLong("id"), resultSet.getString("name"), resultSet.getString("email"), resultSet.getString("timezone"));
    }

    private static Habit findHabitForUpdate(Connection connection, long userId, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM habits WHERE id=? AND user_id=? FOR UPDATE")) {
            bind(statement, id, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new ApiError(404, "NOT_FOUND", "Hábito não encontrado.");
                }
                return new Habit(id, resultSet.getString("name"), resultSet.getObject("start_date", LocalDate.class), resultSet.getObject("end_date", LocalDate.class));
            }
        }
    }

    private static void lockUser(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
            bind(statement, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
            }
        }
    }

    private void createSession(HttpExchange exchange, Connection connection, long userId, Request request) throws Exception {
        String previousToken = sessionToken(exchange);
        if (!previousToken.isEmpty()) {
            execute(connection, "DELETE FROM user_sessions WHERE token_hash=?", digest(previousToken));
        }
        execute(connection, "DELETE FROM user_sessions WHERE expires_at<=CURRENT_TIMESTAMP");
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        execute(connection,
                "INSERT INTO user_sessions(token_hash,user_id,expires_at) "
                        + "VALUES (?,?,CURRENT_TIMESTAMP + INTERVAL '7 days')",
                digest(token), userId);
        execute(connection,
                "DELETE FROM user_sessions WHERE user_id=? AND token_hash IN "
                        + "(SELECT token_hash FROM user_sessions WHERE user_id=? "
                        + "ORDER BY created_at DESC,token_hash OFFSET 10)",
                userId, userId);
        setSessionCookie(request, token, 7 * 24 * 3600);
    }

    private void setSessionCookie(Request request, String token, int maxAgeSeconds) {
        request.cookie = security.sessionCookie(token, maxAgeSeconds);
    }

    private static String sessionToken(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("habit_session") && pair[1].matches("[A-Za-z0-9_-]{43}")) {
                    return pair[1];
                }
            }
        }
        return "";
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonNode readJsonBody(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new ApiError(415, "JSON_REQUIRED", "Envie os dados como JSON.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiError(413, "BODY_TOO_LARGE", "Requisição muito grande.");
        }
        try {
            JsonNode result = JSON.readTree(bytes);
            if (result == null || !result.isObject()) {
                throw new IllegalArgumentException("Informe um objeto JSON.");
            }
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON inválido.");
        }
    }

    private static String text(JsonNode requestBody, String key, int maxLength) {
        JsonNode value = requestBody.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().strip().length() > maxLength
                || value.asText().codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Campo inválido: " + key);
        }
        return value.asText().strip();
    }

    private static String email(JsonNode requestBody) {
        String email = text(requestBody, "email", 255).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Informe um email válido.");
        }
        return email;
    }

    private static String password(JsonNode requestBody) {
        JsonNode value = requestBody.path("password");
        if (!value.isTextual() || value.asText().length() < 8 || value.asText().length() > 128 || value.asText().isBlank()) {
            throw new IllegalArgumentException("A senha deve ter entre 8 e 128 caracteres.");
        }
        return value.asText();
    }

    private static LocalDate date(JsonNode requestBody, String key) {
        LocalDate value = LocalDate.parse(text(requestBody, key, 10));
        if (value.getYear() < 1900 || value.getYear() > 9999) {
            throw new IllegalArgumentException("Data inválida.");
        }
        return value;
    }

    private static void requireMethod(HttpExchange exchange, String... methods) {
        if (!List.of(methods).contains(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", String.join(", ", methods));
            throw new ApiError(405, "METHOD_NOT_ALLOWED", "Método não permitido.");
        }
    }

    private static Map<String, String> readQueryParameters(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2) {
                    values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static void send(HttpExchange exchange, int status, Object data) throws IOException {
        send(exchange, status, data, null);
    }

    private static void send(HttpExchange exchange, int status, Object data, String cookie) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(data);
        RequestSecurity.headers(exchange);
        if (cookie != null) {
            exchange.getResponseHeaders().set("Set-Cookie", cookie);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
