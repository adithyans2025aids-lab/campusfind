import java.sql.*;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;

public class SetStaffPassword {
    public static void main(String[] args) throws Exception {
        String newPassword = "admin";
        if (args.length > 0) newPassword = args[0];

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(newPassword.toCharArray(), salt, 120000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] hash = factory.generateSecret(spec).getEncoded();
        String hashedPassword = "PBKDF2$120000$" + 
            Base64.getEncoder().encodeToString(salt) + "$" + 
            Base64.getEncoder().encodeToString(hash);

        String url = "jdbc:mysql://localhost:3306/lost_and_found";
        String user = "root";
        String pass = "LostFound@2026";

        try (Connection con = DriverManager.getConnection(url, user, pass);
             PreparedStatement ps = con.prepareStatement("UPDATE staff SET password = ? WHERE email = 'admin@campusfind.com'")) {
            ps.setString(1, hashedPassword);
            int rows = ps.executeUpdate();
            System.out.println("Updated " + rows + " staff row(s) with password: " + newPassword);
        }
    }
}
