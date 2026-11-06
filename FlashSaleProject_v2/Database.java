import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class Database {

    private static final String URL = "jdbc:mysql://localhost:3306/flash_sale_db";
    private static final String USER = "root";
    private static final String PASSWORD = System.getenv("DB_PASSWORD");

    // null = not checked yet, true = MySQL works, false = use memory
    private static volatile Boolean mysqlAvailable = null;
    private static final Object checkLock = new Object();

    // name of the big lock used inside purchaseTicket (shown in wait-for graph)
    private static final String DB_LOCK = "DB-LOCK";

    public static class Product {
        public int id;
        public String name;
        public String description;
        public int totalQuantity;
        public int availableQuantity;
        public double price;
        public double originalPrice;
        public String category;   // travel, wearables, electronics
        public String status;
        public String imageUrl;
        public final ReentrantLock rowLock = new ReentrantLock();

        public Product(int id, String name, String description, int totalQuantity, int availableQuantity,
                       double price, double originalPrice, String category, String status, String imageUrl) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.totalQuantity = totalQuantity;
            this.availableQuantity = availableQuantity;
            this.price = price;
            this.originalPrice = originalPrice > 0 ? originalPrice : Math.round(price * 1.8);
            this.category = (category != null && !category.isEmpty()) ? category.toLowerCase() : findCategory(name);
            this.status = status;
            this.imageUrl = (imageUrl != null && !imageUrl.isEmpty()) ? imageUrl : findImage(this.category, name);
        }

        public Product(int id, String name, String description, int totalQuantity, int availableQuantity, double price, String status) {
            this(id, name, description, totalQuantity, availableQuantity, price, Math.round(price * 1.8),
                    findCategory(name), status, findImage(findCategory(name), name));
        }

        // true if the text has any one of the words
        private static boolean hasAny(String text, String... words) {
            for (String word : words) {
                if (text.contains(word)) return true;
            }
            return false;
        }

        private static String findCategory(String name) {
            String text = name.toLowerCase();
            if (hasAny(text, "flight", "train", "express", "vande", "ticket", "tour", "pass", "travel", "goa", "bus")) {
                return "travel";
            }
            if (hasAny(text, "watch", "ring", "wearable", "band", "ultrahuman", "smartwatch", "sneaker", "shoe", "glasses")) {
                return "wearables";
            }
            return "electronics";
        }

        private static String findImage(String category, String name) {
            String text = name.toLowerCase();
            if ("travel".equalsIgnoreCase(category)) {
                if (hasAny(text, "flight", "air")) return "flight";
                if (hasAny(text, "train", "vande")) return "train";
                return "ticket";
            }
            if ("wearables".equalsIgnoreCase(category)) {
                if (text.contains("ring")) return "ring";
                if (text.contains("watch")) return "smartwatch";
                if (text.contains("sneaker")) return "sneaker";
                return "wearable";
            }
            if (hasAny(text, "ps5", "console", "game")) return "console";
            if (hasAny(text, "phone", "iphone")) return "phone";
            return "tech";
        }
    }

    public static class PurchaseRecord {
        public int purchaseId;
        public int userId;
        public int productId;
        public String productName;
        public String category;
        public double price;
        public String status;
        public String purchaseTime;
        public long latencyMs;

        public PurchaseRecord(int purchaseId, int userId, int productId, String productName,
                              String category, double price, String status, String purchaseTime, long latencyMs) {
            this.purchaseId = purchaseId;
            this.userId = userId;
            this.productId = productId;
            this.productName = productName;
            this.category = category;
            this.price = price;
            this.status = status;
            this.purchaseTime = purchaseTime;
            this.latencyMs = latencyMs;
        }
    }

    // memory store (used when MySQL is not running)
    private static final Map<Integer, Product> products = new ConcurrentHashMap<>();
    private static final List<PurchaseRecord> purchases = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> boughtKeys = Collections.synchronizedSet(new HashSet<>());   // "userId_productId"
    private static final AtomicInteger nextProductId = new AtomicInteger(1);
    private static final AtomicInteger nextPurchaseId = new AtomicInteger(1001);

    static {
        addStartingData();
    }

    private static int addStartingProduct(String name, String description, int quantity,
                                          double price, double originalPrice, String category, String image) {
        int id = nextProductId.getAndIncrement();
        products.put(id, new Product(id, name, description, quantity, quantity, price, originalPrice, category, "ACTIVE", image));
        return id;
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(millis));
    }

    private static void addStartingData() {
        // travel tickets
        int flightId = addStartingProduct("Air India Express: Delhi to Goa Flash Flight",
                "Non-stop direct flight ticket with 15kg check-in baggage and express boarding. Valid for weekend departure.",
                25, 2999.00, 6999.00, "travel", "flight");
        int trainId = addStartingProduct("Vande Bharat Express: Executive Chair Car (Delhi - Varanasi)",
                "Superfast semi-high speed train journey with complimentary hot gourmet breakfast, Wi-Fi, and panoramic windows.",
                40, 1750.00, 3400.00, "travel", "train");
        addStartingProduct("Sunburn Goa 2026: VIP Arena 3-Day Pass",
                "All-access VIP pass with elevated viewing deck, backstage lounge access, and exclusive festival merchandise kit.",
                30, 4499.00, 9999.00, "travel", "ticket");

        // wearables
        addStartingProduct("Ultrahuman Ring AIR - Raw Titanium Smart Ring",
                "Ultra-lightweight 2.4g titanium smart ring with medical-grade sleep, HRV, skin temperature, and recovery tracking.",
                15, 14999.00, 28499.00, "wearables", "ring");
        int titanId = addStartingProduct("Titan Smart Pro 2 AMOLED Smartwatch",
                "1.43\" Ultra-bright AMOLED display, sapphire crystal glass, 14-day battery life, and AI fitness coaching.",
                50, 3999.00, 8995.00, "wearables", "smartwatch");
        addStartingProduct("Noise ColorFit Ultra 3 Smartwatch (Deep Orange Edition)",
                "Metallic finish, Bluetooth calling, 100+ sports modes, TruSync voice assistant with 2-year warranty.",
                60, 1999.00, 4999.00, "wearables", "smartwatch");

        // electronics
        addStartingProduct("Sony PlayStation 5 Digital Edition Flash Drop",
                "Lightning fast 1TB Custom SSD, 4K 120Hz gaming, Ray Tracing, and DualSense Wireless Controller.",
                10, 39990.00, 44990.00, "electronics", "console");

        // few old orders so the admin page is not empty
        long now = System.currentTimeMillis();
        purchases.add(new PurchaseRecord(nextPurchaseId.getAndIncrement(), 108, flightId, "Air India Express: Delhi to Goa Flash Flight", "travel", 2999.00, "SUCCESS", formatTime(now - 120000), 12));
        purchases.add(new PurchaseRecord(nextPurchaseId.getAndIncrement(), 142, titanId, "Titan Smart Pro 2 AMOLED Smartwatch", "wearables", 3999.00, "SUCCESS", formatTime(now - 60000), 8));
        purchases.add(new PurchaseRecord(nextPurchaseId.getAndIncrement(), 179, trainId, "Vande Bharat Express: Executive Chair Car (Delhi - Varanasi)", "travel", 1750.00, "SUCCESS", formatTime(now - 30000), 5));
    }

    // ---------- MySQL connection ----------
    public static boolean checkMySQLAvailability() {
        if (mysqlAvailable != null) return mysqlAvailable;
        synchronized (checkLock) {
            if (mysqlAvailable != null) return mysqlAvailable;
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                DriverManager.setLoginTimeout(2);
                try (Connection con = DriverManager.getConnection(URL, USER, PASSWORD)) {
                    mysqlAvailable = true;
                    createTables(con);
                    System.out.println("[Database] Successfully connected to MySQL on localhost:3306!");
                    return true;
                }
            } catch (Throwable e) {
                mysqlAvailable = false;
                System.out.println("[Database] MySQL not reachable (" + e.getMessage() + ").");
                System.out.println("[Database] Seamlessly activated High-Performance In-Memory ACID Store.");
                return false;
            }
        }
    }

    private static void createTables(Connection con) {
        try (Statement stmt = con.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS product (" +
                    "product_id INT PRIMARY KEY AUTO_INCREMENT, " +
                    "product_name VARCHAR(100) NOT NULL, " +
                    "description VARCHAR(255), " +
                    "total_quantity INT NOT NULL CHECK (total_quantity >= 0), " +
                    "available_quantity INT NOT NULL CHECK (available_quantity >= 0), " +
                    "price DECIMAL(10,2) NOT NULL CHECK (price >= 0), " +
                    "status VARCHAR(20) NOT NULL)");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS purchase (" +
                    "purchase_id INT PRIMARY KEY AUTO_INCREMENT, " +
                    "user_id INT NOT NULL, " +
                    "product_id INT NOT NULL, " +
                    "status VARCHAR(20) NOT NULL, " +
                    "purchase_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "CONSTRAINT fk_purchase_product FOREIGN KEY (product_id) REFERENCES product(product_id), " +
                    "CONSTRAINT unique_user_product UNIQUE (user_id, product_id))");
        } catch (SQLException e) {
            System.out.println("[Database] Schema check notice: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC Driver not found.", e);
        }
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static boolean isMySQLActive() {
        return Boolean.TRUE.equals(mysqlAvailable) || checkMySQLAvailability();
    }

    public static String getStorageEngineName() {
        return isMySQLActive() ? "MySQL 8.0 (Active Connection)" : "In-Memory Engine (ACID Row-Locking)";
    }

    // ---------- products ----------
    public static int addProduct(String name, String description, int quantity, double price) {
        return addProduct(name, description, quantity, price, "general", Math.round(price * 1.8), "tech");
    }

    public static int addProduct(String name, String description, int quantity, double price,
                                 String category, double originalPrice, String imageUrl) {
        if (isMySQLActive()) {
            String sql = "INSERT INTO product (product_name, description, total_quantity, available_quantity, price, status) " +
                         "VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection con = getConnection();
                 PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, description);
                ps.setInt(3, quantity);
                ps.setInt(4, quantity);
                ps.setDouble(5, price);
                ps.setString(6, quantity > 0 ? "ACTIVE" : "SOLD OUT");
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int newId = rs.getInt(1);
                        products.put(newId, new Product(newId, name, description, quantity, quantity, price, originalPrice, category, "ACTIVE", imageUrl));
                        return newId;
                    }
                }
            } catch (SQLException e) {
                System.out.println("Product insertion error in MySQL: " + e.getMessage());
            }
        }

        int newId = nextProductId.getAndIncrement();
        String status = quantity > 0 ? "ACTIVE" : "SOLD OUT";
        products.put(newId, new Product(newId, name, description, quantity, quantity, price, originalPrice, category, status, imageUrl));
        return newId;
    }

    public static boolean deleteProduct(int productId) {
        if (isMySQLActive()) {
            try (Connection con = getConnection();
                 PreparedStatement ps = con.prepareStatement("DELETE FROM product WHERE product_id = ?")) {
                ps.setInt(1, productId);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("Error deleting product from MySQL: " + e.getMessage());
            }
        }
        Product removed = products.remove(productId);
        purchases.removeIf(p -> p.productId == productId);
        boughtKeys.removeIf(key -> key.endsWith("_" + productId));
        return removed != null;
    }

    // admin changes the details of a product (name, price, description, category, total quantity)
    // tickets that are already sold stay sold: new available = new total - sold
    public static boolean updateProduct(int productId, String name, String description, double price,
                                        double originalPrice, String category, int totalQuantity) {
        Product product = products.get(productId);
        if (product == null) {
            // product is only in MySQL, so bring it into memory first
            product = getProduct(productId);
            if (product == null) return false;
            products.put(productId, product);
        }

        lockProduct(product);
        try {
            int sold = product.totalQuantity - product.availableQuantity;
            int newAvailable = Math.max(0, totalQuantity - sold);

            if (isMySQLActive()) {
                int fromMySQL = updateInMySQL(productId, name, description, price, totalQuantity);
                if (fromMySQL >= 0) newAvailable = fromMySQL;
            }

            product.name = name;
            product.description = description;
            product.price = price;
            if (originalPrice > 0) product.originalPrice = originalPrice;
            if (category != null && !category.isEmpty()) product.category = category.toLowerCase();
            product.imageUrl = Product.findImage(product.category, name);   // icon follows the new name
            product.totalQuantity = totalQuantity;
            product.availableQuantity = newAvailable;
            product.status = newAvailable > 0 ? "ACTIVE" : "SOLD OUT";
            return true;
        } finally {
            unlockProduct(product);
        }
    }

    // returns the new available quantity, or -1 if MySQL update failed
    private static int updateInMySQL(int productId, String name, String description, double price, int totalQuantity) {
        try (Connection con = getConnection()) {
            con.setAutoCommit(false);
            try {
                int sold;
                String selectSql = "SELECT total_quantity, available_quantity FROM product WHERE product_id = ? FOR UPDATE";
                try (PreparedStatement ps = con.prepareStatement(selectSql)) {
                    ps.setInt(1, productId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return -1;
                        }
                        sold = rs.getInt("total_quantity") - rs.getInt("available_quantity");
                    }
                }

                int newAvailable = Math.max(0, totalQuantity - sold);
                String updateSql = "UPDATE product SET product_name = ?, description = ?, price = ?, " +
                                   "total_quantity = ?, available_quantity = ?, status = ? WHERE product_id = ?";
                try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                    ps.setString(1, name);
                    ps.setString(2, description);
                    ps.setDouble(3, price);
                    ps.setInt(4, totalQuantity);
                    ps.setInt(5, newAvailable);
                    ps.setString(6, newAvailable > 0 ? "ACTIVE" : "SOLD OUT");
                    ps.setInt(7, productId);
                    ps.executeUpdate();
                }

                con.commit();
                return newAvailable;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.out.println("Error updating product in MySQL: " + e.getMessage());
            return -1;
        }
    }

    public static List<Product> getAllProducts() {
        List<Product> list = new ArrayList<>();
        if (isMySQLActive()) {
            String sql = "SELECT product_id, product_name, description, total_quantity, available_quantity, price, status FROM product";
            try (Connection con = getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("product_id");
                    Product saved = products.get(id);
                    if (saved != null) {
                        list.add(saved);
                    } else {
                        list.add(new Product(id, rs.getString("product_name"), rs.getString("description"),
                                rs.getInt("total_quantity"), rs.getInt("available_quantity"),
                                rs.getDouble("price"), rs.getString("status")));
                    }
                }
                if (!list.isEmpty()) return list;
            } catch (SQLException e) {
                System.out.println("Error fetching products from MySQL: " + e.getMessage());
            }
        }

        list.addAll(products.values());
        list.sort(Comparator.comparingInt(p -> p.id));
        return list;
    }

    public static Product getProduct(int productId) {
        if (isMySQLActive()) {
            String sql = "SELECT product_id, product_name, description, total_quantity, available_quantity, price, status " +
                         "FROM product WHERE product_id = ?";
            try (Connection con = getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, productId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Product saved = products.get(productId);
                        if (saved != null) return saved;
                        return new Product(rs.getInt("product_id"), rs.getString("product_name"), rs.getString("description"),
                                rs.getInt("total_quantity"), rs.getInt("available_quantity"),
                                rs.getDouble("price"), rs.getString("status"));
                    }
                }
            } catch (SQLException e) {
                System.out.println("Error fetching product from MySQL: " + e.getMessage());
            }
        }
        return products.get(productId);
    }

    // ---------- row lock helpers (they also tell the wait-for graph) ----------
    private static void lockProduct(Product product) {
        String me = Thread.currentThread().getName();
        String lockName = "Product-" + product.id;
        WaitForGraph.startWaiting(me, lockName);
        product.rowLock.lock();
        WaitForGraph.gotLock(me, lockName);
    }

    private static void unlockProduct(Product product) {
        product.rowLock.unlock();
        WaitForGraph.releaseLock(Thread.currentThread().getName(), "Product-" + product.id);
    }

    // ---------- buying a ticket ----------
    // Only one thread can be inside at a time (same as the old synchronized method).
    public static boolean purchaseTicket(int userId, int productId) {
        String me = Thread.currentThread().getName();
        WaitForGraph.startWaiting(me, DB_LOCK);
        synchronized (Database.class) {
            WaitForGraph.gotLock(me, DB_LOCK);
            try {
                return buyTicket(userId, productId);
            } finally {
                WaitForGraph.releaseLock(me, DB_LOCK);
            }
        }
    }

    // Steps: lock the row, check stock, reduce stock by 1, save the purchase, commit
    private static boolean buyTicket(int userId, int productId) {
        long start = System.currentTimeMillis();

        if (isMySQLActive()) {
            Connection con = null;
            try {
                con = getConnection();
                con.setAutoCommit(false);

                int available;
                String checkSql = "SELECT available_quantity FROM product WHERE product_id = ? FOR UPDATE";
                try (PreparedStatement ps = con.prepareStatement(checkSql)) {
                    ps.setInt(1, productId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return false;
                        }
                        available = rs.getInt("available_quantity");
                    }
                }

                if (available <= 0) {
                    con.rollback();
                    return false;
                }

                String updateSql = "UPDATE product " +
                        "SET available_quantity = available_quantity - 1, " +
                        "status = CASE WHEN available_quantity - 1 = 0 THEN 'SOLD OUT' ELSE 'ACTIVE' END " +
                        "WHERE product_id = ?";
                try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                    ps.setInt(1, productId);
                    ps.executeUpdate();
                }

                String insertSql = "INSERT INTO purchase (user_id, product_id, status) VALUES (?, ?, 'SUCCESS')";
                try (PreparedStatement ps = con.prepareStatement(insertSql)) {
                    ps.setInt(1, userId);
                    ps.setInt(2, productId);
                    ps.executeUpdate();
                }

                con.commit();

                // keep the memory copy same as database
                Product product = products.get(productId);
                if (product != null) {
                    product.availableQuantity--;
                    if (product.availableQuantity <= 0) product.status = "SOLD OUT";
                }
                return true;

            } catch (SQLException e) {
                if (con != null) {
                    try { con.rollback(); } catch (SQLException ignored) {}
                }
                return false;
            } finally {
                if (con != null) {
                    try { con.close(); } catch (SQLException ignored) {}
                }
            }
        }

        // no MySQL, do the same steps in memory
        Product product = products.get(productId);
        if (product == null) return false;

        lockProduct(product);
        try {
            // one user can buy a product only once
            String key = userId + "_" + productId;
            if (boughtKeys.contains(key)) return false;

            if (product.availableQuantity <= 0) {
                product.status = "SOLD OUT";
                return false;
            }

            product.availableQuantity--;
            if (product.availableQuantity == 0) product.status = "SOLD OUT";

            boughtKeys.add(key);
            int purchaseId = nextPurchaseId.getAndIncrement();
            long timeTaken = System.currentTimeMillis() - start;
            String time = formatTime(System.currentTimeMillis());

            purchases.add(new PurchaseRecord(purchaseId, userId, productId, product.name, product.category, product.price, "SUCCESS", time, timeTaken));
            return true;
        } finally {
            unlockProduct(product);
        }
    }

    public static int getAvailableQuantity(int productId) {
        if (isMySQLActive()) {
            String sql = "SELECT available_quantity FROM product WHERE product_id = ?";
            try (Connection con = getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, productId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt("available_quantity");
                }
            } catch (SQLException e) {
                System.out.println("Quantity check error in MySQL: " + e.getMessage());
            }
        }
        Product product = products.get(productId);
        return product != null ? product.availableQuantity : -1;
    }

    public static List<PurchaseRecord> getAllPurchases() {
        if (isMySQLActive()) {
            List<PurchaseRecord> list = new ArrayList<>();
            String sql = "SELECT p.purchase_id, p.user_id, p.product_id, pr.product_name, pr.price, p.status, p.purchase_time " +
                         "FROM purchase p JOIN product pr ON p.product_id = pr.product_id ORDER BY p.purchase_id DESC LIMIT 200";
            try (Connection con = getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PurchaseRecord(rs.getInt("purchase_id"), rs.getInt("user_id"), rs.getInt("product_id"),
                            rs.getString("product_name"), "general", rs.getDouble("price"),
                            rs.getString("status"), rs.getString("purchase_time"), 0));
                }
                if (!list.isEmpty()) return list;
            } catch (SQLException e) {
                System.out.println("Error reading purchases from MySQL: " + e.getMessage());
            }
        }

        List<PurchaseRecord> copy = new ArrayList<>(purchases);
        Collections.reverse(copy);
        return copy;
    }

    public static boolean resetProduct(int productId, int quantity) {
        if (isMySQLActive()) {
            try (Connection con = getConnection()) {
                con.setAutoCommit(false);
                try (PreparedStatement updateStmt = con.prepareStatement(
                        "UPDATE product SET available_quantity = ?, total_quantity = ?, status = 'ACTIVE' WHERE product_id = ?");
                     PreparedStatement deleteStmt = con.prepareStatement(
                        "DELETE FROM purchase WHERE product_id = ?")) {
                    updateStmt.setInt(1, quantity);
                    updateStmt.setInt(2, quantity);
                    updateStmt.setInt(3, productId);
                    updateStmt.executeUpdate();

                    deleteStmt.setInt(1, productId);
                    deleteStmt.executeUpdate();

                    con.commit();
                } catch (SQLException e) {
                    con.rollback();
                    return false;
                }
                return true;
            } catch (SQLException e) {
                System.out.println("Error resetting MySQL product: " + e.getMessage());
            }
        }

        Product product = products.get(productId);
        if (product == null) return false;

        lockProduct(product);
        try {
            product.totalQuantity = quantity;
            product.availableQuantity = quantity;
            product.status = quantity > 0 ? "ACTIVE" : "SOLD OUT";
            purchases.removeIf(p -> p.productId == productId);
            boughtKeys.removeIf(key -> key.endsWith("_" + productId));
            return true;
        } finally {
            unlockProduct(product);
        }
    }

    public static void clearAllPurchases() {
        purchases.clear();
        boughtKeys.clear();
        if (isMySQLActive()) {
            try (Connection con = getConnection();
                 Statement st = con.createStatement()) {
                st.executeUpdate("DELETE FROM purchase");
            } catch (SQLException ignored) {}
        }
    }
}
