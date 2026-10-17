import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebServer {

    private static final int DEFAULT_PORT = 8080;
    private static final long SERVER_START_TIME = System.currentTimeMillis();
    private static final String ADMIN_PIN = "admin123";

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        // 1. Initialize Write-Ahead Logging (WAL) and crash-recovery engine
        WAL.init();

        // 2. Initialize database engine (MySQL test or In-Memory fallback)
        Database.checkMySQLAvailability();

        // 3. Initialize WorkerPool concurrency engine
        WorkerPool.init(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

        HttpServer server = null;
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                break;
            } catch (java.net.BindException e) {
                System.out.println("[Notice] Port " + port + " is in use. Trying port " + (port + 1) + "...");
                port++;
            }
        }

        if (server == null) {
            System.err.println("[Error] Could not find an available port between " + DEFAULT_PORT + " and " + (port - 1));
            return;
        }

        server.setExecutor(Executors.newFixedThreadPool(20));

        // Public Customer API Endpoints
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/products", new ProductsHandler());
        server.createContext("/api/purchase", new PurchaseHandler());

        // Secure Admin API Endpoints
        server.createContext("/api/admin/login", new AdminLoginHandler());
        server.createContext("/api/admin/overview", new AdminOverviewHandler());
        server.createContext("/api/admin/products", new AdminProductsHandler());
        server.createContext("/api/admin/products/delete", new AdminDeleteProductHandler());
        server.createContext("/api/admin/reset", new ResetHandler());
        server.createContext("/api/admin/purchases", new PurchasesHandler());
        server.createContext("/api/admin/clear-purchases", new ClearPurchasesHandler());

        // WAL & WorkerPool Telemetry Endpoints
        server.createContext("/api/admin/wal", new AdminWalHandler());
        server.createContext("/api/admin/workerpool", new AdminWorkerPoolHandler());
        server.createContext("/api/admin/checkpoint", new AdminCheckpointHandler());

        // Static Files Handler
        server.createContext("/", new StaticFileHandler());

        server.start();

        System.out.println("=================================================");
        System.out.println("   FLASH SALE ENGINE - WEB SERVER RUNNING");
        System.out.println("=================================================");
        System.out.println(" Customer Store : http://localhost:" + port);
        System.out.println(" Admin Section  : http://localhost:" + port + "/#admin");
        System.out.println(" Admin PIN      : " + ADMIN_PIN);
        System.out.println(" Storage Engine : " + Database.getStorageEngineName());
        System.out.println(" WAL Status     : ACTIVE (Current LSN: " + WAL.getCurrentLsn() + ")");
        System.out.println(" WorkerPool     : " + WorkerPool.getWorkerCount() + " Workers Online");
        System.out.println(" Java Runtime   : OpenJDK " + System.getProperty("java.version"));
        System.out.println(" Press Ctrl+C to stop the server.");
        System.out.println("=================================================");
    }

    // ==========================================
    // HTTP Handlers
    // ==========================================

    private static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            long uptimeSec = (System.currentTimeMillis() - SERVER_START_TIME) / 1000;
            boolean mysql = Database.isMySQLActive();
            int totalProducts = Database.getAllProducts().size();
            int totalPurchases = Database.getAllPurchases().size();

            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"status\":\"ONLINE\",")
              .append("\"currency\":\"INR\",")
              .append("\"currencySymbol\":\"₹\",")
              .append("\"engine\":\"").append(escapeJson(Database.getStorageEngineName())).append("\",")
              .append("\"mysqlActive\":").append(mysql).append(",")
              .append("\"uptimeSeconds\":").append(uptimeSec).append(",")
              .append("\"totalProducts\":").append(totalProducts).append(",")
              .append("\"totalPurchases\":").append(totalPurchases).append(",")
              .append("\"walLsn\":").append(WAL.getCurrentLsn()).append(",")
              .append("\"workerCount\":").append(WorkerPool.getWorkerCount()).append(",")
              .append("\"javaVersion\":\"").append(escapeJson(System.getProperty("java.version"))).append("\"")
              .append("}");

            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    // ==========================================
    // Admin Authentication & Overview
    // ==========================================
    private static class AdminLoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = readRequestBody(exchange);
            String pin = getJsonString(body, "pin", "");

            if (ADMIN_PIN.equals(pin.trim())) {
                sendJsonResponse(exchange, 200, "{\"authenticated\":true,\"message\":\"Admin login verified\"}");
            } else {
                sendJsonResponse(exchange, 401, "{\"authenticated\":false,\"message\":\"Invalid Admin PIN\"}");
            }
        }
    }

    private static class AdminOverviewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            List<Database.Product> products = Database.getAllProducts();
            List<Database.PurchaseRecord> purchases = Database.getAllPurchases();

            double totalRevenue = 0;
            for (Database.PurchaseRecord p : purchases) {
                totalRevenue += p.price;
            }

            int totalStockRemaining = 0;
            int totalInitialStock = 0;
            for (Database.Product p : products) {
                totalStockRemaining += p.availableQuantity;
                totalInitialStock += p.totalQuantity;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"totalRevenue\":").append(String.format(Locale.US, "%.2f", totalRevenue)).append(",")
              .append("\"totalOrders\":").append(purchases.size()).append(",")
              .append("\"totalProducts\":").append(products.size()).append(",")
              .append("\"totalStockRemaining\":").append(totalStockRemaining).append(",")
              .append("\"totalInitialStock\":").append(totalInitialStock).append(",")
              .append("\"walLsn\":").append(WAL.getCurrentLsn()).append(",")
              .append("\"workersOnline\":").append(WorkerPool.getWorkerCount()).append(",")
              .append("\"queueSize\":").append(WorkerPool.getQueueSize()).append(",")
              .append("\"engine\":\"").append(escapeJson(Database.getStorageEngineName())).append("\"")
              .append("}");

            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    // ==========================================
    // Public Products Handler
    // ==========================================
    private static class ProductsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod().toUpperCase();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("GET".equals(method)) {
                List<Database.Product> products = Database.getAllProducts();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < products.size(); i++) {
                    Database.Product p = products.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(productToJson(p));
                }
                sb.append("]");
                sendJsonResponse(exchange, 200, sb.toString());
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    // ==========================================
    // Admin Products Handler (Add, Restock, Delete)
    // ==========================================
    private static class AdminProductsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod().toUpperCase();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(method)) {
                String body = readRequestBody(exchange);
                String name = getJsonString(body, "name", "Flash Drop Item");
                String desc = getJsonString(body, "description", "Exclusive flash sale product");
                int qty = getJsonInt(body, "quantity", 30);
                double price = getJsonDouble(body, "price", 1999.0);
                double originalPrice = getJsonDouble(body, "originalPrice", Math.round(price * 1.8));
                String category = getJsonString(body, "category", "wearables");
                String imageUrl = getJsonString(body, "imageUrl", "tech");

                int newId = Database.addProduct(name, desc, qty, price, category, originalPrice, imageUrl);
                Database.Product created = Database.getProduct(newId);

                // Log drop creation in WAL
                WAL.append("DROP-INIT-" + newId, "PRODUCT_CREATE", 0, newId, name, 0, qty, price, "SUCCESS");

                StringBuilder sb = new StringBuilder();
                sb.append("{\"success\":true,\"productId\":").append(newId)
                  .append(",\"product\":").append(productToJson(created)).append("}");
                sendJsonResponse(exchange, 201, sb.toString());
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private static class AdminDeleteProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = readRequestBody(exchange);
            int productId = getJsonInt(body, "productId", -1);

            if (productId <= 0) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Invalid product ID\"}");
                return;
            }

            boolean ok = Database.deleteProduct(productId);
            WAL.append("DROP-DEL-" + productId, "PRODUCT_DELETE", 0, productId, "", 0, 0, 0, ok ? "SUCCESS" : "FAILED");
            sendJsonResponse(exchange, 200, "{\"success\":" + ok + ",\"productId\":" + productId + "}");
        }
    }

    // ==========================================
    // Purchase Handler (Routes via WorkerPool & WAL)
    // ==========================================
    private static class PurchaseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = readRequestBody(exchange);
            int userId = getJsonInt(body, "userId", 1);
            int productId = getJsonInt(body, "productId", 1);

            // Execute via asynchronous bounded WorkerPool and Write-Ahead Log!
            WorkerPool.PurchaseResult res = WorkerPool.submitSync(userId, productId, 5000);

            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"success\":").append(res.success).append(",")
              .append("\"userId\":").append(res.userId).append(",")
              .append("\"productId\":").append(res.productId).append(",")
              .append("\"productName\":\"").append(escapeJson(res.productName)).append("\",")
              .append("\"price\":").append(res.price).append(",")
              .append("\"currency\":\"₹\",")
              .append("\"remaining\":").append(res.remaining).append(",")
              .append("\"latencyMs\":").append(res.latencyMs).append(",")
              .append("\"workerName\":\"").append(escapeJson(res.workerName)).append("\",")
              .append("\"lsn\":").append(res.lsn).append(",")
              .append("\"message\":\"").append(escapeJson(res.message)).append("\"")
              .append("}");

            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    // ==========================================
    // Reset / Restock Handler
    // ==========================================
    private static class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = readRequestBody(exchange);
            int productId = getJsonInt(body, "productId", 1);
            int quantity = getJsonInt(body, "quantity", 50);

            boolean ok = Database.resetProduct(productId, quantity);
            Database.Product p = Database.getProduct(productId);

            WAL.append("RESTOCK-" + productId, "STOCK_RESET", 0, productId, p != null ? p.name : "", 0, quantity, 0, "SUCCESS");

            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":").append(ok)
              .append(",\"productId\":").append(productId)
              .append(",\"product\":").append(p != null ? productToJson(p) : "null")
              .append("}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    // ==========================================
    // Purchases / Ledger Handler (Admin View)
    // ==========================================
    private static class PurchasesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            List<Database.PurchaseRecord> list = Database.getAllPurchases();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                Database.PurchaseRecord r = list.get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append("\"purchaseId\":").append(r.purchaseId).append(",")
                  .append("\"userId\":").append(r.userId).append(",")
                  .append("\"productId\":").append(r.productId).append(",")
                  .append("\"productName\":\"").append(escapeJson(r.productName != null ? r.productName : "")).append("\",")
                  .append("\"category\":\"").append(escapeJson(r.category != null ? r.category : "general")).append("\",")
                  .append("\"price\":").append(r.price).append(",")
                  .append("\"status\":\"").append(escapeJson(r.status)).append("\",")
                  .append("\"purchaseTime\":\"").append(escapeJson(r.purchaseTime != null ? r.purchaseTime : "")).append("\",")
                  .append("\"latencyMs\":").append(r.latencyMs)
                  .append("}");
            }
            sb.append("]");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private static class ClearPurchasesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            Database.clearAllPurchases();
            WAL.append("ADMIN-CLEAR", "PURCHASE_PURGE", 0, 0, "ALL_RECORDS", 0, 0, 0, "SUCCESS");
            sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Purchases cleared\"}");
        }
    }

    // ==========================================
    // WAL & WorkerPool Telemetry Handlers
    // ==========================================
    private static class AdminWalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            List<WAL.LogRecord> logs = WAL.getRecentLogs(60);
            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"currentLsn\":").append(WAL.getCurrentLsn()).append(",")
              .append("\"lastCheckpointLsn\":").append(WAL.getLastCheckpointLsn()).append(",")
              .append("\"totalCheckpoints\":").append(WAL.getTotalCheckpoints()).append(",")
              .append("\"fileSizeBytes\":").append(WAL.getLogFileSize()).append(",")
              .append("\"records\":[");

            for (int i = 0; i < logs.size(); i++) {
                WAL.LogRecord r = logs.get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append("\"lsn\":").append(r.lsn).append(",")
                  .append("\"timestamp\":\"").append(escapeJson(r.timestamp)).append("\",")
                  .append("\"txnId\":\"").append(escapeJson(r.txnId)).append("\",")
                  .append("\"opType\":\"").append(escapeJson(r.opType)).append("\",")
                  .append("\"userId\":").append(r.userId).append(",")
                  .append("\"productId\":").append(r.productId).append(",")
                  .append("\"productName\":\"").append(escapeJson(r.productName)).append("\",")
                  .append("\"prevQty\":").append(r.prevQty).append(",")
                  .append("\"newQty\":").append(r.newQty).append(",")
                  .append("\"price\":").append(r.price).append(",")
                  .append("\"status\":\"").append(escapeJson(r.status)).append("\"")
                  .append("}");
            }
            sb.append("]}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private static class AdminWorkerPoolHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            List<WorkerPool.WorkerThread> workers = WorkerPool.getWorkers();
            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"queueSize\":").append(WorkerPool.getQueueSize()).append(",")
              .append("\"queueCapacity\":").append(WorkerPool.getQueueCapacity()).append(",")
              .append("\"workerCount\":").append(WorkerPool.getWorkerCount()).append(",")
              .append("\"totalSubmitted\":").append(WorkerPool.getTotalSubmitted()).append(",")
              .append("\"totalCompleted\":").append(WorkerPool.getTotalCompleted()).append(",")
              .append("\"totalRejected\":").append(WorkerPool.getTotalRejected()).append(",")
              .append("\"workers\":[");

            for (int i = 0; i < workers.size(); i++) {
                WorkerPool.WorkerThread w = workers.get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append("\"workerId\":").append(w.getWorkerId()).append(",")
                  .append("\"name\":\"").append(escapeJson(w.getName())).append("\",")
                  .append("\"status\":\"").append(escapeJson(w.getStatus())).append("\",")
                  .append("\"currentTaskId\":\"").append(escapeJson(w.getCurrentTaskId())).append("\",")
                  .append("\"tasksProcessed\":").append(w.getTasksProcessed()).append(",")
                  .append("\"lastExecutionTimeMs\":").append(w.getLastExecutionTimeMs())
                  .append("}");
            }
            sb.append("]}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private static class AdminCheckpointHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            WAL.LogRecord rec = WAL.logCheckpoint("ADMIN_MANUAL_TRIGGER");
            sendJsonResponse(exchange, 200, "{\"success\":true,\"checkpointLsn\":" + rec.lsn + ",\"timestamp\":\"" + escapeJson(rec.timestamp) + "\"}");
        }
    }

    // ==========================================
    // Static File Serving
    // ==========================================
    private static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String pathStr = exchange.getRequestURI().getPath();
            if (pathStr.equals("/") || pathStr.isEmpty()) {
                pathStr = "/index.html";
            }

            Path filePath = Paths.get("public", pathStr.startsWith("/") ? pathStr.substring(1) : pathStr);
            if (!Files.exists(filePath)) {
                filePath = Paths.get("..", "public", pathStr.startsWith("/") ? pathStr.substring(1) : pathStr);
            }

            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                String mime = getMimeType(filePath.toString());
                byte[] bytes = Files.readAllBytes(filePath);
                exchange.getResponseHeaders().set("Content-Type", mime);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                String notFound = "<h1>404 Not Found</h1><p>File " + pathStr + " does not exist.</p>";
                byte[] bytes = notFound.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    // ==========================================
    // Utilities
    // ==========================================
    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Admin-Pin");
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int read;
            while ((read = is.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String productToJson(Database.Product p) {
        if (p == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("{")
          .append("\"id\":").append(p.id).append(",")
          .append("\"name\":\"").append(escapeJson(p.name)).append("\",")
          .append("\"description\":\"").append(escapeJson(p.description != null ? p.description : "")).append("\",")
          .append("\"totalQuantity\":").append(p.totalQuantity).append(",")
          .append("\"availableQuantity\":").append(p.availableQuantity).append(",")
          .append("\"price\":").append(p.price).append(",")
          .append("\"originalPrice\":").append(p.originalPrice).append(",")
          .append("\"category\":\"").append(escapeJson(p.category != null ? p.category : "general")).append("\",")
          .append("\"status\":\"").append(escapeJson(p.status)).append("\",")
          .append("\"imageUrl\":\"").append(escapeJson(p.imageUrl != null ? p.imageUrl : "tech")).append("\"")
          .append("}");
        return sb.toString();
    }

    private static String getJsonString(String json, String key, String defaultVal) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) return matcher.group(1);
        return defaultVal;
    }

    private static int getJsonInt(String json, String key, int defaultVal) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static double getJsonDouble(String json, String key, double defaultVal) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    private static String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "text/plain; charset=UTF-8";
    }
}
