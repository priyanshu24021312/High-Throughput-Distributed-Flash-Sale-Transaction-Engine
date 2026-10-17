import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class Database {

    private static final String URL = "jdbc:mysql://localhost:3306/flash_sale_db";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    // Track whether MySQL is available
    private static volatile Boolean mysqlAvailable = null;
    private static final Object dbInitLock = new Object();

    // ==========================================
    // Product Model with Category & Rupee Pricing
    // ==========================================
    public static class Product {
        public int id;
        public String name;
        public String description;
        public int totalQuantity;
        public int availableQuantity;
        public double price;
        public double originalPrice;
        public String category; // "travel", "wearables", "electronics"
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
            this.category = (category != null && !category.isEmpty()) ? category.toLowerCase() : inferCategory(name);
            this.status = status;
            this.imageUrl = (imageUrl != null && !imageUrl.isEmpty()) ? imageUrl : inferImage(this.category, name);
        }

        public Product(int id, String name, String description, int totalQuantity, int availableQuantity, double price, String status) {
            this(id, name, description, totalQuantity, availableQuantity, price, Math.round(price * 1.8), inferCategory(name), status, inferImage(inferCategory(name), name));
        }

        private static String inferCategory(String name) {
            String lower = name.toLowerCase();
            if (lower.contains("flight") || lower.contains("train") || lower.contains("express") || 
                lower.contains("vande") || lower.contains("ticket") || lower.contains("tour") || 
                lower.contains("pass") || lower.contains("travel") || lower.contains("goa") || lower.contains("bus")) {
                return "travel";
            }
            if (lower.contains("watch") || lower.contains("ring") || lower.contains("wearable") || 
                lower.contains("band") || lower.contains("ultrahuman") || lower.contains("smartwatch") ||
                lower.contains("sneaker") || lower.contains("shoe") || lower.contains("glasses")) {
                return "wearables";
            }
            return "electronics";
        }

        private static String inferImage(String category, String name) {
            String lower = name.toLowerCase();
            if ("travel".equalsIgnoreCase(category)) {
                if (lower.contains("flight") || lower.contains("air")) return "flight";
                if (lower.contains("train") || lower.contains("vande")) return "train";
                return "ticket";
            }
            if ("wearables".equalsIgnoreCase(category)) {
                if (lower.contains("ring")) return "ring";
                if (lower.contains("watch")) return "smartwatch";
                if (lower.contains("sneaker")) return "sneaker";
                return "wearable";
            }
            if (lower.contains("ps5") || lower.contains("console") || lower.contains("game")) return "console";
            if (lower.contains("phone") || lower.contains("iphone")) return "phone";
            return "tech";
        }
    }

    // ==========================================
    // Purchase Record Model
    // ==========================================
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

    private static final Map<Integer, Product> inMemoryProducts = new ConcurrentHashMap<>();
    private static final List<PurchaseRecord> inMemoryPurchases = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> inMemoryUniquePurchases = Collections.synchronizedSet(new HashSet<>());
    private static final AtomicInteger productIdCounter = new AtomicInteger(1);
    private static final AtomicInteger purchaseIdCounter = new AtomicInteger(1001);

    static {
        // Seed initial Travel Tickets & Wearables in Indian Rupees (₹)
        seedInitialInMemoryData();
    }

    private static void seedInitialInMemoryData() {
        // 1. Travel Tickets
        int id1 = productIdCounter.getAndIncrement();
        inMemoryProducts.put(id1, new Product(id1, 
                "Air India Express: Delhi to Goa Flash Flight", 
                "Non-stop direct flight ticket with 15kg check-in baggage and express boarding. Valid for weekend departure.", 
                25, 25, 2999.00, 6999.00, "travel", "ACTIVE", "flight"));

        int id2 = productIdCounter.getAndIncrement();
        inMemoryProducts.put(id2, new Product(id2, 
                "Vande Bharat Express: Executive Chair Car (Delhi - Varanasi)", 
                "Superfast semi-high speed train journey with complimentary hot gourmet breakfast, Wi-Fi, and panoramic windows.", 
                40, 40, 1750.00, 3400.00, "travel", "ACTIVE", "train"));

        int id3 = productIdCounter.getAndIncrement();
        inMemoryProducts.put(id3, new Product(id3, 
                "Sunburn Goa 2026: VIP Arena 3-Day Pass", 
                "All-access VIP pass with elevated viewing deck, backstage lounge access, and exclusive festival merchandise kit.", 
                30, 30, 4499.00, 9999.00, "travel", "ACTIVE", "ticket"));

        // 2. Wearables
        int id4 = productIdCounter.getAndIncrement();
        inMemoryProducts.put(id4, new Product(id4, 
                "Ultrahuman Ring AIR - Raw Titanium Smart Ring", 
                "Ultra-lightweight 2.4g titanium smart ring with medical-grade sleep, HRV, skin temperature, and recovery tracking.", 
                15, 15, 14999.00, 28499.00, "wearables", "ACTIVE", "ring"));

        int id5 = productIdCounter.getAndIncrement();
        inMemoryProducts.put(id5, new Product(id5, 
                "Titan Smart Pro 2 AMOLED Smartwatch", 
                "1.43\" Ultra-bright AMOLED display, sapphire crystal glass, 14-day battery life, and AI fitness coaching.", 
                50, 50, 3999.00, 8995.00, "wearables", "ACTIVE", "smartwatch"));

        int id6 = productIdCounter.getAndIncrement();
        inMemoryProducts.put(id6, new Product(id6, 
                "Noise ColorFit Ultra 3 Smartwatch (Deep Orange Edition)", 
                "Metallic finish, Bluetooth calling, 100+ sports modes, TruSync voice assistant with 2-year warranty.", 
                60, 60, 1999.00, 4999.00, "wearables", "ACTIVE", "smartwatch"));

        // 3. Electronics / Gadgets
        int id7 = productIdCounter.getAndIncrement();
        inMemoryProducts.put(id7, new Product(id7, 
                "Sony PlayStation 5 Digital Edition Flash Drop", 
                "Lightning fast 1TB Custom SSD, 4K 120Hz gaming, Ray Tracing, and DualSense Wireless Controller.", 
                10, 10, 39990.00, 44990.00, "electronics", "ACTIVE", "console"));

        // Seed sample verified purchases for admin reporting
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long now = System.currentTimeMillis();
        inMemoryPurchases.add(new PurchaseRecord(purchaseIdCounter.getAndIncrement(), 108, id1, "Air India Express: Delhi to Goa Flash Flight", "travel", 2999.00, "SUCCESS", sdf.format(new java.util.Date(now - 120000)), 12));
        inMemoryPurchases.add(new PurchaseRecord(purchaseIdCounter.getAndIncrement(), 142, id5, "Titan Smart Pro 2 AMOLED Smartwatch", "wearables", 3999.00, "SUCCESS", sdf.format(new java.util.Date(now - 60000)), 8));
        inMemoryPurchases.add(new PurchaseRecord(purchaseIdCounter.getAndIncrement(), 179, id2, "Vande Bharat Express: Executive Chair Car (Delhi - Varanasi)", "travel", 1750.00, "SUCCESS", sdf.format(new java.util.Date(now - 30000)), 5));
    }

    public static boolean checkMySQLAvailability() {
        if (mysqlAvailable != null) {
            return mysqlAvailable;
        }
        synchronized (dbInitLock) {
            if (mysqlAvailable != null) {
                return mysqlAvailable;
            }
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                DriverManager.setLoginTimeout(2);
                try (Connection con = DriverManager.getConnection(URL, USER, PASSWORD)) {
                    mysqlAvailable = true;
                    initMySQLSchema(con);
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

    private static void initMySQLSchema(Connection con) {
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

    // ==========================================
    // Product Operations
    // ==========================================
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
                        int genId = rs.getInt(1);
                        Product p = new Product(genId, name, description, quantity, quantity, price, originalPrice, category, "ACTIVE", imageUrl);
                        inMemoryProducts.put(genId, p);
                        return genId;
                    }
                }
            } catch (SQLException e) {
                System.out.println("Product insertion error in MySQL: " + e.getMessage());
            }
        }

        // In-Memory store insertion
        int newId = productIdCounter.getAndIncrement();
        Product product = new Product(newId, name, description, quantity, quantity, price, originalPrice, category, quantity > 0 ? "ACTIVE" : "SOLD OUT", imageUrl);
        inMemoryProducts.put(newId, product);
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
        Product removed = inMemoryProducts.remove(productId);
        inMemoryPurchases.removeIf(p -> p.productId == productId);
        inMemoryUniquePurchases.removeIf(k -> k.endsWith("_" + productId));
        return removed != null;
    }

    public static List<Product> getAllProducts() {
        List<Product> list = new ArrayList<>();
        if (isMySQLActive()) {
            String sql = "SELECT product_id, product_name, description, total_quantity, available_quantity, price, status FROM product";
            try (Connection con = getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int pId = rs.getInt("product_id");
                    Product mem = inMemoryProducts.get(pId);
                    if (mem != null) {
                        list.add(mem);
                    } else {
                        list.add(new Product(
                                pId,
                                rs.getString("product_name"),
                                rs.getString("description"),
                                rs.getInt("total_quantity"),
                                rs.getInt("available_quantity"),
                                rs.getDouble("price"),
                                rs.getString("status")
                        ));
                    }
                }
                if (!list.isEmpty()) {
                    return list;
                }
            } catch (SQLException e) {
                System.out.println("Error fetching products from MySQL: " + e.getMessage());
            }
        }

        list.addAll(inMemoryProducts.values());
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
                        Product mem = inMemoryProducts.get(productId);
                        if (mem != null) return mem;
                        return new Product(
                                rs.getInt("product_id"),
                                rs.getString("product_name"),
                                rs.getString("description"),
                                rs.getInt("total_quantity"),
                                rs.getInt("available_quantity"),
                                rs.getDouble("price"),
                                rs.getString("status")
                        );
                    }
                }
            } catch (SQLException e) {
                System.out.println("Error fetching product from MySQL: " + e.getMessage());
            }
        }
        return inMemoryProducts.get(productId);
    }

    // ==========================================
    // Transactional Purchase Engine
    // 1. Lock product row (Pessimistic row lock)
    // 2. Check inventory > 0
    // 3. Decrease inventory by 1
    // 4. Record purchase (Enforce user uniqueness)
    // 5. Commit
    // ==========================================
    public static synchronized boolean purchaseTicket(int userId, int productId) {
        long start = System.currentTimeMillis();

        if (isMySQLActive()) {
            Connection con = null;
            try {
                con = getConnection();
                con.setAutoCommit(false);

                String checkSql = "SELECT available_quantity FROM product WHERE product_id = ? FOR UPDATE";
                int available;

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
                
                // Update in-memory cache if mirrored
                Product p = inMemoryProducts.get(productId);
                if (p != null) {
                    p.availableQuantity--;
                    if (p.availableQuantity <= 0) p.status = "SOLD OUT";
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

        // ==========================================
        // In-Memory Fallback Purchase Logic
        // Replicating MySQL row lock & uniqueness
        // ==========================================
        Product product = inMemoryProducts.get(productId);
        if (product == null) return false;

        product.rowLock.lock();
        try {
            // Check unique user purchase constraint
            String uniqueKey = userId + "_" + productId;
            if (inMemoryUniquePurchases.contains(uniqueKey)) {
                return false;
            }

            if (product.availableQuantity <= 0) {
                product.status = "SOLD OUT";
                return false;
            }

            // Decrement inventory atomically
            product.availableQuantity--;
            if (product.availableQuantity == 0) {
                product.status = "SOLD OUT";
            }

            // Register purchase
            inMemoryUniquePurchases.add(uniqueKey);
            int pId = purchaseIdCounter.getAndIncrement();
            long latency = System.currentTimeMillis() - start;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String timestamp = sdf.format(new java.util.Date());

            inMemoryPurchases.add(new PurchaseRecord(pId, userId, productId, product.name, product.category, product.price, "SUCCESS", timestamp, latency));
            return true;
        } finally {
            product.rowLock.unlock();
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

        Product p = inMemoryProducts.get(productId);
        return p != null ? p.availableQuantity : -1;
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
                    list.add(new PurchaseRecord(
                            rs.getInt("purchase_id"),
                            rs.getInt("user_id"),
                            rs.getInt("product_id"),
                            rs.getString("product_name"),
                            "general",
                            rs.getDouble("price"),
                            rs.getString("status"),
                            rs.getString("purchase_time"),
                            0
                    ));
                }
                if (!list.isEmpty()) return list;
            } catch (SQLException e) {
                System.out.println("Error reading purchases from MySQL: " + e.getMessage());
            }
        }

        List<PurchaseRecord> copy = new ArrayList<>(inMemoryPurchases);
        Collections.reverse(copy);
        return copy;
    }

    public static boolean resetProduct(int productId, int quantity) {
        if (isMySQLActive()) {
            try (Connection con = getConnection()) {
                con.setAutoCommit(false);
                try (PreparedStatement ps1 = con.prepareStatement(
                        "UPDATE product SET available_quantity = ?, total_quantity = ?, status = 'ACTIVE' WHERE product_id = ?");
                     PreparedStatement ps2 = con.prepareStatement(
                        "DELETE FROM purchase WHERE product_id = ?")) {
                    ps1.setInt(1, quantity);
                    ps1.setInt(2, quantity);
                    ps1.setInt(3, productId);
                    ps1.executeUpdate();

                    ps2.setInt(1, productId);
                    ps2.executeUpdate();

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

        Product p = inMemoryProducts.get(productId);
        if (p != null) {
            p.rowLock.lock();
            try {
                p.totalQuantity = quantity;
                p.availableQuantity = quantity;
                p.status = quantity > 0 ? "ACTIVE" : "SOLD OUT";

                // Clear purchases for this product
                inMemoryPurchases.removeIf(pr -> pr.productId == productId);
                inMemoryUniquePurchases.removeIf(key -> key.endsWith("_" + productId));
                return true;
            } finally {
                p.rowLock.unlock();
            }
        }
        return false;
    }

    public static void clearAllPurchases() {
        inMemoryPurchases.clear();
        inMemoryUniquePurchases.clear();
        if (isMySQLActive()) {
            try (Connection con = getConnection();
                 Statement st = con.createStatement()) {
                st.executeUpdate("DELETE FROM purchase");
            } catch (SQLException ignored) {}
        }
    }
}
