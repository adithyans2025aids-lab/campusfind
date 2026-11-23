import java.sql.*;
import java.util.Scanner;

public class Main {


private static final String URL =
        "jdbc:mysql://localhost:3306/lost_and_found";

private static final String USER = "root";

// Read the MySQL password from an environment variable.
private static final String PASSWORD =
        System.getenv("MYSQL_PASSWORD");

private static Connection connection;
private static final Scanner scanner = new Scanner(System.in);

public static void main(String[] args) {

    if (PASSWORD == null || PASSWORD.isEmpty()) {
        System.out.println(
                "MYSQL_PASSWORD environment variable is not set.");
        System.out.println(
                "Set it in the terminal before running the program.");
        return;
    }

    try {
        connection = DriverManager.getConnection(
                URL, USER, PASSWORD);

        System.out.println("====================================");
        System.out.println("       LOST AND FOUND SYSTEM");
        System.out.println("====================================");
        System.out.println("Database connected successfully!");

        runMenu();

    } catch (SQLException e) {
        System.out.println("Database connection failed!");
        System.out.println("Error: " + e.getMessage());

    } finally {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            System.out.println("Error closing database connection.");
        }

        scanner.close();
    }
}

// MAIN MENU

private static void runMenu() {

    while (true) {
        System.out.println("\n========== MAIN MENU ==========");
        System.out.println("1. Register Student");
        System.out.println("2. View Students");
        System.out.println("3. Report Lost or Found Item");
        System.out.println("4. View All Items");
        System.out.println("5. Search Items");
        System.out.println("6. Submit Claim");
        System.out.println("7. View Claims");
        System.out.println("8. Update Item Status");
        System.out.println("9. Exit");
        System.out.println("================================");

        int choice = readInt("Enter your choice: ");

        switch (choice) {
            case 1:
                registerStudent();
                break;
            case 2:
                viewStudents();
                break;
            case 3:
                reportItem();
                break;
            case 4:
                viewItems();
                break;
            case 5:
                searchItems();
                break;
            case 6:
                submitClaim();
                break;
            case 7:
                viewClaims();
                break;
            case 8:
                updateItemStatus();
                break;
            case 9:
                System.out.println("Thank you for using Lost and Found!");
                return;
            default:
                System.out.println("Invalid choice. Select 1-9.");
        }
    }
}

// INPUT METHODS

private static String readText(String message) {
    System.out.print(message);
    return scanner.nextLine().trim();
}

private static int readInt(String message) {
    while (true) {
        try {
            return Integer.parseInt(readText(message));
        } catch (NumberFormatException e) {
            System.out.println("Please enter a valid number.");
        }
    }
}

// 1. REGISTER STUDENT

private static void registerStudent() {

    System.out.println("\n--- STUDENT REGISTRATION ---");

    String name = readText("Enter student name: ");
    String registerNumber = readText("Enter register number: ");
    String email = readText("Enter student email: ");
    String password = readText("Enter student password: ");

    if (name.isEmpty() || registerNumber.isEmpty()
            || email.isEmpty() || password.isEmpty()) {
        System.out.println("All fields are required.");
        return;
    }

    String sql = "INSERT INTO students " +
            "(name, register_number, email, password) " +
            "VALUES (?, ?, ?, ?)";

    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setString(1, name);
        ps.setString(2, registerNumber);
        ps.setString(3, email);
        ps.setString(4, password);

        ps.executeUpdate();
        System.out.println("Student registered successfully!");

    } catch (SQLException e) {
        System.out.println("Registration failed: " + e.getMessage());
    }
}

// 2. VIEW STUDENTS

private static void viewStudents() {

    String sql = "SELECT student_id, name, register_number, email " +
            "FROM students ORDER BY student_id";

    try (Statement st = connection.createStatement();
         ResultSet rs = st.executeQuery(sql)) {

        boolean found = false;

        while (rs.next()) {
            found = true;
            System.out.println("\n----------------------------");
            System.out.println("Student ID: " + rs.getInt("student_id"));
            System.out.println("Name: " + rs.getString("name"));
            System.out.println("Register Number: "
                    + rs.getString("register_number"));
            System.out.println("Email: " + rs.getString("email"));
        }

        if (!found) {
            System.out.println("No students registered yet.");
        }

    } catch (SQLException e) {
        System.out.println("Unable to view students: " + e.getMessage());
    }
}

// 3. REPORT LOST OR FOUND ITEM

private static void reportItem() {

    System.out.println("\n--- REPORT LOST OR FOUND ITEM ---");

    String title = readText("Enter item name/title: ");
    String description = readText("Enter description: ");
    String category = readText("Enter category: ");
    String location = readText("Enter location: ");
    String itemType = readText("Enter type (LOST or FOUND): ")
            .toUpperCase();

    if (!itemType.equals("LOST") && !itemType.equals("FOUND")) {
        System.out.println("Type must be LOST or FOUND.");
        return;
    }

    int studentId = readInt("Enter reporting student's ID: ");

    if (title.isEmpty() || description.isEmpty()
            || category.isEmpty() || location.isEmpty()) {
        System.out.println("All item fields are required.");
        return;
    }

    String sql = "INSERT INTO items " +
            "(title, description, category, location, item_type, " +
            "reported_by, status) VALUES (?, ?, ?, ?, ?, ?, 'OPEN')";

    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setString(1, title);
        ps.setString(2, description);
        ps.setString(3, category);
        ps.setString(4, location);
        ps.setString(5, itemType);
        ps.setInt(6, studentId);

        ps.executeUpdate();
        System.out.println("Item reported successfully!");

    } catch (SQLException e) {
        System.out.println("Unable to report item: " + e.getMessage());
    }
}

// 4. VIEW ALL ITEMS

private static void viewItems() {

    String sql = "SELECT i.item_id, i.title, i.description, " +
            "i.category, i.location, i.item_type, i.reported_by, " +
            "i.report_date, i.status, s.name AS reporter_name " +
            "FROM items i LEFT JOIN students s " +
            "ON i.reported_by = s.student_id " +
            "ORDER BY i.item_id DESC";

    try (Statement st = connection.createStatement();
         ResultSet rs = st.executeQuery(sql)) {

        boolean found = false;

        while (rs.next()) {
            found = true;
            printItem(rs);
        }

        if (!found) {
            System.out.println("No items reported yet.");
        }

    } catch (SQLException e) {
        System.out.println("Unable to view items: " + e.getMessage());
    }
}

// 5. SEARCH ITEMS

private static void searchItems() {

    String keyword = readText(
            "Search by item name, category, location or description: ");

    String sql = "SELECT i.item_id, i.title, i.description, " +
            "i.category, i.location, i.item_type, i.reported_by, " +
            "i.report_date, i.status, s.name AS reporter_name " +
            "FROM items i LEFT JOIN students s " +
            "ON i.reported_by = s.student_id " +
            "WHERE i.title LIKE ? OR i.category LIKE ? " +
            "OR i.location LIKE ? OR i.description LIKE ? " +
            "ORDER BY i.item_id DESC";

    try (PreparedStatement ps = connection.prepareStatement(sql)) {

        String pattern = "%" + keyword + "%";

        ps.setString(1, pattern);
        ps.setString(2, pattern);
        ps.setString(3, pattern);
        ps.setString(4, pattern);

        try (ResultSet rs = ps.executeQuery()) {
            boolean found = false;

            while (rs.next()) {
                found = true;
                printItem(rs);
            }

            if (!found) {
                System.out.println("No matching items found.");
            }
        }

    } catch (SQLException e) {
        System.out.println("Search failed: " + e.getMessage());
    }
}

// DISPLAY ITEM DETAILS

private static void printItem(ResultSet rs) throws SQLException {

    System.out.println("\n----------------------------");
    System.out.println("Item ID: " + rs.getInt("item_id"));
    System.out.println("Name: " + rs.getString("title"));
    System.out.println("Description: " + rs.getString("description"));
    System.out.println("Category: " + rs.getString("category"));
    System.out.println("Location: " + rs.getString("location"));
    System.out.println("Type: " + rs.getString("item_type"));
    System.out.println("Reported By: " + rs.getString("reporter_name"));
    System.out.println("Report Date: " + rs.getString("report_date"));
    System.out.println("Status: " + rs.getString("status"));
}

// 6. SUBMIT CLAIM

private static void submitClaim() {

    System.out.println("\n--- SUBMIT CLAIM ---");

    int itemId = readInt("Enter item ID: ");
    int studentId = readInt("Enter your student ID: ");
    String description = readText("Explain why this item is yours: ");

    if (description.isEmpty()) {
        System.out.println("Claim description cannot be empty.");
        return;
    }

    String checkSql = "SELECT status FROM items WHERE item_id = ?";

    try (PreparedStatement check =
                 connection.prepareStatement(checkSql)) {

        check.setInt(1, itemId);

        try (ResultSet rs = check.executeQuery()) {
            if (!rs.next()) {
                System.out.println("Item not found.");
                return;
            }

            if (!"OPEN".equalsIgnoreCase(rs.getString("status"))) {
                System.out.println("This item is not open for claims.");
                return;
            }
        }

    } catch (SQLException e) {
        System.out.println("Unable to check item: " + e.getMessage());
        return;
    }

    String sql = "INSERT INTO claims " +
            "(item_id, student_id, claim_description, claim_status, " +
            "claim_date) VALUES (?, ?, ?, 'PENDING', CURRENT_DATE)";

    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setInt(1, itemId);
        ps.setInt(2, studentId);
        ps.setString(3, description);

        ps.executeUpdate();
        System.out.println("Claim submitted successfully!");
        System.out.println("Your claim is pending approval.");

    } catch (SQLException e) {
        System.out.println("Unable to submit claim: " + e.getMessage());
    }
}

// 7. VIEW CLAIMS

private static void viewClaims() {

    String sql = "SELECT c.claim_id, c.item_id, i.title, " +
            "c.student_id, s.name AS student_name, " +
            "c.claim_description, c.claim_status, c.claim_date " +
            "FROM claims c " +
            "LEFT JOIN items i ON c.item_id = i.item_id " +
            "LEFT JOIN students s ON c.student_id = s.student_id " +
            "ORDER BY c.claim_id DESC";

    try (Statement st = connection.createStatement();
         ResultSet rs = st.executeQuery(sql)) {

        boolean found = false;

        while (rs.next()) {
            found = true;

            System.out.println("\n----------------------------");
            System.out.println("Claim ID: " + rs.getInt("claim_id"));
            System.out.println("Item ID: " + rs.getInt("item_id"));
            System.out.println("Item: " + rs.getString("title"));
            System.out.println("Student ID: " + rs.getInt("student_id"));
            System.out.println("Student: " + rs.getString("student_name"));
            System.out.println("Description: "
                    + rs.getString("claim_description"));
            System.out.println("Status: " + rs.getString("claim_status"));
            System.out.println("Date: " + rs.getString("claim_date"));
        }

        if (!found) {
            System.out.println("No claims submitted yet.");
        }

    } catch (SQLException e) {
        System.out.println("Unable to view claims: " + e.getMessage());
    }
}

// 8. UPDATE ITEM STATUS

private static void updateItemStatus() {

    int itemId = readInt("Enter item ID: ");

    System.out.println("1. OPEN");
    System.out.println("2. RETURNED");
    System.out.println("3. CLOSED");

    int choice = readInt("Choose new status: ");

    String status;

    switch (choice) {
        case 1:
            status = "OPEN";
            break;
        case 2:
            status = "RETURNED";
            break;
        case 3:
            status = "CLOSED";
            break;
        default:
            System.out.println("Invalid choice.");
            return;
    }

    String sql = "UPDATE items SET status = ? WHERE item_id = ?";

    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setString(1, status);
        ps.setInt(2, itemId);

        int rows = ps.executeUpdate();

        if (rows > 0) {
            System.out.println("Item status updated to " + status);
        } else {
            System.out.println("Item ID not found.");
        }

    } catch (SQLException e) {
        System.out.println("Unable to update status: " + e.getMessage());
    }
}


}
