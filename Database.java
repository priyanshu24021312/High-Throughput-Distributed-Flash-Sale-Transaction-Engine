import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class Database {

    private static final String URL = "jdbc:mysql://localhost:3306/flash_sale_db?useSSL=false&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    // returns the new product_id, or -1 if the insert failed
    public static int addProduct(String name, String description, int quantity, double price) {
        String sql = "INSERT INTO product (product_name, description, total_quantity, available_quantity, price, status) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, name);
            ps.setString(2, description);
            ps.setInt(3, quantity);
            ps.setInt(4, quantity);
            ps.setDouble(5, price);
            ps.setString(6, quantity > 0 ? "ACTIVE" : "SOLD OUT");
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.out.println("Product insertion error: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Saves one confirmed order: every product is decremented and gets a purchase row,
     * all inside a single MySQL transaction. Product ids should come in sorted so two
     * orders never lock the rows in opposite order. Retries on MySQL deadlock/lock timeout.
     */
    public static boolean confirmPurchase(int userId, List<Integer> productIds) {
        // MySQL evaluates the SET list left to right, so by the time the CASE runs
        // available_quantity has already been decremented
        String updateSql = "UPDATE product SET available_quantity = available_quantity - 1, "
                + "status = CASE WHEN available_quantity = 0 THEN 'SOLD OUT' ELSE 'ACTIVE' END "
                + "WHERE product_id = ? AND available_quantity > 0";
        String insertSql = "INSERT INTO purchase (user_id, product_id, status) VALUES (?, ?, 'SUCCESS')";

        int maxTries = 3;
        for (int attempt = 1; attempt <= maxTries; attempt++) {
            try (Connection con = getConnection()) {
                con.setAutoCommit(false);
                try (PreparedStatement upd = con.prepareStatement(updateSql);
                     PreparedStatement ins = con.prepareStatement(insertSql)) {

                    for (int productId : productIds) {
                        upd.setInt(1, productId);
                        if (upd.executeUpdate() == 0) {
                            con.rollback();
                            return false;
                        }
                        ins.setInt(1, userId);
                        ins.setInt(2, productId);
                        ins.executeUpdate();
                    }
                    con.commit();
                    return true;

                } catch (SQLException e) {
                    con.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                boolean retryable = e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
                if (retryable && attempt < maxTries) {
                    try {
                        Thread.sleep(50L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    continue;
                }
                System.out.println("Purchase save error: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    // -1 if the product isn't there or the query failed
    public static int getAvailableQuantity(int productId) {
        String sql = "SELECT available_quantity FROM product WHERE product_id = ?";

        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("available_quantity");
                }
            }
        } catch (SQLException e) {
            System.out.println("Quantity check error: " + e.getMessage());
        }
        return -1;
    }
}
