import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
public class Main {
    private static final String WAL_FILE = "flash_sale.wal";
    private static final long HOLD_TTL_MS = 5 * 60 * 1000;   // 5 minute seat hold
    private static final double PAYMENT_SUCCESS_RATE = 0.90;
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.println("========================================");
        System.out.println("      FLASH SALE DISTRIBUTION SYSTEM");
        System.out.println("========================================");
        int workers = readInt(sc, "\nEnter number of worker threads in the pool: ");
        FlashSaleEngine engine = new FlashSaleEngine(workers, HOLD_TTL_MS, true,
                PAYMENT_SUCCESS_RATE, Database::confirmPurchase, WAL_FILE);
        try {
            // ADMIN SIDE: add products
            int numProducts = readInt(sc, "\nEnter how many products you want to add: ");
            List<Product> products = new ArrayList<>();
            for (int i = 1; i <= numProducts; i++) {
                System.out.println("\n--- ADMIN: ADD PRODUCT " + i + " ---");
                System.out.print("Enter Product/Event Name: ");
                String name = sc.nextLine();
                System.out.print("Enter Description: ");
                String description = sc.nextLine();
                int totalTickets = readInt(sc, "Enter Total quantity: ");
                double price = readDouble(sc, "Enter product Price: ");
                int createdId = Database.addProduct(name, description, totalTickets, price);
                if (createdId != -1) {
                    Product p = new Product(createdId, name, description, totalTickets, price);
                    engine.registerProduct(p);
                    products.add(p);
                    System.out.println("Product created successfully! Assigned Product ID: " + createdId);
                } else {
                    System.out.println("Failed to create product.");
                }
            }
            if (products.isEmpty()) {
                System.out.println("\nNo products to sell, exiting.");
                return;
            }
            int numberOfUsers = readInt(sc, "\nEnter number of concurrent users: ");
            // every user wants 1 to 3 different products, in whatever order they clicked them
            Random rnd = new Random();
            List<List<Integer>> wanted = new ArrayList<>();
            for (int u = 0; u < numberOfUsers; u++) {
                List<Integer> ids = new ArrayList<>();
                for (Product p : products) {
                    ids.add(p.getId());
                }
                Collections.shuffle(ids, rnd);
                int count = 1 + rnd.nextInt(Math.min(3, ids.size()));
                wanted.add(new ArrayList<>(ids.subList(0, count)));
            }
            System.out.println("\n========================================");
            System.out.println(" FLASH SALE STARTED (" + numberOfUsers + " users, "
                    + workers + " workers)");
            System.out.println("========================================");
            long startTime = System.currentTimeMillis();
            List<CompletableFuture<PurchaseResult>> futures = new ArrayList<>();
            for (int u = 0; u < numberOfUsers; u++) {
                futures.add(engine.submit(u + 1, wanted.get(u)));
            }
            List<PurchaseResult> results = new ArrayList<>();
            for (CompletableFuture<PurchaseResult> f : futures) {
                results.add(f.join());
            }
            long executionTime = System.currentTimeMillis() - startTime;
            if (numberOfUsers <= 50) {
                for (int u = 0; u < numberOfUsers; u++) {
                    System.out.println("User-" + (u + 1) + " " + wanted.get(u) + " -> " + results.get(u));
                }
            }
            // REPORT PER PRODUCT
            for (Product p : products) {
                int remaining = engine.getAvailable(p.getId());
                int dbRemaining = Database.getAvailableQuantity(p.getId());
                System.out.println("\n--- REPORT FOR PRODUCT ID: " + p.getId() + " (" + p.getName() + ") ---");
                System.out.println("Total Tickets           : " + p.getTotalQuantity());
                System.out.println("Tickets Sold            : " + (p.getTotalQuantity() - remaining));
                System.out.println("Remaining (engine)      : " + remaining);
                System.out.println("Remaining (MySQL)       : " + dbRemaining);
                System.out.println("Sale Status             : " + (remaining == 0 ? "SOLD OUT" : "AVAILABLE"));
            }
            // OVERALL REPORT
            Metrics m = engine.getMetrics();
            System.out.println("\n--- OVERALL REPORT ---");
            System.out.println("Concurrent Users        : " + numberOfUsers);
            System.out.println("Confirmed Orders        : " + m.count(PurchaseResult.Status.CONFIRMED));
            System.out.println("Sold Out                : " + m.count(PurchaseResult.Status.SOLD_OUT));
            System.out.println("Payment Failed          : " + m.count(PurchaseResult.Status.PAYMENT_FAILED));
            System.out.println("Hold Expired            : " + m.count(PurchaseResult.Status.HOLD_EXPIRED));
            System.out.println("Aborted (deadlock)      : " + m.count(PurchaseResult.Status.DEADLOCK_ABORTED));
            System.out.println("Execution Time          : " + executionTime + " ms");
            m.printSummary();
            // pretend we crashed: rebuild the stock from the log file only and compare
            engine.shutdown();
            System.out.println("\n--- CRASH RECOVERY CHECK (replaying " + WAL_FILE + ") ---");
            Map<Integer, Integer> recovered = WriteAheadLog.replay(WAL_FILE);
            boolean allMatch = true;
            for (Product p : products) {
                int fromLog = recovered.getOrDefault(p.getId(), -1);
                boolean same = fromLog == p.getAvailable();
                allMatch &= same;
                System.out.println("Product " + p.getId() + " -> WAL says " + fromLog + ", memory says "
                        + p.getAvailable() + (same ? "  [MATCH]" : "  [MISMATCH]"));
            }
            System.out.println(allMatch ? "Recovery verified, stock can be restored exactly."
                                        : "Recovery mismatch, something is wrong.");
            System.out.println("\n========================================");
            System.out.println(" ALL FLASH SALES COMPLETED SUCCESSFULLY!");
            System.out.println("========================================");
        } finally {
            engine.shutdown();   // harmless if already done
            sc.close();
        }
    }
    // reading whole lines avoids the usual nextInt()/nextLine() newline mess
    private static int readInt(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Integer.parseInt(sc.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Please enter a whole number.");
            }
        }
    }
    private static double readDouble(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Double.parseDouble(sc.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Please enter a number.");
            }
        }
    }
}
