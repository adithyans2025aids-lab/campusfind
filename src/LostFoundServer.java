import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class LostFoundServer {

    private static final Map<String, Integer> staffSessions =
            new ConcurrentHashMap<>();

    private static final String DB_URL =
            "jdbc:mysql://localhost:3306/lost_and_found";

    private static final String DB_USER = "root";

    private static final String DB_PASSWORD =
            System.getenv("MYSQL_PASSWORD");

    public static void main(String[] args) throws IOException {

        if (DB_PASSWORD == null || DB_PASSWORD.isEmpty()) {
            System.out.println("MYSQL_PASSWORD is not set.");
            return;
        }

        try {
            upgradeDemoStaffPasswords();
        } catch (Exception e) {
            System.out.println("Unable to prepare staff passwords.");
            e.printStackTrace();
            return;
        }

        HttpServer server = HttpServer.create(
                new InetSocketAddress("localhost", 8080), 0);

        // HEALTH CHECK
        server.createContext("/api/health", exchange -> {
            if (handleOptions(exchange)) return;

            try (Connection connection = getConnection()) {
                send(exchange, 200,
                        "{\"message\":\"Java API and MySQL connected successfully\"}");
            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500,
                        "{\"error\":\"Database connection failed\"}");
            }
        });

        // STUDENT REGISTRATION
        server.createContext("/api/register", exchange -> {
            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("POST")) {
                send(exchange, 405,
                        "{\"error\":\"Method not allowed\"}");
                return;
            }

            registerStudent(exchange);
        });

        // STUDENT LOGIN
        server.createContext("/api/login", exchange -> {
            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("POST")) {
                send(exchange, 405,
                        "{\"error\":\"Method not allowed\"}");
                return;
            }

            loginStudent(exchange);
        });

        // STAFF LOGIN
        server.createContext("/api/staff/login", exchange -> {
            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("POST")) {
                send(exchange, 405,
                        "{\"error\":\"Method not allowed\"}");
                return;
            }

            loginStaff(exchange);
        });

        // STAFF CLAIMS
        server.createContext("/api/staff/claims", exchange -> {
            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405,
                        "{\"error\":\"Method not allowed\"}");
                return;
            }

            if (!requireStaff(exchange)) return;

            getClaims(exchange);
        });

        // STAFF LOGOUT
        server.createContext("/api/staff/logout", exchange -> {
            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("POST")) {
                send(exchange, 405,
                        "{\"error\":\"Method not allowed\"}");
                return;
            }

            String token = getStaffToken(exchange);

            if (token != null) {
                staffSessions.remove(token);
            }

            send(exchange, 200,
                    "{\"message\":\"Staff logged out successfully\"}");
        });

        // ITEMS
        server.createContext("/api/items", exchange -> {
            if (handleOptions(exchange)) return;

            String method = exchange.getRequestMethod();

            if (method.equals("GET")) {
                getItems(exchange);

            } else if (method.equals("POST")) {
                addItem(exchange);

            } else {
                send(exchange, 405,
                        "{\"error\":\"Method not allowed\"}");
            }
        });

        // CLAIMS AND STAFF CLAIM ACTIONS
        server.createContext("/api/claims", exchange -> {
            if (handleOptions(exchange)) return;

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/api/claims")) {

                if (method.equals("GET")) {
                    getClaims(exchange);

                } else if (method.equals("POST")) {
                    addClaim(exchange);

                } else {
                    send(exchange, 405,
                            "{\"error\":\"Method not allowed\"}");
                }

            } else if (path.equals("/api/claims/status")
                    && method.equals("POST")) {

                if (!requireStaff(exchange)) return;

                updateClaimStatus(exchange);

            } else if (path.equals("/api/claims/handover")
                    && method.equals("POST")) {

                if (!requireStaff(exchange)) return;

                confirmHandover(exchange);

            } else {
                send(exchange, 404,
                        "{\"error\":\"API endpoint not found\"}");
            }
        });

        server.setExecutor(null);
        server.start();

        System.out.println("====================================");
        System.out.println(" CAMPUSFIND WEB API");
        System.out.println("====================================");
        System.out.println("Server running at http://localhost:8080");
        System.out.println("Health: http://localhost:8080/api/health");
        System.out.println("Staff: http://localhost:8080/api/staff/login");
        System.out.println("Items: http://localhost:8080/api/items");
        System.out.println("Claims: http://localhost:8080/api/claims");
        System.out.println("Press Ctrl+C to stop the server.");
    }

    // ==========================================
    // STUDENT REGISTRATION
    // ==========================================

    private static void registerStudent(HttpExchange exchange)
            throws IOException {

        String body = readBody(exchange);

        String name = safe(getJsonString(body, "name"));

        String registerNumber =
                safe(getJsonString(body, "register_number"));

        String email =
                safe(getJsonString(body, "email")).toLowerCase();

        String department =
                safe(getJsonString(body, "department"));

        String studyYear =
                safe(getJsonString(body, "study_year"));

        String password = getJsonString(body, "password");

        if (isBlank(name) || isBlank(registerNumber)
                || isBlank(email) || isBlank(department)
                || isBlank(studyYear) || isBlank(password)) {

            send(exchange, 400,
                    "{\"error\":\"All registration fields are required\"}");
            return;
        }

        if (password.length() < 8) {
            send(exchange, 400,
                    "{\"error\":\"Password must contain at least 8 characters\"}");
            return;
        }

        if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            send(exchange, 400,
                    "{\"error\":\"Please enter a valid email address\"}");
            return;
        }

        String checkSql =
                "SELECT student_id FROM students " +
                "WHERE register_number = ? OR email = ?";

        String insertSql =
                "INSERT INTO students " +
                "(name, register_number, email, password, department, study_year) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection()) {

            try (PreparedStatement check =
                         connection.prepareStatement(checkSql)) {

                check.setString(1, registerNumber);
                check.setString(2, email);

                try (ResultSet rs = check.executeQuery()) {

                    if (rs.next()) {
                        send(exchange, 409,
                                "{\"error\":\"Register number or email is already registered\"}");
                        return;
                    }
                }
            }

            String passwordHash = hashPassword(password);

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 insertSql,
                                 Statement.RETURN_GENERATED_KEYS)) {

                statement.setString(1, name);
                statement.setString(2, registerNumber);
                statement.setString(3, email);
                statement.setString(4, passwordHash);
                statement.setString(5, department);
                statement.setString(6, studyYear);

                if (statement.executeUpdate() == 0) {
                    send(exchange, 500,
                            "{\"error\":\"Registration was not completed\"}");
                    return;
                }

                try (ResultSet keys = statement.getGeneratedKeys()) {

                    if (keys.next()) {
                        int studentId = keys.getInt(1);

                        send(exchange, 201,
                                "{\"message\":\"Student registered successfully\"," +
                                "\"student_id\":" + studentId + "}");

                    } else {
                        send(exchange, 500,
                                "{\"error\":\"Unable to retrieve student ID\"}");
                    }
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();

            send(exchange, 500,
                    "{\"error\":\"Unable to register student. Check server terminal.\"}");

        } catch (Exception e) {
            e.printStackTrace();

            send(exchange, 500,
                    "{\"error\":\"Unable to secure student password\"}");
        }
    }
        // ==========================================
    // STUDENT LOGIN
    // ==========================================

    private static void loginStudent(HttpExchange exchange)
            throws IOException {

        String body = readBody(exchange);

        String email =
                safe(getJsonString(body, "email")).toLowerCase();

        String password = getJsonString(body, "password");

        if (isBlank(email) || isBlank(password)) {
            send(exchange, 400,
                    "{\"error\":\"Email and password are required\"}");
            return;
        }

        String sql =
                "SELECT student_id, name, register_number, email, password, " +
                "department, study_year FROM students WHERE email = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, email);

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    send(exchange, 401,
                            "{\"error\":\"Invalid email or password\"}");
                    return;
                }

                String storedPassword = rs.getString("password");

                if (!verifyPassword(password, storedPassword)) {
                    send(exchange, 401,
                            "{\"error\":\"Invalid email or password\"}");
                    return;
                }

                String response =
                        "{\"message\":\"Login successful\"," +
                        "\"student_id\":" + rs.getInt("student_id") +
                        ",\"name\":\"" + escape(rs.getString("name")) + "\"" +
                        ",\"register_number\":\"" +
                        escape(rs.getString("register_number")) + "\"" +
                        ",\"email\":\"" + escape(rs.getString("email")) + "\"" +
                        ",\"department\":\"" +
                        escape(rs.getString("department")) + "\"" +
                        ",\"study_year\":\"" +
                        escape(rs.getString("study_year")) + "\"}";

                send(exchange, 200, response);
            }

        } catch (SQLException e) {
            e.printStackTrace();

            send(exchange, 500,
                    "{\"error\":\"Unable to log in. Check server terminal.\"}");

        } catch (Exception e) {
            e.printStackTrace();

            send(exchange, 500,
                    "{\"error\":\"Unable to verify password\"}");
        }
    }


    // ==========================================
    // STAFF PASSWORD PREPARATION
    // ==========================================

    private static void upgradeDemoStaffPasswords()
            throws Exception {

        String selectSql =
                "SELECT staff_id, password FROM staff";

        String updateSql =
                "UPDATE staff SET password = ? WHERE staff_id = ?";

        try (Connection connection = getConnection();
             PreparedStatement select =
                     connection.prepareStatement(selectSql);
             ResultSet rs = select.executeQuery()) {

            while (rs.next()) {

                int staffId = rs.getInt("staff_id");

                String storedPassword =
                        rs.getString("password");

                if (storedPassword != null
                        && !storedPassword.startsWith("PBKDF2$")) {

                    String hashedPassword =
                            hashPassword(storedPassword);

                    try (PreparedStatement update =
                                 connection.prepareStatement(updateSql)) {

                        update.setString(1, hashedPassword);
                        update.setInt(2, staffId);

                        update.executeUpdate();
                    }

                    System.out.println(
                            "Updated password hash for staff ID: " + staffId);
                }
            }
        }
    }


    // ==========================================
    // STAFF LOGIN
    // ==========================================

    private static void loginStaff(HttpExchange exchange)
            throws IOException {

        String body = readBody(exchange);

        String email =
                safe(getJsonString(body, "email")).toLowerCase();

        String password = getJsonString(body, "password");

        if (isBlank(email) || isBlank(password)) {
            send(exchange, 400,
                    "{\"error\":\"Email and password are required\"}");
            return;
        }

        String sql =
                "SELECT staff_id, name, email, password " +
                "FROM staff WHERE email = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, email);

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    send(exchange, 401,
                            "{\"error\":\"Invalid email or password\"}");
                    return;
                }

                String storedPassword =
                        rs.getString("password");

                if (!verifyPassword(password, storedPassword)) {
                    send(exchange, 401,
                            "{\"error\":\"Invalid email or password\"}");
                    return;
                }

                int staffId = rs.getInt("staff_id");

                String name = rs.getString("name");
                String staffEmail = rs.getString("email");

                String token = UUID.randomUUID().toString();

                staffSessions.put(token, staffId);

                String response =
                        "{\"message\":\"Staff login successful\"," +
                        "\"token\":\"" + escape(token) + "\"," +
                        "\"staff\":{" +
                        "\"staff_id\":" + staffId +
                        ",\"name\":\"" + escape(name) + "\"" +
                        ",\"email\":\"" + escape(staffEmail) + "\"" +
                        "}}";

                send(exchange, 200, response);
            }

        } catch (SQLException e) {
            e.printStackTrace();

            send(exchange, 500,
                    "{\"error\":\"Unable to log in staff member\"}");

        } catch (Exception e) {
            e.printStackTrace();

            send(exchange, 500,
                    "{\"error\":\"Unable to verify staff password\"}");
        }
    }


    // ==========================================
    // GET STAFF TOKEN
    // ==========================================

    private static String getStaffToken(HttpExchange exchange) {

        String authorization =
                exchange.getRequestHeaders().getFirst("Authorization");

        if (authorization == null
                || !authorization.startsWith("Bearer ")) {
            return null;
        }

        return authorization.substring(7).trim();
    }


    // ==========================================
    // VERIFY STAFF SESSION
    // ==========================================

    private static boolean requireStaff(HttpExchange exchange)
            throws IOException {

        String token = getStaffToken(exchange);

        if (token == null || !staffSessions.containsKey(token)) {

            send(exchange, 401,
                    "{\"error\":\"Staff authentication required. Please log in again.\"}");

            return false;
        }

        return true;
    }


    // ==========================================
    // PASSWORD HASHING
    // ==========================================

    private static String hashPassword(String password)
            throws Exception {

        byte[] salt = new byte[16];

        new SecureRandom().nextBytes(salt);

        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt,
                120000,
                256);

        SecretKeyFactory factory =
                SecretKeyFactory.getInstance(
                        "PBKDF2WithHmacSHA256");

        byte[] hash =
                factory.generateSecret(spec).getEncoded();

        spec.clearPassword();

        return "PBKDF2$120000$" +
                Base64.getEncoder().encodeToString(salt) + "$" +
                Base64.getEncoder().encodeToString(hash);
    }


    // ==========================================
    // VERIFY PASSWORD
    // ==========================================

    private static boolean verifyPassword(
            String password, String stored) throws Exception {

        if (stored == null || !stored.startsWith("PBKDF2$")) {
            return false;
        }

        String[] parts = stored.split("\\$");

        if (parts.length != 4) {
            return false;
        }

        int iterations = Integer.parseInt(parts[1]);

        byte[] salt =
                Base64.getDecoder().decode(parts[2]);

        byte[] expectedHash =
                Base64.getDecoder().decode(parts[3]);

        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt,
                iterations,
                expectedHash.length * 8);

        SecretKeyFactory factory =
                SecretKeyFactory.getInstance(
                        "PBKDF2WithHmacSHA256");

        byte[] actualHash =
                factory.generateSecret(spec).getEncoded();

        spec.clearPassword();

        if (actualHash.length != expectedHash.length) {
            return false;
        }

        int difference = 0;

        for (int i = 0; i < actualHash.length; i++) {
            difference |= actualHash[i] ^ expectedHash[i];
        }

        return difference == 0;
    }
        // ==========================================
    // GET ALL ITEMS
    // ==========================================

    private static void getItems(HttpExchange exchange)
            throws IOException {

        String sql =
                "SELECT item_id, title, description, category, " +
                "location, item_type, status, report_date " +
                "FROM items ORDER BY item_id DESC";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            StringBuilder json = new StringBuilder("[");
            boolean first = true;

            while (rs.next()) {

                if (!first) json.append(",");
                first = false;

                json.append("{")
                    .append("\"item_id\":").append(rs.getInt("item_id"))
                    .append(",\"title\":\"").append(escape(rs.getString("title")))
                    .append("\",\"description\":\"").append(escape(rs.getString("description")))
                    .append("\",\"category\":\"").append(escape(rs.getString("category")))
                    .append("\",\"location\":\"").append(escape(rs.getString("location")))
                    .append("\",\"item_type\":\"").append(escape(rs.getString("item_type")))
                    .append("\",\"status\":\"").append(escape(rs.getString("status")))
                    .append("\",\"report_date\":\"").append(escape(rs.getString("report_date")))
                    .append("\"}");
            }

            json.append("]");
            send(exchange, 200, json.toString());

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500,
                    "{\"error\":\"Unable to retrieve items\"}");
        }
    }


    // ==========================================
    // ADD NEW ITEM
    // ==========================================

    private static void addItem(HttpExchange exchange)
            throws IOException {

        String body = readBody(exchange);

        String title = getJsonString(body, "title");
        String description = getJsonString(body, "description");
        String category = getJsonString(body, "category");
        String location = getJsonString(body, "location");
        String itemType = getJsonString(body, "item_type");
        String reportedByText = getJsonString(body, "reported_by");

        if (isBlank(title) || isBlank(description)
                || isBlank(category) || isBlank(location)
                || isBlank(itemType) || isBlank(reportedByText)) {

            send(exchange, 400,
                    "{\"error\":\"Required fields are missing\"}");
            return;
        }

        if (!itemType.equals("FOUND") && !itemType.equals("LOST")) {
            send(exchange, 400,
                    "{\"error\":\"item_type must be FOUND or LOST\"}");
            return;
        }

        int reportedBy;

        try {
            reportedBy = Integer.parseInt(reportedByText);
        } catch (NumberFormatException e) {
            send(exchange, 400,
                    "{\"error\":\"Invalid student ID\"}");
            return;
        }

        String sql =
                "INSERT INTO items " +
                "(title, description, category, location, " +
                "item_type, reported_by, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(
                             sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, title);
            statement.setString(2, description);
            statement.setString(3, category);
            statement.setString(4, location);
            statement.setString(5, itemType);
            statement.setInt(6, reportedBy);

            int affectedRows = statement.executeUpdate();

            if (affectedRows == 0) {
                send(exchange, 500,
                        "{\"error\":\"Item was not saved\"}");
                return;
            }

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    send(exchange, 201,
                            "{\"message\":\"Item saved successfully\"," +
                            "\"item_id\":" + keys.getInt(1) + "}");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500,
                    "{\"error\":\"Unable to save item\"}");
        }
    }


    // ==========================================
    // ADD NEW CLAIM
    // ==========================================

    private static void addClaim(HttpExchange exchange)
            throws IOException {

        String body = readBody(exchange);

        String itemIdText = getJsonString(body, "item_id");
        String studentIdText = getJsonString(body, "student_id");

        String claimantName = getJsonString(body, "claimantName");
        String claimantRegister = getJsonString(body, "claimantRegister");
        String claimantEmail = getJsonString(body, "claimantEmail");
        String ownershipProof = getJsonString(body, "ownershipProof");

        if (isBlank(studentIdText)) {
            studentIdText = "1";
        }

        if (isBlank(itemIdText) || isBlank(ownershipProof)) {
            send(exchange, 400,
                    "{\"error\":\"Item ID and ownership proof are required\"}");
            return;
        }

        int itemId;
        int studentId;

        try {
            itemId = Integer.parseInt(itemIdText);
            studentId = Integer.parseInt(studentIdText);

            if (itemId <= 0 || studentId <= 0) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {
            send(exchange, 400,
                    "{\"error\":\"Invalid item ID or student ID\"}");
            return;
        }

        String claimDescription =
                "Claimant Name: " + safe(claimantName) +
                "\nRegister Number: " + safe(claimantRegister) +
                "\nEmail: " + safe(claimantEmail) +
                "\nOwnership Proof: " + ownershipProof;

        String sql =
                "INSERT INTO claims " +
                "(item_id, student_id, claim_description, claim_status) " +
                "VALUES (?, ?, ?, 'PENDING')";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(
                             sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setInt(1, itemId);
            statement.setInt(2, studentId);
            statement.setString(3, claimDescription);

            int affectedRows = statement.executeUpdate();

            if (affectedRows == 0) {
                send(exchange, 500,
                        "{\"error\":\"Claim was not saved\"}");
                return;
            }

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    send(exchange, 201,
                            "{\"message\":\"Claim submitted successfully\"," +
                            "\"claim_id\":" + keys.getInt(1) + "}");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500,
                    "{\"error\":\"Unable to save claim. Check server terminal.\"}");
        }
    }
        // ==========================================
    // GET ALL CLAIMS
    // ==========================================

    private static void getClaims(HttpExchange exchange)
            throws IOException {

        String sql =
                "SELECT c.claim_id, c.item_id, c.student_id, " +
                "c.claim_description, c.claim_status, c.claim_date, " +
                "i.title AS item_title " +
                "FROM claims c " +
                "LEFT JOIN items i ON c.item_id = i.item_id " +
                "ORDER BY c.claim_id DESC";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            StringBuilder json = new StringBuilder("[");
            boolean first = true;

            while (rs.next()) {

                if (!first) json.append(",");
                first = false;

                json.append("{")
                    .append("\"claim_id\":").append(rs.getInt("claim_id"))
                    .append(",\"item_id\":").append(rs.getInt("item_id"))
                    .append(",\"student_id\":").append(rs.getInt("student_id"))
                    .append(",\"item_title\":\"").append(escape(rs.getString("item_title")))
                    .append("\",\"claim_description\":\"").append(escape(rs.getString("claim_description")))
                    .append("\",\"claim_status\":\"").append(escape(rs.getString("claim_status")))
                    .append("\",\"claim_date\":\"").append(escape(rs.getString("claim_date")))
                    .append("\"}");
            }

            json.append("]");
            send(exchange, 200, json.toString());

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500,
                    "{\"error\":\"Unable to retrieve claims\"}");
        }
    }


    // ==========================================
    // STAFF-ONLY CLAIM RETRIEVAL
    // ==========================================

    private static void getStaffClaims(HttpExchange exchange)
            throws IOException {

        if (!requireStaff(exchange)) {
            return;
        }

        getClaims(exchange);
    }


    // ==========================================
    // STAFF LOGOUT
    // ==========================================

    private static void logoutStaff(HttpExchange exchange)
            throws IOException {

        String token = getStaffToken(exchange);

        if (token != null) {
            staffSessions.remove(token);
        }

        send(exchange, 200,
                "{\"message\":\"Staff logged out successfully\"}");
    }


    // ==========================================
    // APPROVE OR REJECT CLAIM
    // ==========================================

    private static void updateClaimStatus(HttpExchange exchange)
            throws IOException {

        String body = readBody(exchange);

        String claimIdText = getJsonString(body, "claim_id");
        String newStatus = getJsonString(body, "status");

        if (isBlank(claimIdText) || isBlank(newStatus)) {
            send(exchange, 400,
                    "{\"error\":\"Claim ID and status are required\"}");
            return;
        }

        if (!newStatus.equals("APPROVED")
                && !newStatus.equals("REJECTED")) {

            send(exchange, 400,
                    "{\"error\":\"Status must be APPROVED or REJECTED\"}");
            return;
        }

        int claimId;

        try {
            claimId = Integer.parseInt(claimIdText);

            if (claimId <= 0) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {
            send(exchange, 400,
                    "{\"error\":\"Invalid claim ID\"}");
            return;
        }

        String sql =
                "UPDATE claims SET claim_status = ? " +
                "WHERE claim_id = ? AND claim_status = 'PENDING'";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, newStatus);
            statement.setInt(2, claimId);

            int rows = statement.executeUpdate();

            if (rows == 0) {
                send(exchange, 409,
                        "{\"error\":\"Claim not found or no longer pending\"}");
                return;
            }

            send(exchange, 200,
                    "{\"message\":\"Claim status updated successfully\"," +
                    "\"status\":\"" + newStatus + "\"}");

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500,
                    "{\"error\":\"Unable to update claim status\"}");
        }
    }


    // ==========================================
    // CONFIRM HANDOVER
    // ==========================================

    private static void confirmHandover(HttpExchange exchange)
            throws IOException {

        String body = readBody(exchange);
        String claimIdText = getJsonString(body, "claim_id");

        if (isBlank(claimIdText)) {
            send(exchange, 400,
                    "{\"error\":\"Claim ID is required\"}");
            return;
        }

        int claimId;

        try {
            claimId = Integer.parseInt(claimIdText);

            if (claimId <= 0) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {
            send(exchange, 400,
                    "{\"error\":\"Invalid claim ID\"}");
            return;
        }

        try (Connection connection = getConnection()) {

            connection.setAutoCommit(false);

            try {
                int itemId;

                String checkSql =
                        "SELECT item_id FROM claims " +
                        "WHERE claim_id = ? AND claim_status = 'APPROVED' " +
                        "FOR UPDATE";

                try (PreparedStatement check =
                             connection.prepareStatement(checkSql)) {

                    check.setInt(1, claimId);

                    try (ResultSet rs = check.executeQuery()) {

                        if (!rs.next()) {
                            connection.rollback();
                            send(exchange, 409,
                                    "{\"error\":\"Claim not found or not approved\"}");
                            return;
                        }

                        itemId = rs.getInt("item_id");
                    }
                }

                String itemSql =
                        "UPDATE items SET status = 'RETURNED' " +
                        "WHERE item_id = ? AND item_type = 'FOUND' " +
                        "AND status = 'AVAILABLE'";

                try (PreparedStatement itemUpdate =
                             connection.prepareStatement(itemSql)) {

                    itemUpdate.setInt(1, itemId);

                    if (itemUpdate.executeUpdate() == 0) {
                        connection.rollback();
                        send(exchange, 409,
                                "{\"error\":\"Item is already returned or unavailable\"}");
                        return;
                    }
                }

                String claimSql =
                        "UPDATE claims SET claim_status = 'RETURNED' " +
                        "WHERE claim_id = ? AND claim_status = 'APPROVED'";

                try (PreparedStatement claimUpdate =
                             connection.prepareStatement(claimSql)) {

                    claimUpdate.setInt(1, claimId);

                    if (claimUpdate.executeUpdate() == 0) {
                        connection.rollback();
                        send(exchange, 409,
                                "{\"error\":\"Unable to update claim\"}");
                        return;
                    }
                }

                connection.commit();

                send(exchange, 200,
                        "{\"message\":\"Handover confirmed. Item marked as returned.\"}");

            } catch (SQLException e) {
                connection.rollback();
                throw e;

            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500,
                    "{\"error\":\"Unable to confirm handover\"}");
        }
    }


    // ==========================================
    // READ REQUEST BODY
    // ==========================================

    private static String readBody(HttpExchange exchange)
            throws IOException {

        try (InputStream input = exchange.getRequestBody()) {
            return new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }


    // ==========================================
    // HANDLE OPTIONS REQUEST
    // ==========================================

    private static boolean handleOptions(HttpExchange exchange)
            throws IOException {

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods",
                "GET, POST, OPTIONS");

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers",
                "Content-Type, Authorization, bypass-tunnel-reminder, x-requested-with");

        if (exchange.getRequestMethod().equals("OPTIONS")) {
            send(exchange, 204, "");
            return true;
        }

        return false;
    }


    // ==========================================
    // READ JSON VALUES
    // ==========================================

    private static String getJsonString(String json, String key) {

        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(key) +
                "\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(-?\\d+))");

        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return null;
        }

        if (matcher.group(1) != null) {
            return unescape(matcher.group(1));
        }

        return matcher.group(2);
    }


    // ==========================================
    // UNESCAPE JSON
    // ==========================================

    private static String unescape(String value) {

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            if (c == '\\' && i + 1 < value.length()) {

                char next = value.charAt(++i);

                switch (next) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    default: result.append(next);
                }

            } else {
                result.append(c);
            }
        }

        return result.toString();
    }


    // ==========================================
    // COMMON HELPERS
    // ==========================================

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }


    // ==========================================
    // DATABASE CONNECTION
    // ==========================================

    private static Connection getConnection()
            throws SQLException {

        return DriverManager.getConnection(
                DB_URL, DB_USER, DB_PASSWORD);
    }


    // ==========================================
    // ESCAPE JSON TEXT
    // ==========================================

    private static String escape(String value) {

        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }


    // ==========================================
    // SEND RESPONSE
    // ==========================================

    private static void send(
            HttpExchange exchange, int status, String response)
            throws IOException {

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin", "*");

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods", "GET, POST, OPTIONS");

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers", "Content-Type, Authorization, bypass-tunnel-reminder, x-requested-with");

        exchange.getResponseHeaders().set(
                "Cache-Control", "no-store");

        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}