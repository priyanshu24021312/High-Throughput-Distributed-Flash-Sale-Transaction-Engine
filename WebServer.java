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
import java.util.concurrent.TimeUnit;
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
        Database.recover();   // rebuild the data from the snapshot + WAL (or load MySQL)
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

        server.setExecutor(Executors.newFixedThreadPool(16));

        // customer APIs
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/products", new ProductsHandler());
        server.createContext("/api/purchase", new PurchaseHandler());
        server.createContext("/api/pay", new PayHandler());

        // admin APIs
        server.createContext("/api/admin/login", new AdminLoginHandler());
        server.createContext("/api/admin/overview", new AdminOverviewHandler());
        server.createContext("/api/admin/products", new AdminProductsHandler());
        server.createContext("/api/admin/products/delete", new AdminDeleteProductHandler());
        server.createContext("/api/admin/products/update", new AdminUpdateProductHandler());
        server.createContext("/api/admin/reset", new ResetHandler());
        server.createContext("/api/admin/purchases", new PurchasesHandler());
        server.createContext("/api/admin/clear-purchases", new ClearPurchasesHandler());

        // WAL, WorkerPool and Wait-For Graph
        server.createContext("/api/admin/wal", new AdminWalHandler());
        server.createContext("/api/admin/workerpool", new AdminWorkerPoolHandler());
        server.createContext("/api/admin/checkpoint", new AdminCheckpointHandler());
        server.createContext("/api/admin/stats", new StatsHandler());
        server.createContext("/api/admin/burst", new BurstHandler());
        server.createContext("/api/admin/crash", new CrashHandler());
        server.createContext("/api/admin/waitforgraph", new WaitForGraphHandler());
        server.createContext("/api/admin/waitforgraph/simulate", new WaitForGraphSimulateHandler());
        server.createContext("/api/admin/waitforgraph/resolve", new WaitForGraphResolveHandler());

        // html, css, js files
        server.createContext("/", new StaticFileHandler());

        // when the server is stopped normally (Ctrl+C) take a last checkpoint
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Database.checkpoint("SERVER_SHUTDOWN");
            } catch (Exception ignored) {}
        }));

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
        System.out.println(" Concurrency    : Strict 2PL (S/X locks), B+ Tree index, group commit WAL");
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
                String imageUrl = getJsonString(body, "imageUrl", "");   // empty = the icon is chosen from the category and name

                int newId = Database.addProduct(name, description, quantity, price, category, originalPrice, imageUrl);
                Database.Product created = Database.getProduct(newId);

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
            sendJson(exchange, 200, "{\"success\":" + deleted + ",\"productId\":" + productId + "}");
        }
    }

    // admin: change name, price, description, category or quantity of a product
    private static class AdminUpdateProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String body = readBody(exchange);
            int productId = getJsonInt(body, "productId", -1);
            Database.Product oldProduct = Database.getProduct(productId);
            if (oldProduct == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"Product not found\"}");
                return;
            }

            String name = getJsonString(body, "name", "").trim();
            String description = getJsonString(body, "description", "").trim();
            String category = getJsonString(body, "category", "");
            double price = getJsonDouble(body, "price", -1);
            double originalPrice = getJsonDouble(body, "originalPrice", -1);
            int quantity = getJsonInt(body, "quantity", oldProduct.totalQuantity);

            if (name.isEmpty() || name.length() > 100) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"Name is required (max 100 letters)\"}");
                return;
            }
            if (description.length() > 255) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"Description is too long (max 255 letters)\"}");
                return;
            }
            if (price <= 0) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"Price must be more than 0\"}");
                return;
            }

            boolean updated = Database.updateProduct(productId, name, description, price, originalPrice, category, quantity);
            Database.Product product = Database.getProduct(productId);

            String json = "{\"success\":" + updated + ",\"productId\":" + productId +
                    ",\"product\":" + productToJson(product) + "}";
            sendJson(exchange, 200, json);
        }
    }

    // customer clicks Buy: the seat is held for 5 minutes (goes through the WorkerPool, 2PL and WAL)
    private static class PurchaseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String body = readBody(exchange);
            int userId = getJsonInt(body, "userId", 1);
            int[] ids = getJsonIntList(body, "productIds");
            if (ids.length == 0) ids = new int[]{getJsonInt(body, "productId", 1)};
            int[] productIds = ids;

            // this thread only puts the request in the queue and goes back to the next customer.
            // The answer is sent later by the worker thread, so a few web threads can serve thousands of users.
            WorkerPool.submit(userId, productIds)
                .orTimeout(10, TimeUnit.SECONDS)
                .whenComplete((answer, error) -> {
                    WorkerPool.PurchaseResult result = answer;
                    if (error != null || result == null) {
                        result = WorkerPool.failed(userId, productIds[0], "TIMEOUT", "Transaction Timeout in WorkerPool", "TimeoutHandler");
                    }
                    try {
                        sendJson(exchange, 200, purchaseToJson(result));
                    } catch (IOException ignored) {}
                });
        }
    }

    // customer pays (or cancels) the held seats
    private static class PayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String body = readBody(exchange);
            int[] ids = getJsonIntList(body, "purchaseIds");
            if (ids.length == 0) ids = new int[]{getJsonInt(body, "purchaseId", -1)};
            boolean paying = !getJsonString(body, "action", "pay").equals("cancel");

            if (paying) {
                try {
                    Thread.sleep(600);   // the payment gateway is only a pretend delay in this project
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            boolean allOk = true;
            String message = paying ? "Payment successful" : "Booking cancelled, seat released";
            String firstPnr = "-";
            StringBuilder items = new StringBuilder();
            for (int id : ids) {
                Database.BuyResult result = paying ? Database.confirm(id) : Database.cancel(id, "CANCELLED");
                if (!result.success) {
                    allOk = false;
                    message = result.message;
                }
                Database.PurchaseRecord order = Database.getPurchase(id);
                if (order == null) continue;
                if (items.length() > 0) items.append(",");
                items.append(orderToJson(order));
                if (order.pnr != null && !order.pnr.equals("-") && firstPnr.equals("-")) firstPnr = order.pnr;
            }
            sendJson(exchange, 200, "{\"success\":" + allOk + ",\"action\":\"" + (paying ? "pay" : "cancel") +
                    "\",\"message\":\"" + escapeJson(message) + "\",\"pnr\":\"" + escapeJson(firstPnr) + "\",\"items\":[" + items + "]}");
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
                    .append("\"latencyMs\":").append(record.latencyMs).append(",")
                    .append("\"pnr\":\"").append(escapeJson(record.pnr)).append("\",")
                    .append("\"expiresInSeconds\":").append(secondsLeft(record))
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
                .append("\"totalFlushes\":").append(WAL.getTotalFlushes()).append(",")
                .append("\"flushedRecords\":").append(WAL.getFlushedRecords()).append(",")
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
                .append("\"totalRequeued\":").append(WorkerPool.getTotalRequeued()).append(",")
                .append("\"peakQueue\":").append(WorkerPool.getPeakQueue()).append(",")
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

            WAL.LogRecord record = Database.checkpoint("ADMIN_MANUAL_TRIGGER");
            sendJson(exchange, 200, "{\"success\":true,\"checkpointLsn\":" + record.lsn +
                    ",\"timestamp\":\"" + escapeJson(record.timestamp) + "\"}");
        }
    }

    // current wait-for graph: who is waiting for whom + result of the flash sale simulation
    private static class WaitForGraphHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            List<String> cycle = WaitForGraph.findCycle();

            StringBuilder json = new StringBuilder();
            json.append("{")
                .append("\"deadlock\":").append(!cycle.isEmpty()).append(",")
                .append("\"totalWaits\":").append(WaitForGraph.getTotalWaits()).append(",")
                .append("\"totalDeadlocks\":").append(WaitForGraph.getTotalDeadlocks()).append(",")
                .append("\"lastDeadlock\":\"").append(escapeJson(WaitForGraph.getLastDeadlock())).append("\",")
                .append("\"cycle\":").append(listToJson(cycle)).append(",")
                .append("\"edges\":").append(edgesToJson(WaitForGraph.getEdges())).append(",")
                .append("\"simulation\":{")
                .append("\"status\":\"").append(DeadlockSimulation.getStatus()).append("\",")
                .append("\"ordered\":").append(DeadlockSimulation.isOrdered()).append(",")
                .append("\"productId\":").append(DeadlockSimulation.getProductId()).append(",")
                .append("\"productName\":\"").append(escapeJson(DeadlockSimulation.getProductName())).append("\",")
                .append("\"victim\":\"").append(escapeJson(DeadlockSimulation.getVictim())).append("\",")
                .append("\"cycleText\":\"").append(escapeJson(DeadlockSimulation.getCycleText())).append("\",")
                .append("\"secondsLeft\":").append(DeadlockSimulation.getSecondsLeft()).append(",")
                .append("\"snapshotCycle\":").append(listToJson(DeadlockSimulation.getSnapshotCycle())).append(",")
                .append("\"snapshotEdges\":").append(edgesToJson(DeadlockSimulation.getSnapshotEdges())).append(",")
                .append("\"buyers\":[");

            List<DeadlockSimulation.Buyer> buyers = DeadlockSimulation.getBuyers();
            for (int i = 0; i < buyers.size(); i++) {
                DeadlockSimulation.Buyer buyer = buyers.get(i);
                if (i > 0) json.append(",");
                json.append("{")
                    .append("\"name\":\"").append(escapeJson(buyer.name)).append("\",")
                    .append("\"userId\":").append(buyer.userId).append(",")
                    .append("\"holds\":\"").append(escapeJson(buyer.holds)).append("\",")
                    .append("\"waitsFor\":\"").append(escapeJson(buyer.waitsFor)).append("\",")
                    .append("\"state\":\"").append(escapeJson(buyer.state)).append("\"")
                    .append("}");
            }
            json.append("]}}");
            sendJson(exchange, 200, json.toString());
        }
    }

    // admin presses "Run Flash Sale Threads": start buyer threads that deadlock on purpose
    private static class WaitForGraphSimulateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String body = readBody(exchange);
            int productId = getJsonInt(body, "productId", -1);
            int threads = getJsonInt(body, "threads", 3);

            // if admin does not resolve it, it resolves by itself after 30 seconds
            boolean sorted = getJsonString(body, "mode", "deadlock").equals("sorted");
            String problem = DeadlockSimulation.start(productId, threads, 30, sorted);
            if (problem == null) {
                sendJson(exchange, 200, "{\"success\":true,\"message\":\"Flash sale threads started\"}");
            } else {
                sendJson(exchange, 200, "{\"success\":false,\"message\":\"" + escapeJson(problem) + "\"}");
            }
        }
    }

    // admin presses "Resolve Deadlock": roll back the victim
    private static class WaitForGraphResolveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            boolean done = DeadlockSimulation.requestResolve();
            sendJson(exchange, 200, "{\"success\":" + done + ",\"message\":\"" +
                    (done ? "Resolving deadlock" : "There is no deadlock to resolve") + "\"}");
        }
    }

    // numbers for the performance card
    private static class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;

            Stats.Summary s = Stats.summary();
            long flushes = WAL.getTotalFlushes();
            double perFlush = flushes == 0 ? 0 : (double) WAL.getFlushedRecords() / flushes;
            Database.RecoveryReport r = Database.getLastRecovery();
            String json = "{" +
                    "\"avgLatencyMs\":" + two(s.avgMs) + ",\"p95LatencyMs\":" + two(s.p95Ms) + ",\"p99LatencyMs\":" + two(s.p99Ms) +
                    ",\"maxLatencyMs\":" + two(s.maxMs) + ",\"samples\":" + s.samples + ",\"throughputPerSec\":" + two(s.perSecond) +
                    ",\"avgLockWaitMs\":" + two(Stats.avgLockWaitMs()) + ",\"lockWaits\":" + Stats.getLockWaits() +
                    ",\"deadlocksFound\":" + WaitForGraph.getTotalDeadlocks() +
                    ",\"walFlushes\":" + flushes + ",\"walFlushedRecords\":" + WAL.getFlushedRecords() + ",\"recordsPerFlush\":" + two(perFlush) +
                    ",\"indexOrder\":" + Database.getIndexOrder() + ",\"indexHeight\":" + Database.getIndexHeight() + ",\"indexKeys\":" + Database.getIndexSize() +
                    ",\"holdsPending\":" + Database.getPendingCount() + ",\"holdsExpired\":" + Database.getExpiredCount() + ",\"holdSeconds\":" + Database.HOLD_SECONDS +
                    ",\"queueRejected\":" + WorkerPool.getTotalRejected() + ",\"queueRequeued\":" + WorkerPool.getTotalRequeued() + ",\"peakQueue\":" + WorkerPool.getPeakQueue() +
                    ",\"recovery\":{\"source\":\"" + escapeJson(r.source) + "\",\"snapshotLsn\":" + r.snapshotLsn + ",\"redone\":" + r.redone +
                    ",\"undone\":" + r.undone + ",\"millis\":" + r.millis + ",\"time\":\"" + escapeJson(r.time) + "\"}" +
                    "}";
            sendJson(exchange, 200, json);
        }
    }

    // admin: many users click Buy at the same moment
    private static class BurstHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            String body = readBody(exchange);
            int productId = getJsonInt(body, "productId", 1);
            int users = Math.max(1, Math.min(10000, getJsonInt(body, "users", 500)));
            if (Database.getProduct(productId) == null) {
                sendJson(exchange, 200, "{\"success\":false,\"message\":\"Product not found\"}");
                return;
            }
            FlashSale.Burst b = FlashSale.burst(productId, users);
            sendJson(exchange, 200, "{\"success\":true,\"users\":" + b.users + ",\"booked\":" + b.booked + ",\"soldOut\":" + b.soldOut +
                    ",\"rejected\":" + b.rejected + ",\"other\":" + b.other + ",\"millis\":" + b.millis + ",\"perSecond\":" + two(b.perSecond) +
                    ",\"avgLatencyMs\":" + two(b.avgLatencyMs) + ",\"peakQueue\":" + b.peakQueue + "}");
        }
    }

    // admin: pretend the server crashed, then rebuild everything from the disk
    private static class CrashHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isPreflight(exchange)) return;
            if (!isPost(exchange)) return;

            Database.CrashReport c = Database.simulateCrash();
            Database.RecoveryReport r = c.recovery;
            sendJson(exchange, 200, "{\"success\":true,\"same\":" + c.same + ",\"before\":\"" + escapeJson(c.before) + "\",\"after\":\"" + escapeJson(c.after) +
                    "\",\"source\":\"" + escapeJson(r.source) + "\",\"snapshotLsn\":" + r.snapshotLsn + ",\"redone\":" + r.redone +
                    ",\"undone\":" + r.undone + ",\"millis\":" + r.millis + "}");
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
        // text between the quotes, \" inside the text is allowed
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? unescapeJson(matcher.group(1)) : defaultValue;
    }

    // opposite of escapeJson: \" -> "   \\ -> \   \n -> new line
    private static String unescapeJson(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\\' || i == text.length() - 1) {
                result.append(ch);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'u':
                    if (i + 4 < text.length()) {   // 4 hex digits must be there
                        try {
                            result.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {}
                    }
                    result.append('u');
                    break;
                default: result.append(next);   // \" \\ \/
            }
        }
        return result.toString();
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

    // "productIds": [1, 2, 3]
    private static int[] getJsonIntList(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([0-9,\\s]*)\\]").matcher(json);
        if (!matcher.find()) return new int[0];
        List<Integer> list = new ArrayList<>();
        for (String part : matcher.group(1).split(",")) {
            if (!part.trim().isEmpty()) list.add(Integer.parseInt(part.trim()));
        }
        int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) result[i] = list.get(i);
        return result;
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

    private static String two(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static int secondsLeft(Database.PurchaseRecord order) {
        if (!"PENDING_PAYMENT".equals(order.status)) return 0;
        return (int) Math.max(0, (order.expiresAt - System.currentTimeMillis()) / 1000);
    }

    private static String orderToJson(Database.PurchaseRecord order) {
        return "{\"purchaseId\":" + order.purchaseId + ",\"productId\":" + order.productId +
                ",\"name\":\"" + escapeJson(order.productName) + "\",\"price\":" + order.price +
                ",\"status\":\"" + escapeJson(order.status) + "\",\"pnr\":\"" + escapeJson(order.pnr) +
                "\",\"expiresInSeconds\":" + secondsLeft(order) + "}";
    }

    private static String purchaseToJson(WorkerPool.PurchaseResult result) {
        StringBuilder items = new StringBuilder();
        for (Database.PurchaseRecord order : result.items) {
            if (items.length() > 0) items.append(",");
            items.append(orderToJson(order));
        }
        int firstId = result.items.isEmpty() ? 0 : result.items.get(0).purchaseId;
        return "{" +
                "\"success\":" + result.success + "," +
                "\"reason\":\"" + escapeJson(result.reason) + "\"," +
                "\"userId\":" + result.userId + "," +
                "\"productId\":" + result.productId + "," +
                "\"productName\":\"" + escapeJson(result.productName) + "\"," +
                "\"price\":" + result.price + "," +
                "\"currency\":\"₹\"," +
                "\"remaining\":" + result.remaining + "," +
                "\"latencyMs\":" + result.latencyMs + "," +
                "\"workerName\":\"" + escapeJson(result.workerName) + "\"," +
                "\"lsn\":" + result.lsn + "," +
                "\"purchaseId\":" + firstId + "," +
                "\"expiresInSeconds\":" + result.expiresInSeconds + "," +
                "\"items\":[" + items + "]," +
                "\"message\":\"" + escapeJson(result.message) + "\"" +
                "}";
    }

    private static String listToJson(List<String> items) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(items.get(i))).append("\"");
        }
        return json.append("]").toString();
    }

    private static String edgesToJson(List<WaitForGraph.Edge> edges) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < edges.size(); i++) {
            WaitForGraph.Edge edge = edges.get(i);
            if (i > 0) json.append(",");
            json.append("{")
                .append("\"from\":\"").append(escapeJson(edge.from)).append("\",")
                .append("\"to\":\"").append(escapeJson(edge.to)).append("\",")
                .append("\"lock\":\"").append(escapeJson(edge.lock)).append("\"")
                .append("}");
        }
        return json.append("]").toString();
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
