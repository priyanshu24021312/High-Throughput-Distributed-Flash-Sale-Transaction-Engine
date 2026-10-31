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

        // start the engines: WAL, then database, then worker pool
        WAL.init();
        Database.checkMySQLAvailability();
        WorkerPool.init(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

        // if the port is busy try the next one
        HttpServer server = null;
        for (int i = 0; i < 10; i++) {
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

        // customer APIs
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/products", new ProductsHandler());
        server.createContext("/api/purchase", new PurchaseHandler());

        // admin APIs
        server.createContext("/api/admin/login", new AdminLoginHandler());
        server.createContext("/api/admin/overview", new AdminOverviewHandler());
        server.createContext("/api/admin/products", new AdminProductsHandler());
        server.createContext("/api/admin/products/delete", new AdminDeleteProductHandler());
        server.createContext("/api/admin/reset", new ResetHandler());
        server.createContext("/api/admin/purchases", new PurchasesHandler());
        server.createContext("/api/admin/clear-purchases", new ClearPurchasesHandler());

        // WAL, WorkerPool and Wait-For Graph
        server.createContext("/api/admin/wal", new AdminWalHandler());
        server.createContext("/api/admin/workerpool", new AdminWorkerPoolHandler());
        server.createContext("/api/admin/checkpoint", new AdminCheckpointHandler());
        server.createContext("/api/admin/waitforgraph", new WaitForGraphHandler());
        server.createContext("/api/admin/waitforgraph/demo", new WaitForGraphDemoHandler());

        // html, css, js files
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
        System.out.println(" Wait-For Graph : ACTIVE (deadlock detection)");
        System.out.println(" Java Runtime   : OpenJDK " + System.getProperty("java.version"));
        System.out.println(" Press Ctrl+C to stop the server.");
        System.out.println("=================================================");
    }

    // ---------- handlers ----------

    private static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            long uptimeSeconds = (System.currentTimeMillis() - SERVER_START_TIME) / 1000;
            StringBuilder json = new StringBuilder();
            json.append("{")
                .append("\"status\":\"ONLINE\",")
                .append("\"currency\":\"INR\",")
                .append("\"currencySymbol\":\"₹\",")
                .append("\"engine\":\"").append(escapeJson(Database.getStorageEngineName())).append("\",")
                .append("\"mysqlActive\":").append(Database.isMySQLActive()).append(",")
                .append("\"uptimeSeconds\":").append(uptimeSeconds).append(",")
                .append("\"totalProducts\":").append(Database.getAllProducts().size()).append(",")
                .append("\"totalPurchases\":").append(Database.getAllPurchases().size()).append(",")
                .append("\"walLsn\":").append(WAL.getCurrentLsn()).append(",")
                .append("\"workerCount\":").append(WorkerPool.getWorkerCount()).append(",")
                .append("\"javaVersion\":\"").append(escapeJson(System.getProperty("java.version"))).append("\"")
                .append("}");
            sendJson(exchange, 200, json.toString());
        }
    }

    private static class AdminLoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String pin = getJsonString(readBody(exchange), "pin", "");
            if (ADMIN_PIN.equals(pin.trim())) {
                sendJson(exchange, 200, "{\"authenticated\":true,\"message\":\"Admin login verified\"}");
            } else {
                sendJson(exchange, 401, "{\"authenticated\":false,\"message\":\"Invalid Admin PIN\"}");
            }
        }
    }

    private static class AdminOverviewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            List<Database.Product> products = Database.getAllProducts();
            List<Database.PurchaseRecord> purchases = Database.getAllPurchases();

            double totalRevenue = 0;
            for (Database.PurchaseRecord purchase : purchases) {
                totalRevenue += purchase.price;
            }

            int stockRemaining = 0;
            int initialStock = 0;
            for (Database.Product product : products) {
                stockRemaining += product.availableQuantity;
                initialStock += product.totalQuantity;
            }

            StringBuilder json = new StringBuilder();
            json.append("{")
                .append("\"totalRevenue\":").append(String.format(Locale.US, "%.2f", totalRevenue)).append(",")
                .append("\"totalOrders\":").append(purchases.size()).append(",")
                .append("\"totalProducts\":").append(products.size()).append(",")
                .append("\"totalStockRemaining\":").append(stockRemaining).append(",")
                .append("\"totalInitialStock\":").append(initialStock).append(",")
                .append("\"walLsn\":").append(WAL.getCurrentLsn()).append(",")
                .append("\"workersOnline\":").append(WorkerPool.getWorkerCount()).append(",")
                .append("\"queueSize\":").append(WorkerPool.getQueueSize()).append(",")
                .append("\"engine\":\"").append(escapeJson(Database.getStorageEngineName())).append("\"")
                .append("}");
            sendJson(exchange, 200, json.toString());
        }
    }

    private static class ProductsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                List<Database.Product> products = Database.getAllProducts();
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < products.size(); i++) {
                    if (i > 0) json.append(",");
                    json.append(productToJson(products.get(i)));
                }
                json.append("]");
                sendJson(exchange, 200, json.toString());
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    // admin: add a product
    private static class AdminProductsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                String name = getJsonString(body, "name", "Flash Drop Item");
                String description = getJsonString(body, "description", "Exclusive flash sale product");
                int quantity = getJsonInt(body, "quantity", 30);
                double price = getJsonDouble(body, "price", 1999.0);
                double originalPrice = getJsonDouble(body, "originalPrice", Math.round(price * 1.8));
                String category = getJsonString(body, "category", "wearables");
                String imageUrl = getJsonString(body, "imageUrl", "tech");

                int newId = Database.addProduct(name, description, quantity, price, category, originalPrice, imageUrl);
                Database.Product created = Database.getProduct(newId);

                WAL.append("DROP-INIT-" + newId, "PRODUCT_CREATE", 0, newId, name, 0, quantity, price, "SUCCESS");

                String json = "{\"success\":true,\"productId\":" + newId + ",\"product\":" + productToJson(created) + "}";
                sendJson(exchange, 201, json);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private static class AdminDeleteProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            int productId = getJsonInt(readBody(exchange), "productId", -1);
            if (productId <= 0) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"Invalid product ID\"}");
                return;
            }

            boolean deleted = Database.deleteProduct(productId);
            WAL.append("DROP-DEL-" + productId, "PRODUCT_DELETE", 0, productId, "", 0, 0, 0, deleted ? "SUCCESS" : "FAILED");
            sendJson(exchange, 200, "{\"success\":" + deleted + ",\"productId\":" + productId + "}");
        }
    }

    // customer buys a ticket (goes through WorkerPool and WAL)
    private static class PurchaseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String body = readBody(exchange);
            int userId = getJsonInt(body, "userId", 1);
            int productId = getJsonInt(body, "productId", 1);

            WorkerPool.PurchaseResult result = WorkerPool.submitSync(userId, productId, 5000);

            StringBuilder json = new StringBuilder();
            json.append("{")
                .append("\"success\":").append(result.success).append(",")
                .append("\"userId\":").append(result.userId).append(",")
                .append("\"productId\":").append(result.productId).append(",")
                .append("\"productName\":\"").append(escapeJson(result.productName)).append("\",")
                .append("\"price\":").append(result.price).append(",")
                .append("\"currency\":\"₹\",")
                .append("\"remaining\":").append(result.remaining).append(",")
                .append("\"latencyMs\":").append(result.latencyMs).append(",")
                .append("\"workerName\":\"").append(escapeJson(result.workerName)).append("\",")
                .append("\"lsn\":").append(result.lsn).append(",")
                .append("\"message\":\"").append(escapeJson(result.message)).append("\"")
                .append("}");
            sendJson(exchange, 200, json.toString());
        }
    }

    // admin: restock a product
    private static class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String body = readBody(exchange);
            int productId = getJsonInt(body, "productId", 1);
            int quantity = getJsonInt(body, "quantity", 50);

            boolean done = Database.resetProduct(productId, quantity);
            Database.Product product = Database.getProduct(productId);

            WAL.append("RESTOCK-" + productId, "STOCK_RESET", 0, productId, product != null ? product.name : "", 0, quantity, 0, "SUCCESS");

            String json = "{\"success\":" + done + ",\"productId\":" + productId +
                    ",\"product\":" + (product != null ? productToJson(product) : "null") + "}";
            sendJson(exchange, 200, json);
        }
    }

    // admin: list of all orders
    private static class PurchasesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            List<Database.PurchaseRecord> list = Database.getAllPurchases();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                Database.PurchaseRecord record = list.get(i);
                if (i > 0) json.append(",");
                json.append("{")
                    .append("\"purchaseId\":").append(record.purchaseId).append(",")
                    .append("\"userId\":").append(record.userId).append(",")
                    .append("\"productId\":").append(record.productId).append(",")
                    .append("\"productName\":\"").append(escapeJson(record.productName != null ? record.productName : "")).append("\",")
                    .append("\"category\":\"").append(escapeJson(record.category != null ? record.category : "general")).append("\",")
                    .append("\"price\":").append(record.price).append(",")
                    .append("\"status\":\"").append(escapeJson(record.status)).append("\",")
                    .append("\"purchaseTime\":\"").append(escapeJson(record.purchaseTime != null ? record.purchaseTime : "")).append("\",")
                    .append("\"latencyMs\":").append(record.latencyMs)
                    .append("}");
            }
            json.append("]");
            sendJson(exchange, 200, json.toString());
        }
    }

    private static class ClearPurchasesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            Database.clearAllPurchases();
            WAL.append("ADMIN-CLEAR", "PURCHASE_PURGE", 0, 0, "ALL_RECORDS", 0, 0, 0, "SUCCESS");
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"Purchases cleared\"}");
        }
    }

    // ---------- WAL, WorkerPool, Wait-For Graph ----------

    private static class AdminWalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            List<WAL.LogRecord> logs = WAL.getRecentLogs(60);
            StringBuilder json = new StringBuilder();
            json.append("{")
                .append("\"currentLsn\":").append(WAL.getCurrentLsn()).append(",")
                .append("\"lastCheckpointLsn\":").append(WAL.getLastCheckpointLsn()).append(",")
                .append("\"totalCheckpoints\":").append(WAL.getTotalCheckpoints()).append(",")
                .append("\"fileSizeBytes\":").append(WAL.getLogFileSize()).append(",")
                .append("\"records\":[");

            for (int i = 0; i < logs.size(); i++) {
                WAL.LogRecord record = logs.get(i);
                if (i > 0) json.append(",");
                json.append("{")
                    .append("\"lsn\":").append(record.lsn).append(",")
                    .append("\"timestamp\":\"").append(escapeJson(record.timestamp)).append("\",")
                    .append("\"txnId\":\"").append(escapeJson(record.txnId)).append("\",")
                    .append("\"opType\":\"").append(escapeJson(record.opType)).append("\",")
                    .append("\"userId\":").append(record.userId).append(",")
                    .append("\"productId\":").append(record.productId).append(",")
                    .append("\"productName\":\"").append(escapeJson(record.productName)).append("\",")
                    .append("\"prevQty\":").append(record.prevQty).append(",")
                    .append("\"newQty\":").append(record.newQty).append(",")
                    .append("\"price\":").append(record.price).append(",")
                    .append("\"status\":\"").append(escapeJson(record.status)).append("\"")
                    .append("}");
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
        }
    }

    private static class AdminWorkerPoolHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            List<WorkerPool.WorkerThread> workers = WorkerPool.getWorkers();
            StringBuilder json = new StringBuilder();
            json.append("{")
                .append("\"queueSize\":").append(WorkerPool.getQueueSize()).append(",")
                .append("\"queueCapacity\":").append(WorkerPool.getQueueCapacity()).append(",")
                .append("\"workerCount\":").append(WorkerPool.getWorkerCount()).append(",")
                .append("\"totalSubmitted\":").append(WorkerPool.getTotalSubmitted()).append(",")
                .append("\"totalCompleted\":").append(WorkerPool.getTotalCompleted()).append(",")
                .append("\"totalRejected\":").append(WorkerPool.getTotalRejected()).append(",")
                .append("\"workers\":[");

            for (int i = 0; i < workers.size(); i++) {
                WorkerPool.WorkerThread worker = workers.get(i);
                if (i > 0) json.append(",");
                json.append("{")
                    .append("\"workerId\":").append(worker.getWorkerId()).append(",")
                    .append("\"name\":\"").append(escapeJson(worker.getName())).append("\",")
                    .append("\"status\":\"").append(escapeJson(worker.getStatus())).append("\",")
                    .append("\"currentTaskId\":\"").append(escapeJson(worker.getCurrentTaskId())).append("\",")
                    .append("\"tasksProcessed\":").append(worker.getTasksProcessed()).append(",")
                    .append("\"lastExecutionTimeMs\":").append(worker.getLastExecutionTimeMs())
                    .append("}");
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
        }
    }

    private static class AdminCheckpointHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            WAL.LogRecord record = WAL.logCheckpoint("ADMIN_MANUAL_TRIGGER");
            sendJson(exchange, 200, "{\"success\":true,\"checkpointLsn\":" + record.lsn +
                    ",\"timestamp\":\"" + escapeJson(record.timestamp) + "\"}");
        }
    }

    // current wait-for graph: who is waiting for whom
    private static class WaitForGraphHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            List<WaitForGraph.Edge> edges = WaitForGraph.getEdges();
            List<String> cycle = WaitForGraph.findCycle();

            StringBuilder json = new StringBuilder();
            json.append("{")
                .append("\"deadlock\":").append(!cycle.isEmpty()).append(",")
                .append("\"totalWaits\":").append(WaitForGraph.getTotalWaits()).append(",")
                .append("\"totalDeadlocks\":").append(WaitForGraph.getTotalDeadlocks()).append(",")
                .append("\"lastDeadlock\":\"").append(escapeJson(WaitForGraph.getLastDeadlock())).append("\",")
                .append("\"cycle\":[");
            for (int i = 0; i < cycle.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(escapeJson(cycle.get(i))).append("\"");
            }
            json.append("],\"edges\":[");
            for (int i = 0; i < edges.size(); i++) {
                WaitForGraph.Edge edge = edges.get(i);
                if (i > 0) json.append(",");
                json.append("{")
                    .append("\"from\":\"").append(escapeJson(edge.from)).append("\",")
                    .append("\"to\":\"").append(escapeJson(edge.to)).append("\",")
                    .append("\"lock\":\"").append(escapeJson(edge.lock)).append("\"")
                    .append("}");
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
        }
    }

    // button in admin panel: start two threads that deadlock on purpose
    private static class WaitForGraphDemoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            boolean started = WaitForGraph.startDeadlockDemo();
            sendJson(exchange, 200, "{\"success\":" + started + ",\"message\":\"" +
                    (started ? "Deadlock demo started" : "Demo is already running") + "\"}");
        }
    }

    // ---------- static files ----------

    private static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            String urlPath = exchange.getRequestURI().getPath();
            if (urlPath.equals("/") || urlPath.isEmpty()) {
                urlPath = "/index.html";
            }
            String fileName = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;

            Path filePath = Paths.get("public", fileName);
            if (!Files.exists(filePath)) {
                filePath = Paths.get("..", "public", fileName);
            }

            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                byte[] bytes = Files.readAllBytes(filePath);
                exchange.getResponseHeaders().set("Content-Type", getMimeType(filePath.toString()));
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                byte[] bytes = ("<h1>404 Not Found</h1><p>File " + urlPath + " does not exist.</p>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    // ---------- small helper methods ----------

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Admin-Pin");
    }

    // adds CORS headers, returns true if the request was OPTIONS (already answered)
    private static boolean isPreflight(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    // returns true if the request is POST, otherwise sends 405
    private static boolean isPost(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) return true;
        exchange.sendResponseHeaders(405, -1);
        return false;
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody();
             ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                result.write(buffer, 0, count);
            }
            return result.toString(StandardCharsets.UTF_8);
        }
    }

    private static String productToJson(Database.Product product) {
        if (product == null) return "null";
        return "{" +
                "\"id\":" + product.id + "," +
                "\"name\":\"" + escapeJson(product.name) + "\"," +
                "\"description\":\"" + escapeJson(product.description != null ? product.description : "") + "\"," +
                "\"totalQuantity\":" + product.totalQuantity + "," +
                "\"availableQuantity\":" + product.availableQuantity + "," +
                "\"price\":" + product.price + "," +
                "\"originalPrice\":" + product.originalPrice + "," +
                "\"category\":\"" + escapeJson(product.category != null ? product.category : "general") + "\"," +
                "\"status\":\"" + escapeJson(product.status) + "\"," +
                "\"imageUrl\":\"" + escapeJson(product.imageUrl != null ? product.imageUrl : "tech") + "\"" +
                "}";
    }

    // small json readers (find "key": value using regex)
    private static String getJsonString(String json, String key, String defaultValue) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : defaultValue;
    }

    private static int getJsonInt(String json, String key, int defaultValue) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static double getJsonDouble(String json, String key, double defaultValue) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
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
