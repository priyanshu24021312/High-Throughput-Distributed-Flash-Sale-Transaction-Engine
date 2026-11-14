import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// The transaction engine.
//  - products are kept in a B+ Tree index
//  - concurrency control: strict 2PL, every product row has a lock in the LockManager
//  - a purchase is 2 steps: hold (stock is reserved for 5 minutes) and pay (order is confirmed with a PNR)
//  - every change is written to the WAL first, so the state can be rebuilt after a crash (recover)
//  - MySQL is optional: if it is running, committed data is also saved there
public class Database {

    private static final String URL = "jdbc:mysql://localhost:3306/flash_sale_db";
    private static final String USER = "root";
    private static final String PASSWORD = "24021312";
    private static final String SNAPSHOT_FILE = "data/snapshot.dat";

    public static final int HOLD_SECONDS = Integer.getInteger("hold.seconds", 300);   // 5 minutes
    private static final long LOCK_WAIT_MS = 10000;

    // null = not checked yet, true = MySQL works, false = memory only
    private static volatile Boolean mysqlAvailable = null;
    private static final Object checkLock = new Object();

    public static class Product {
        public int id;
        public volatile String name;
        public volatile String description;
        public volatile int totalQuantity;
        public volatile int availableQuantity;
        public volatile double price;
        public volatile double originalPrice;
        public volatile String category;   // travel, wearables, electronics
        public volatile String status;
        public volatile String imageUrl;

        public Product(int id, String name, String description, int totalQuantity, int availableQuantity,
                       double price, double originalPrice, String category, String status, String imageUrl) {
            this.id = id;
            this.name = name;
            this.description = description != null ? description : "";
            this.totalQuantity = totalQuantity;
            this.availableQuantity = availableQuantity;
            this.price = price;
            this.originalPrice = originalPrice > 0 ? originalPrice : Math.round(price * 1.8);
            this.category = (category != null && !category.isEmpty()) ? category.toLowerCase() : findCategory(name);
            this.status = status;
            this.imageUrl = (imageUrl != null && !imageUrl.isEmpty()) ? imageUrl : findImage(this.category, name);
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

    // one booking of one product. status: PENDING_PAYMENT, CONFIRMED, PAYMENT_FAILED, EXPIRED
    public static class PurchaseRecord {
        public int purchaseId;
        public int userId;
        public int productId;
        public String productName;
        public String category;
        public double price;
        public volatile String status;
        public String purchaseTime;
        public long latencyMs;
        public volatile String pnr = "-";
        public volatile long expiresAt = 0;   // time when the hold ends (only for PENDING_PAYMENT)

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

    // answer of hold / confirm / cancel
    public static class BuyResult {
        public boolean success = false;
        public String reason = "";    // SOLD_OUT, DUPLICATE_CLAIM, NOT_FOUND, DEADLOCK_VICTIM ...
        public String message = "";
        public List<PurchaseRecord> records = new ArrayList<>();
        public String productName = "";
        public double price = 0;
        public int remaining = 0;
        public long lsn = 0;
    }

    public static class RecoveryReport {
        public String source = "none";   // WAL or MySQL
        public long snapshotLsn = 0;
        public int redone = 0;
        public int undone = 0;
        public long millis = 0;
        public String time = "-";
    }

    public static class CrashReport {
        public String before = "";
        public String after = "";
        public boolean same = false;
        public RecoveryReport recovery;
    }

    // ---------- the data ----------
    private static final BPlusTree<Product> products = new BPlusTree<>(4);
    private static final Map<Integer, PurchaseRecord> orders = new ConcurrentHashMap<>();
    private static final Set<String> boughtKeys = ConcurrentHashMap.newKeySet();   // "userId_productId"
    private static final AtomicInteger nextProductId = new AtomicInteger(1);
    private static final AtomicInteger nextPurchaseId = new AtomicInteger(1001);
    private static volatile RecoveryReport lastRecovery = new RecoveryReport();
    private static volatile int expiredCount = 0;

    // normal operations take the read side (many together), a checkpoint takes the write side (alone)
    private static final ReentrantReadWriteLock checkpointLock = new ReentrantReadWriteLock();

    static {
        addStartingData();
        Thread reaper = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    expireOldHolds();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    System.out.println("[HoldReaper] " + e);
                }
            }
        }, "HoldReaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(millis));
    }

    private static int addStartingProduct(String name, String description, int quantity,
                                          double price, double originalPrice, String category, String image) {
        int id = nextProductId.getAndIncrement();
        products.put(id, new Product(id, name, description, quantity, quantity, price, originalPrice, category, "ACTIVE", image));
        return id;
    }

    private static void addStartingOrder(String pnr, int userId, int productId, long ago, int latency) {
        Product p = products.get(productId);
        PurchaseRecord order = new PurchaseRecord(nextPurchaseId.getAndIncrement(), userId, productId, p.name, p.category,
                p.price, "CONFIRMED", formatTime(System.currentTimeMillis() - ago), latency);
        order.pnr = pnr;
        orders.put(order.purchaseId, order);
        boughtKeys.add(userId + "_" + productId);
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
        addStartingOrder("PNR8K2M4Q", 108, flightId, 120000, 12);
        addStartingOrder("PNR5T7W9X", 142, titanId, 60000, 8);
        addStartingOrder("PNR3H6J2L", 179, trainId, 30000, 5);
    }

    // back to the starting state (used before recovery)
    private static void resetMemory() {
        products.clear();
        orders.clear();
        boughtKeys.clear();
        nextProductId.set(1);
        nextPurchaseId.set(1001);
        addStartingData();
    }

    // ---------- MySQL (optional) ----------
    public static boolean checkMySQLAvailability() {
        if (mysqlAvailable != null) return mysqlAvailable;
        synchronized (checkLock) {
            if (mysqlAvailable != null) return mysqlAvailable;
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                DriverManager.setLoginTimeout(2);
                try (Connection con = DriverManager.getConnection(URL, USER, PASSWORD)) {
                    createTables(con);
                    mysqlAvailable = true;
                    System.out.println("[Database] Successfully connected to MySQL on localhost:3306!");
                    return true;
                }
            } catch (Throwable e) {
                mysqlAvailable = false;
                System.out.println("[Database] MySQL not reachable (" + e.getMessage() + ").");
                System.out.println("[Database] Using the in-memory engine (B+ Tree + 2PL + WAL recovery).");
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
                    "status VARCHAR(20) NOT NULL, " +
                    "original_price DECIMAL(10,2), " +
                    "category VARCHAR(30), " +
                    "image_url VARCHAR(30))");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS purchase (" +
                    "purchase_id INT PRIMARY KEY AUTO_INCREMENT, " +
                    "user_id INT NOT NULL, " +
                    "product_id INT NOT NULL, " +
                    "status VARCHAR(20) NOT NULL, " +
                    "purchase_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "pnr VARCHAR(20), " +
                    "CONSTRAINT fk_purchase_product FOREIGN KEY (product_id) REFERENCES product(product_id), " +
                    "CONSTRAINT unique_user_product UNIQUE (user_id, product_id))");
        } catch (SQLException e) {
            System.out.println("[Database] Schema check notice: " + e.getMessage());
        }
        // tables made by the old database.sql do not have the new columns, add them (error = column is already there)
        String[] newColumns = {
            "ALTER TABLE product ADD COLUMN original_price DECIMAL(10,2)",
            "ALTER TABLE product ADD COLUMN category VARCHAR(30)",
            "ALTER TABLE product ADD COLUMN image_url VARCHAR(30)",
            "ALTER TABLE purchase ADD COLUMN pnr VARCHAR(20)"
        };
        for (String sql : newColumns) {
            try (Statement stmt = con.createStatement()) {
                stmt.executeUpdate(sql);
            } catch (SQLException ignored) {}
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
        return isMySQLActive() ? "MySQL 8.0 + In-Memory 2PL Engine" : "In-Memory Engine (B+ Tree, 2PL, WAL)";
    }

    private static void mysqlNotice(SQLException e) {
        System.out.println("[Database] MySQL notice: " + e.getMessage());
    }

    // stock as MySQL knows it = total - confirmed orders (a hold is not saved in MySQL)
    private static int committedStock(Product p) {
        int held = 0;
        for (PurchaseRecord order : orders.values()) {
            if (order.productId == p.id && "PENDING_PAYMENT".equals(order.status)) held++;
        }
        return p.availableQuantity + held;
    }

    // insert or update the product row
    private static void saveProduct(Product p) {
        if (!isMySQLActive()) return;
        int stock = committedStock(p);
        String sql = "INSERT INTO product (product_id, product_name, description, total_quantity, available_quantity, " +
                "price, status, original_price, category, image_url) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE product_name = VALUES(product_name), description = VALUES(description), " +
                "total_quantity = VALUES(total_quantity), available_quantity = VALUES(available_quantity), " +
                "price = VALUES(price), status = VALUES(status), original_price = VALUES(original_price), " +
                "category = VALUES(category), image_url = VALUES(image_url)";
        try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, p.id);
            ps.setString(2, p.name);
            ps.setString(3, p.description);
            ps.setInt(4, p.totalQuantity);
            ps.setInt(5, stock);
            ps.setDouble(6, p.price);
            ps.setString(7, stock > 0 ? "ACTIVE" : "SOLD OUT");
            ps.setDouble(8, p.originalPrice);
            ps.setString(9, p.category);
            ps.setString(10, p.imageUrl);
            ps.executeUpdate();
        } catch (SQLException e) {
            mysqlNotice(e);
        }
    }

    // ACID transaction: lock the product row (SELECT FOR UPDATE), reduce stock, insert the order, commit
    private static void saveConfirmedOrder(PurchaseRecord order) {
        if (!isMySQLActive()) return;
        try (Connection con = getConnection()) {
            con.setAutoCommit(false);
            try {
                int stock;
                try (PreparedStatement ps = con.prepareStatement("SELECT available_quantity FROM product WHERE product_id = ? FOR UPDATE")) {
                    ps.setInt(1, order.productId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return;
                        }
                        stock = rs.getInt(1);
                    }
                }
                if (stock <= 0) {
                    con.rollback();
                    return;
                }
                try (PreparedStatement ps = con.prepareStatement("UPDATE product SET available_quantity = ?, status = ? WHERE product_id = ?")) {
                    ps.setInt(1, stock - 1);
                    ps.setString(2, stock - 1 > 0 ? "ACTIVE" : "SOLD OUT");
                    ps.setInt(3, order.productId);
                    ps.executeUpdate();
                }
                String insert = "INSERT INTO purchase (purchase_id, user_id, product_id, status, purchase_time, pnr) VALUES (?,?,?,?,?,?)";
                try (PreparedStatement ps = con.prepareStatement(insert)) {
                    ps.setInt(1, order.purchaseId);
                    ps.setInt(2, order.userId);
                    ps.setInt(3, order.productId);
                    ps.setString(4, "CONFIRMED");
                    ps.setTimestamp(5, toTimestamp(order.purchaseTime));
                    ps.setString(6, order.pnr);
                    ps.executeUpdate();
                }
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            mysqlNotice(e);
        }
    }

    private static Timestamp toTimestamp(String text) {
        try {
            return Timestamp.valueOf(text.length() > 19 ? text.substring(0, 19) : text);
        } catch (Exception e) {
            return new Timestamp(System.currentTimeMillis());
        }
    }

    private static void deleteProductRows(int productId) {
        if (!isMySQLActive()) return;
        try (Connection con = getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement a = con.prepareStatement("DELETE FROM purchase WHERE product_id = ?");
                 PreparedStatement b = con.prepareStatement("DELETE FROM product WHERE product_id = ?")) {
                a.setInt(1, productId);
                a.executeUpdate();
                b.setInt(1, productId);
                b.executeUpdate();
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            mysqlNotice(e);
        }
    }

    private static void deletePurchaseRows(int productId) {
        if (!isMySQLActive()) return;
        String sql = productId < 0 ? "DELETE FROM purchase" : "DELETE FROM purchase WHERE product_id = ?";
        try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            if (productId >= 0) ps.setInt(1, productId);
            ps.executeUpdate();
        } catch (SQLException e) {
            mysqlNotice(e);
        }
    }

    // if MySQL already has data it is the truth, if it is empty we put our starting data in it
    private static void syncWithMySQL() {
        try (Connection con = getConnection()) {
            int rows = 0;
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM product")) {
                if (rs.next()) rows = rs.getInt(1);
            }
            if (rows == 0) {
                for (Product p : products.values()) saveProduct(p);
                for (PurchaseRecord order : orders.values()) saveOrderRowOnly(order);
                return;
            }
            products.clear();
            orders.clear();
            boughtKeys.clear();
            int maxProduct = 0;
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM product ORDER BY product_id")) {
                while (rs.next()) {
                    int id = rs.getInt("product_id");
                    products.put(id, new Product(id, rs.getString("product_name"), rs.getString("description"),
                            rs.getInt("total_quantity"), rs.getInt("available_quantity"), rs.getDouble("price"),
                            rs.getDouble("original_price"), rs.getString("category"), rs.getString("status"), rs.getString("image_url")));
                    maxProduct = Math.max(maxProduct, id);
                }
            }
            int maxOrder = 1000;
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM purchase ORDER BY purchase_id")) {
                while (rs.next()) {
                    Product p = products.get(rs.getInt("product_id"));
                    if (p == null) continue;
                    PurchaseRecord order = new PurchaseRecord(rs.getInt("purchase_id"), rs.getInt("user_id"), p.id, p.name,
                            p.category, p.price, "CONFIRMED", String.valueOf(rs.getTimestamp("purchase_time")).substring(0, 19), 0);
                    String pnr = rs.getString("pnr");
                    order.pnr = pnr != null ? pnr : "-";
                    orders.put(order.purchaseId, order);
                    boughtKeys.add(order.userId + "_" + order.productId);
                    maxOrder = Math.max(maxOrder, order.purchaseId);
                }
            }
            nextProductId.set(maxProduct + 1);
            nextPurchaseId.set(maxOrder + 1);
        } catch (Exception e) {
            System.out.println("[Database] MySQL load notice: " + e);
        }
    }

    // only the purchase row (used when we copy the starting orders into an empty MySQL)
    private static void saveOrderRowOnly(PurchaseRecord order) {
        String insert = "INSERT INTO purchase (purchase_id, user_id, product_id, status, purchase_time, pnr) VALUES (?,?,?,?,?,?)";
        try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(insert)) {
            ps.setInt(1, order.purchaseId);
            ps.setInt(2, order.userId);
            ps.setInt(3, order.productId);
            ps.setString(4, "CONFIRMED");
            ps.setTimestamp(5, toTimestamp(order.purchaseTime));
            ps.setString(6, order.pnr);
            ps.executeUpdate();
        } catch (SQLException e) {
            mysqlNotice(e);
        }
    }

    // ---------- reading (no locks needed for the list, the fields are volatile) ----------
    public static List<Product> getAllProducts() {
        return products.values();
    }

    public static Product getProduct(int productId) {
        return products.get(productId);
    }

    // reading stock for a report takes a shared (S) lock, so it waits only for writers
    public static int getAvailableQuantity(int productId) {
        String txn = Thread.currentThread().getName() + "-read";
        try {
            LockManager.lock(txn, "Product-" + productId, false, LOCK_WAIT_MS, true);
        } catch (LockManager.LockAbort ignored) {
            // could not get the lock, read without it
        }
        try {
            Product p = products.get(productId);
            return p != null ? p.availableQuantity : -1;
        } finally {
            LockManager.unlockAll(txn);
        }
    }

    public static List<PurchaseRecord> getAllPurchases() {
        List<PurchaseRecord> list = new ArrayList<>(orders.values());
        list.sort((a, b) -> b.purchaseId - a.purchaseId);
        return list;
    }

    public static PurchaseRecord getPurchase(int purchaseId) {
        return orders.get(purchaseId);
    }

    public static int getPendingCount() {
        int count = 0;
        for (PurchaseRecord order : orders.values()) {
            if ("PENDING_PAYMENT".equals(order.status)) count++;
        }
        return count;
    }

    public static int getExpiredCount() { return expiredCount; }
    public static int getIndexHeight() { return products.height(); }
    public static int getIndexSize() { return products.size(); }
    public static int getIndexOrder() { return products.getOrder(); }
    public static RecoveryReport getLastRecovery() { return lastRecovery; }

    // ---------- buying step 1: HOLD ----------
    // One or more products in one transaction (all or nothing).
    // Locks are asked in sorted product id order, so two carts can never wait for each other in a circle.
    public static BuyResult hold(int userId, int[] productIds) {
        BuyResult result = new BuyResult();
        TreeSet<Integer> sorted = new TreeSet<>();
        for (int id : productIds) sorted.add(id);
        if (sorted.isEmpty()) return fail(result, "NOT_FOUND", "No product selected");
        String txn = Thread.currentThread().getName();
        checkpointLock.readLock().lock();
        try {
            // growing phase: X lock on every product, in sorted order
            for (int id : sorted) LockManager.lock(txn, "Product-" + id, true, LOCK_WAIT_MS, true);

            // check everything first, so nothing is changed if one item fails
            for (int id : sorted) {
                Product p = products.get(id);
                if (p == null) return fail(result, "NOT_FOUND", "Product not found");
                result.productName = p.name;
                result.price = p.price;
                if (boughtKeys.contains(userId + "_" + id)) return fail(result, "DUPLICATE_CLAIM", "Duplicate reservation rejected");
                if (p.availableQuantity <= 0) {
                    p.status = "SOLD OUT";
                    return fail(result, "SOLD_OUT", "Sold Out");
                }
            }

            // write the log first, then change the data
            long now = System.currentTimeMillis();
            long expires = now + HOLD_SECONDS * 1000L;
            List<String> names = new ArrayList<>();
            double total = 0;
            int lowest = Integer.MAX_VALUE;
            for (int id : sorted) {
                Product p = products.get(id);
                int purchaseId = nextPurchaseId.getAndIncrement();
                WAL.LogRecord log = WAL.append("HOLD-" + purchaseId, "STOCK_RESERVE", userId, id, p.name,
                        p.availableQuantity, p.availableQuantity - 1, p.price, "RESERVED",
                        WAL.pack("pid", purchaseId, "time", now, "expires", expires));
                p.availableQuantity--;
                if (p.availableQuantity == 0) p.status = "SOLD OUT";
                PurchaseRecord order = new PurchaseRecord(purchaseId, userId, id, p.name, p.category, p.price,
                        "PENDING_PAYMENT", formatTime(now), 0);
                order.expiresAt = expires;
                orders.put(purchaseId, order);
                boughtKeys.add(userId + "_" + id);
                result.records.add(order);
                result.lsn = log.lsn;
                names.add(p.name);
                total += p.price;
                lowest = Math.min(lowest, p.availableQuantity);
            }
            result.success = true;
            result.productName = String.join(", ", names);
            result.price = total;
            result.remaining = lowest;
            result.message = "Seat held for " + (HOLD_SECONDS / 60) + " minutes, please pay";
            return result;
        } catch (LockManager.LockAbort e) {
            return fail(result, e.reason, e.reason.equals("DEADLOCK_VICTIM") ? "Deadlock victim, please try again" : "Lock wait timeout");
        } finally {
            LockManager.unlockAll(txn);   // shrinking phase: all locks are released together
            checkpointLock.readLock().unlock();
        }
    }

    private static BuyResult fail(BuyResult result, String reason, String message) {
        result.success = false;
        result.reason = reason;
        result.message = message;
        return result;
    }

    // ---------- buying step 2: payment OK -> CONFIRMED with a PNR ----------
    public static BuyResult confirm(int purchaseId) {
        BuyResult result = new BuyResult();
        PurchaseRecord order = orders.get(purchaseId);
        if (order == null) return fail(result, "NOT_FOUND", "Order not found (maybe it expired)");
        String txn = Thread.currentThread().getName() + "-pay";
        checkpointLock.readLock().lock();
        try {
            LockManager.lock(txn, "Product-" + order.productId, true, LOCK_WAIT_MS, true);
            if (!"PENDING_PAYMENT".equals(order.status)) return fail(result, "NOT_PENDING", "Order is already " + order.status);
            if (System.currentTimeMillis() > order.expiresAt) {
                release(order, "EXPIRED", "HOLD_EXPIRED");
                expiredCount++;
                return fail(result, "EXPIRED", "Your hold has expired, please book again");
            }
            Product p = products.get(order.productId);
            String pnr = makePnr();
            WAL.LogRecord log = WAL.append("PAY-" + purchaseId, "ORDER_COMMIT", order.userId, order.productId, order.productName,
                    p != null ? p.availableQuantity + 1 : 0, p != null ? p.availableQuantity : 0, order.price, "COMMITTED",
                    WAL.pack("pid", purchaseId, "pnr", pnr));
            order.pnr = pnr;
            order.status = "CONFIRMED";
            order.expiresAt = 0;
            result.success = true;
            result.message = "Payment successful";
            result.records.add(order);
            result.lsn = log.lsn;
        } catch (LockManager.LockAbort e) {
            return fail(result, e.reason, "Could not confirm: " + e.reason);
        } finally {
            LockManager.unlockAll(txn);
            checkpointLock.readLock().unlock();
        }
        WAL.flush(result.lsn);          // the order is confirmed only after its log record is on the disk
        saveConfirmedOrder(order);      // copy to MySQL (if it is running)
        return result;
    }

    // ---------- payment failed or customer cancelled -> give the seat back ----------
    public static BuyResult cancel(int purchaseId, String reason) {
        BuyResult result = new BuyResult();
        PurchaseRecord order = orders.get(purchaseId);
        if (order == null) return fail(result, "NOT_FOUND", "Order not found (maybe it expired)");
        String txn = Thread.currentThread().getName() + "-pay";
        checkpointLock.readLock().lock();
        try {
            LockManager.lock(txn, "Product-" + order.productId, true, LOCK_WAIT_MS, true);
            if (!"PENDING_PAYMENT".equals(order.status)) return fail(result, "NOT_PENDING", "Order is already " + order.status);
            release(order, "PAYMENT_FAILED", reason);
            result.success = true;
            result.message = "Seat released";
            result.records.add(order);
            return result;
        } catch (LockManager.LockAbort e) {
            return fail(result, e.reason, "Could not cancel: " + e.reason);
        } finally {
            LockManager.unlockAll(txn);
            checkpointLock.readLock().unlock();
        }
    }

    // log the release, then give the stock back (the caller holds the product lock)
    private static void release(PurchaseRecord order, String newStatus, String reason) {
        Product p = products.get(order.productId);
        int before = p != null ? p.availableQuantity : 0;
        WAL.append("REL-" + order.purchaseId, "TXN_ABORT", order.userId, order.productId, order.productName,
                before, before + 1, order.price, reason, WAL.pack("pid", order.purchaseId));
        undoHold(order, newStatus);
    }

    // memory change only (also used when the log is replayed)
    private static void undoHold(PurchaseRecord order, String newStatus) {
        Product p = products.get(order.productId);
        if (p != null) {
            p.availableQuantity++;
            p.status = "ACTIVE";
        }
        order.status = newStatus;
        order.expiresAt = 0;
        boughtKeys.remove(order.userId + "_" + order.productId);
    }

    // called by the HoldReaper thread every second: holds older than 5 minutes give the seat back
    public static void expireOldHolds() {
        long now = System.currentTimeMillis();
        for (PurchaseRecord order : orders.values()) {
            if (!"PENDING_PAYMENT".equals(order.status) || order.expiresAt > now) continue;
            checkpointLock.readLock().lock();
            try {
                LockManager.lock("HoldReaper", "Product-" + order.productId, true, LOCK_WAIT_MS, true);
                if ("PENDING_PAYMENT".equals(order.status) && order.expiresAt <= System.currentTimeMillis()) {
                    release(order, "EXPIRED", "HOLD_EXPIRED");
                    expiredCount++;
                }
            } catch (LockManager.LockAbort ignored) {
                // try again in the next second
            } finally {
                LockManager.unlockAll("HoldReaper");
                checkpointLock.readLock().unlock();
            }
        }
    }

    private static String makePnr() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder pnr = new StringBuilder("PNR");
        for (int i = 0; i < 7; i++) pnr.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
        return pnr.toString();
    }

    // ---------- admin: products (each change is logged first) ----------
    public static int addProduct(String name, String description, int quantity, double price) {
        return addProduct(name, description, quantity, price, "general", Math.round(price * 1.8), "tech");
    }

    public static int addProduct(String name, String description, int quantity, double price,
                                 String category, double originalPrice, String imageUrl) {
        int id = nextProductId.getAndIncrement();
        Product p = new Product(id, name, description, quantity, quantity, price, originalPrice, category,
                quantity > 0 ? "ACTIVE" : "SOLD OUT", imageUrl);
        checkpointLock.readLock().lock();
        long lsn;
        try {
            lsn = WAL.append("DROP-INIT-" + id, "PRODUCT_CREATE", 0, id, name, 0, quantity, price, "SUCCESS",
                    WAL.pack("desc", p.description, "category", p.category, "orig", p.originalPrice, "image", p.imageUrl)).lsn;
            products.put(id, p);
        } finally {
            checkpointLock.readLock().unlock();
        }
        WAL.flush(lsn);
        saveProduct(p);
        return id;
    }

    // change name, price, description, category, quantity. Tickets that are already sold stay sold.
    public static boolean updateProduct(int productId, String name, String description, double price,
                                        double originalPrice, String category, int totalQuantity) {
        Product p = products.get(productId);
        if (p == null) return false;
        String txn = Thread.currentThread().getName() + "-admin";
        checkpointLock.readLock().lock();
        long lsn;
        try {
            LockManager.lock(txn, "Product-" + productId, true, LOCK_WAIT_MS, true);
            String cat = (category != null && !category.isEmpty()) ? category.toLowerCase() : p.category;
            double orig = originalPrice > 0 ? originalPrice : p.originalPrice;
            lsn = WAL.append("EDIT-" + productId, "PRODUCT_UPDATE", 0, productId, name, 0, totalQuantity, price, "SUCCESS",
                    WAL.pack("desc", description, "category", cat, "orig", orig)).lsn;
            applyUpdate(p, name, description, price, orig, cat, totalQuantity);
        } catch (LockManager.LockAbort e) {
            return false;
        } finally {
            LockManager.unlockAll(txn);
            checkpointLock.readLock().unlock();
        }
        WAL.flush(lsn);
        saveProduct(p);
        return true;
    }

    private static void applyUpdate(Product p, String name, String description, double price,
                                    double originalPrice, String category, int totalQuantity) {
        int sold = p.totalQuantity - p.availableQuantity;
        int newAvailable = Math.max(0, totalQuantity - sold);
        p.name = name;
        p.description = description != null ? description : "";
        p.price = price;
        if (originalPrice > 0) p.originalPrice = originalPrice;
        if (category != null && !category.isEmpty()) p.category = category.toLowerCase();
        p.imageUrl = Product.findImage(p.category, name);   // icon follows the new name
        p.totalQuantity = totalQuantity;
        p.availableQuantity = newAvailable;
        p.status = newAvailable > 0 ? "ACTIVE" : "SOLD OUT";
    }

    public static boolean deleteProduct(int productId) {
        Product p = products.get(productId);
        if (p == null) return false;
        String txn = Thread.currentThread().getName() + "-admin";
        checkpointLock.readLock().lock();
        long lsn;
        try {
            LockManager.lock(txn, "Product-" + productId, true, LOCK_WAIT_MS, true);
            lsn = WAL.append("DROP-DEL-" + productId, "PRODUCT_DELETE", 0, productId, p.name, 0, 0, 0, "SUCCESS", "").lsn;
            removeProductData(productId);
        } catch (LockManager.LockAbort e) {
            return false;
        } finally {
            LockManager.unlockAll(txn);
            checkpointLock.readLock().unlock();
        }
        WAL.flush(lsn);
        deleteProductRows(productId);
        return true;
    }

    private static void removeProductData(int productId) {
        products.remove(productId);
        removeOrdersOf(productId);
    }

    private static void removeOrdersOf(int productId) {
        orders.values().removeIf(order -> order.productId == productId);
        boughtKeys.removeIf(key -> key.endsWith("_" + productId));
    }

    // restock: stock is full again and the orders of this product are cleared
    public static boolean resetProduct(int productId, int quantity) {
        Product p = products.get(productId);
        if (p == null) return false;
        String txn = Thread.currentThread().getName() + "-admin";
        checkpointLock.readLock().lock();
        long lsn;
        try {
            LockManager.lock(txn, "Product-" + productId, true, LOCK_WAIT_MS, true);
            lsn = WAL.append("RESTOCK-" + productId, "STOCK_RESET", 0, productId, p.name, p.availableQuantity, quantity, 0, "SUCCESS", "").lsn;
            applyReset(p, quantity);
        } catch (LockManager.LockAbort e) {
            return false;
        } finally {
            LockManager.unlockAll(txn);
            checkpointLock.readLock().unlock();
        }
        WAL.flush(lsn);
        deletePurchaseRows(productId);
        saveProduct(p);
        return true;
    }

    private static void applyReset(Product p, int quantity) {
        p.totalQuantity = quantity;
        p.availableQuantity = quantity;
        p.status = quantity > 0 ? "ACTIVE" : "SOLD OUT";
        removeOrdersOf(p.id);
    }

    // clear the orders list. Holds that are not paid yet give their seats back first.
    public static void clearAllPurchases() {
        String txn = Thread.currentThread().getName() + "-admin";
        checkpointLock.readLock().lock();
        long lsn;
        try {
            // lock all products in sorted order (the products come from the B+ Tree already sorted)
            for (Product p : products.values()) LockManager.lock(txn, "Product-" + p.id, true, LOCK_WAIT_MS, true);
            lsn = WAL.append("ADMIN-CLEAR", "PURCHASE_PURGE", 0, 0, "ALL_RECORDS", 0, 0, 0, "SUCCESS", "").lsn;
            purgeMemory();
        } catch (LockManager.LockAbort e) {
            return;
        } finally {
            LockManager.unlockAll(txn);
            checkpointLock.readLock().unlock();
        }
        WAL.flush(lsn);
        deletePurchaseRows(-1);
    }

    private static void purgeMemory() {
        for (PurchaseRecord order : orders.values()) {
            Product p = products.get(order.productId);
            if ("PENDING_PAYMENT".equals(order.status) && p != null) {
                p.availableQuantity++;
                p.status = "ACTIVE";
            }
        }
        orders.clear();
        boughtKeys.clear();
    }

    // ---------- checkpoint: save a snapshot, then crash recovery starts from it ----------
    public static WAL.LogRecord checkpoint(String note) {
        checkpointLock.writeLock().lock();   // nobody changes data while we take the snapshot
        try {
            long lsn = WAL.getCurrentLsn();
            WAL.flush(lsn);                  // the log is on the disk before the snapshot
            saveSnapshot(lsn);
            return WAL.logCheckpoint(note);
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    private static String enc(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private static String dec(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private static void saveSnapshot(long lsn) {
        StringBuilder text = new StringBuilder();
        text.append("LSN=").append(lsn).append('\n');
        text.append("NEXTP=").append(nextProductId.get()).append('\n');
        text.append("NEXTO=").append(nextPurchaseId.get()).append('\n');
        for (Product p : products.values()) {
            text.append(String.join("|", "P", String.valueOf(p.id), String.valueOf(p.totalQuantity),
                    String.valueOf(p.availableQuantity), String.valueOf(p.price), String.valueOf(p.originalPrice),
                    enc(p.category), enc(p.status), enc(p.imageUrl), enc(p.name), enc(p.description))).append('\n');
        }
        for (PurchaseRecord o : orders.values()) {
            text.append(String.join("|", "O", String.valueOf(o.purchaseId), String.valueOf(o.userId),
                    String.valueOf(o.productId), enc(o.productName), enc(o.category), String.valueOf(o.price),
                    o.status, enc(o.purchaseTime), String.valueOf(o.latencyMs), enc(o.pnr), String.valueOf(o.expiresAt))).append('\n');
        }
        try {
            Files.createDirectories(Paths.get("data"));
            Path temp = Paths.get(SNAPSHOT_FILE + ".tmp");
            Files.writeString(temp, text.toString(), StandardCharsets.UTF_8);
            Files.move(temp, Paths.get(SNAPSHOT_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.println("[Database] Could not save snapshot: " + e.getMessage());
        }
    }

    // returns the LSN of the snapshot, or 0 if there is no snapshot
    private static long loadSnapshot() {
        Path file = Paths.get(SNAPSHOT_FILE);
        if (!Files.exists(file)) return 0;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            long lsn = 0;
            int nextP = 1, nextO = 1001;
            products.clear();
            orders.clear();
            boughtKeys.clear();
            for (String line : lines) {
                if (line.startsWith("LSN=")) lsn = Long.parseLong(line.substring(4));
                else if (line.startsWith("NEXTP=")) nextP = Integer.parseInt(line.substring(6));
                else if (line.startsWith("NEXTO=")) nextO = Integer.parseInt(line.substring(6));
                else if (line.startsWith("P|")) {
                    String[] f = line.split("\\|", -1);
                    int id = Integer.parseInt(f[1]);
                    products.put(id, new Product(id, dec(f[9]), dec(f[10]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                            Double.parseDouble(f[4]), Double.parseDouble(f[5]), dec(f[6]), dec(f[7]), dec(f[8])));
                } else if (line.startsWith("O|")) {
                    String[] f = line.split("\\|", -1);
                    PurchaseRecord o = new PurchaseRecord(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                            dec(f[4]), dec(f[5]), Double.parseDouble(f[6]), f[7], dec(f[8]), Long.parseLong(f[9]));
                    o.pnr = dec(f[10]);
                    o.expiresAt = Long.parseLong(f[11]);
                    orders.put(o.purchaseId, o);
                    if ("PENDING_PAYMENT".equals(o.status) || "CONFIRMED".equals(o.status)) boughtKeys.add(o.userId + "_" + o.productId);
                }
            }
            nextProductId.set(nextP);
            nextPurchaseId.set(nextO);
            return lsn;
        } catch (Exception e) {
            System.out.println("[Database] Snapshot is not readable (" + e + "), using the whole log instead.");
            resetMemory();
            return 0;
        }
    }

    // ---------- crash recovery ----------
    // 1. start from the snapshot (or the starting data)
    // 2. REDO: replay every log record after the snapshot, in LSN order
    // 3. UNDO: holds that were not paid when the crash happened give their seats back
    public static synchronized RecoveryReport recover() {
        long start = System.currentTimeMillis();
        RecoveryReport report = new RecoveryReport();
        resetMemory();
        if (isMySQLActive()) {
            syncWithMySQL();
            report.source = "MySQL";
        } else {
            report.source = "WAL";
            report.snapshotLsn = loadSnapshot();
            for (WAL.LogRecord record : WAL.readAll()) {
                if (record.lsn <= report.snapshotLsn) continue;
                if (redo(record)) report.redone++;
            }
            for (PurchaseRecord order : new ArrayList<>(orders.values())) {
                if ("PENDING_PAYMENT".equals(order.status)) {
                    release(order, "EXPIRED", "CRASH_UNDO");
                    report.undone++;
                }
            }
            WAL.flush(WAL.getCurrentLsn());
        }
        report.millis = System.currentTimeMillis() - start;
        report.time = formatTime(System.currentTimeMillis());
        lastRecovery = report;
        System.out.println("[Recovery] source=" + report.source + ", snapshot LSN=" + report.snapshotLsn +
                ", redone=" + report.redone + ", undone=" + report.undone + ", " + report.millis + " ms");
        return report;
    }

    private static double number(String text, double fallback) {
        try {
            return text == null ? fallback : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // apply one log record to the memory, returns true if it changed something
    private static boolean redo(WAL.LogRecord rec) {
        Map<String, String> d = WAL.unpack(rec.details);
        Product p = products.get(rec.productId);
        switch (rec.opType) {
            case "PRODUCT_CREATE":
                Product created = new Product(rec.productId, rec.productName, d.get("desc"), rec.newQty, rec.newQty, rec.price,
                        number(d.get("orig"), 0), d.get("category"), rec.newQty > 0 ? "ACTIVE" : "SOLD OUT", d.get("image"));
                products.put(rec.productId, created);
                nextProductId.set(Math.max(nextProductId.get(), rec.productId + 1));
                return true;
            case "PRODUCT_UPDATE":
                if (p == null) return false;
                applyUpdate(p, rec.productName, d.get("desc"), rec.price, number(d.get("orig"), 0), d.get("category"), rec.newQty);
                return true;
            case "PRODUCT_DELETE":
                removeProductData(rec.productId);
                return true;
            case "STOCK_RESET":
                if (p == null) return false;
                applyReset(p, rec.newQty);
                return true;
            case "PURCHASE_PURGE":
                purgeMemory();
                return true;
            case "STOCK_RESERVE": {
                int pid = (int) number(d.get("pid"), 0);
                if (p == null || pid == 0) return false;
                p.availableQuantity--;
                if (p.availableQuantity <= 0) p.status = "SOLD OUT";
                PurchaseRecord order = new PurchaseRecord(pid, rec.userId, rec.productId, rec.productName, p.category,
                        rec.price, "PENDING_PAYMENT", formatTime((long) number(d.get("time"), System.currentTimeMillis())), 0);
                order.expiresAt = (long) number(d.get("expires"), 0);
                orders.put(pid, order);
                boughtKeys.add(rec.userId + "_" + rec.productId);
                nextPurchaseId.set(Math.max(nextPurchaseId.get(), pid + 1));
                return true;
            }
            case "ORDER_COMMIT": {
                int pid = (int) number(d.get("pid"), 0);
                PurchaseRecord order = orders.get(pid);
                if (order != null && "PENDING_PAYMENT".equals(order.status)) {
                    order.status = "CONFIRMED";
                    order.pnr = d.getOrDefault("pnr", "-");
                    order.expiresAt = 0;
                    return true;
                }
                if (pid == 0 && p != null && !boughtKeys.contains(rec.userId + "_" + rec.productId)) {
                    // old log record (before holds existed): it is a finished purchase
                    int newId = nextPurchaseId.getAndIncrement();
                    p.availableQuantity = Math.max(0, p.availableQuantity - 1);
                    if (p.availableQuantity == 0) p.status = "SOLD OUT";
                    PurchaseRecord old = new PurchaseRecord(newId, rec.userId, rec.productId, p.name, p.category, rec.price,
                            "CONFIRMED", rec.timestamp.substring(0, Math.min(19, rec.timestamp.length())), 0);
                    orders.put(newId, old);
                    boughtKeys.add(rec.userId + "_" + rec.productId);
                    return true;
                }
                return false;
            }
            case "TXN_ABORT": {
                PurchaseRecord order = orders.get((int) number(d.get("pid"), 0));
                if (order == null || !"PENDING_PAYMENT".equals(order.status)) return false;
                boolean paymentProblem = "PAYMENT_FAILED".equals(rec.status) || "CANCELLED".equals(rec.status);
                undoHold(order, paymentProblem ? "PAYMENT_FAILED" : "EXPIRED");
                return true;
            }
            default:
                return false;   // TXN_BEGIN, CHECKPOINT ...
        }
    }

    // what the committed state looks like (stock without holds), used to compare before and after a crash
    private static String fingerprint() {
        StringBuilder text = new StringBuilder();
        for (Product p : products.values()) {
            text.append(p.id).append(':').append(p.name).append(':').append(p.price).append(':')
                .append(committedStock(p)).append('/').append(p.totalQuantity).append("; ");
        }
        int confirmed = 0;
        for (PurchaseRecord order : orders.values()) {
            if ("CONFIRMED".equals(order.status)) confirmed++;
        }
        return text.append("confirmed orders=").append(confirmed).toString();
    }

    // admin button: throw away the whole memory (like a power cut) and rebuild it from the disk
    public static CrashReport simulateCrash() {
        CrashReport report = new CrashReport();
        checkpointLock.writeLock().lock();   // stop everybody, like a real crash
        try {
            WAL.flush(WAL.getCurrentLsn());
            report.before = fingerprint();
            report.recovery = recover();
            report.after = fingerprint();
            report.same = report.before.equals(report.after);
        } finally {
            checkpointLock.writeLock().unlock();
        }
        return report;
    }
}
