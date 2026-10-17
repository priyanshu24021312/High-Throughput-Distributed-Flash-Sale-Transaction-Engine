import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Runs without MySQL (purchases go to a no-op store), so it can be run any time:
 *   java TestSuite
 */
public class TestSuite {

    private static int passed = 0;
    private static int failed = 0;

    private static final FlashSaleEngine.PurchaseStore NO_DB = (user, ids) -> true;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("           FLASH SALE TEST SUITE");
        System.out.println("========================================");

        testNoOverselling();
        testDeadlockAvoidance();
        testDeadlockDetection();
        testHoldExpiry();
        testCrashRecovery();
        testBPlusTree();

        System.out.println("\n========================================");
        System.out.println(" PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("========================================");
        System.exit(failed == 0 ? 0 : 1);
    }

    // 500 users fight for 50 tickets, exactly 50 must win and nothing goes negative
    private static void testNoOverselling() {
        System.out.println("\n[TEST 1] Concurrency: no overselling");
        String wal = "test1.wal";
        FlashSaleEngine e = new FlashSaleEngine(8, 60_000, true, 1.0, NO_DB, wal);
        e.registerProduct(new Product(1, "Concert", "test", 50, 100));

        List<List<Integer>> reqs = new ArrayList<>();
        for (int i = 0; i < 500; i++) reqs.add(Arrays.asList(1));
        List<PurchaseResult> res = runAll(e, reqs);

        int confirmed = count(res, PurchaseResult.Status.CONFIRMED);
        check("exactly 50 confirmed (got " + confirmed + ")", confirmed == 50);
        check("remaining stock is 0", e.getAvailable(1) == 0);
        check("everyone else got SOLD_OUT",
                count(res, PurchaseResult.Status.SOLD_OUT) == 450);
        e.getMetrics().printSummary();
        e.shutdown();
        new File(wal).delete();
    }

    // everybody locks in sorted order, so opposite requests [1,2] and [2,1] can't deadlock
    private static void testDeadlockAvoidance() {
        System.out.println("\n[TEST 2] Deadlock prevention (sorted lock order)");
        String wal = "test2.wal";
        FlashSaleEngine e = new FlashSaleEngine(8, 60_000, true, 1.0, NO_DB, wal);
        e.registerProduct(new Product(1, "A", "test", 1000, 10));
        e.registerProduct(new Product(2, "B", "test", 1000, 10));

        List<PurchaseResult> res = runAll(e, oppositeOrders(200));
        check("all 200 orders confirmed", count(res, PurchaseResult.Status.CONFIRMED) == 200);
        check("zero deadlocks (got " + e.getMetrics().deadlocks() + ")", e.getMetrics().deadlocks() == 0);
        e.shutdown();
        new File(wal).delete();
    }

    // same workload with ordering switched off: deadlocks WILL happen and the WFG has to sort them out
    private static void testDeadlockDetection() {
        System.out.println("\n[TEST 3] Deadlock detection + recovery (ordering turned off)");
        String wal = "test3.wal";
        FlashSaleEngine e = new FlashSaleEngine(8, 60_000, false, 1.0, NO_DB, wal);
        e.registerProduct(new Product(1, "A", "test", 1000, 10));
        e.registerProduct(new Product(2, "B", "test", 1000, 10));

        List<PurchaseResult> res = runAll(e, oppositeOrders(200));
        int confirmed = count(res, PurchaseResult.Status.CONFIRMED);
        int aborted = count(res, PurchaseResult.Status.DEADLOCK_ABORTED);

        System.out.println("   deadlocks detected: " + e.getMetrics().deadlocks()
                + ", re-queued: " + e.getMetrics().retries()
                + ", confirmed: " + confirmed + ", gave up: " + aborted);
        check("deadlocks were detected", e.getMetrics().deadlocks() > 0);
        check("every request finished (no hang)", res.size() == 200);
        check("stock matches confirmed orders (A)", e.getAvailable(1) == 1000 - confirmed);
        check("stock matches confirmed orders (B)", e.getAvailable(2) == 1000 - confirmed);
        e.shutdown();
        new File(wal).delete();
    }

    // TTL of 1 ms, payment takes at least 10 ms so every hold times out and stock must come back
    private static void testHoldExpiry() {
        System.out.println("\n[TEST 4] Hold expiry returns stock");
        String wal = "test4.wal";
        FlashSaleEngine e = new FlashSaleEngine(4, 1, true, 1.0, NO_DB, wal);
        e.registerProduct(new Product(1, "A", "test", 5, 10));

        List<List<Integer>> reqs = new ArrayList<>();
        for (int i = 0; i < 20; i++) reqs.add(Arrays.asList(1));
        List<PurchaseResult> res = runAll(e, reqs);

        check("nothing confirmed", count(res, PurchaseResult.Status.CONFIRMED) == 0);
        check("stock back to 5 (got " + e.getAvailable(1) + ")", e.getAvailable(1) == 5);
        e.shutdown();
        new File(wal).delete();
    }

    // random multi item orders with 10% payment failures, then rebuild stock from the WAL only
    private static void testCrashRecovery() {
        System.out.println("\n[TEST 5] Crash recovery from WAL");
        String wal = "test5.wal";
        FlashSaleEngine e = new FlashSaleEngine(8, 60_000, true, 0.9, NO_DB, wal);
        for (int id = 1; id <= 3; id++) {
            e.registerProduct(new Product(id, "P" + id, "test", 100, 10));
        }

        Random rnd = new Random(7);
        List<List<Integer>> reqs = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            List<Integer> ids = new ArrayList<>(Arrays.asList(1, 2, 3));
            Collections.shuffle(ids, rnd);
            reqs.add(new ArrayList<>(ids.subList(0, 1 + rnd.nextInt(3))));
        }
        runAll(e, reqs);

        int[] live = new int[4];
        for (int id = 1; id <= 3; id++) live[id] = e.getAvailable(id);
        e.shutdown();

        Map<Integer, Integer> recovered = WriteAheadLog.replay(wal);
        for (int id = 1; id <= 3; id++) {
            check("product " + id + ": memory " + live[id] + " == WAL " + recovered.get(id),
                    recovered.get(id) != null && recovered.get(id) == live[id]);
        }
        new File(wal).delete();
    }

    private static void testBPlusTree() {
        System.out.println("\n[TEST 6] B+ tree index");
        BPlusTree<String> t = new BPlusTree<>();
        List<Integer> keys = new ArrayList<>();
        for (int i = 1; i <= 200; i++) keys.add(i);
        Collections.shuffle(keys, new Random(3));
        for (int k : keys) t.put(k, "v" + k);

        boolean allFound = true;
        for (int k = 1; k <= 200; k++) {
            if (!("v" + k).equals(t.get(k))) allFound = false;
        }
        List<Integer> ordered = t.keysInOrder();
        boolean sorted = ordered.size() == 200;
        for (int i = 0; i < ordered.size() && sorted; i++) {
            if (ordered.get(i) != i + 1) sorted = false;
        }
        check("all 200 keys found", allFound);
        check("leaf chain is sorted", sorted);
        check("missing key returns null", t.get(999) == null);
    }

    // ------------------------------------------------------------ helpers

    private static List<List<Integer>> oppositeOrders(int n) {
        List<List<Integer>> reqs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            reqs.add(i % 2 == 0 ? Arrays.asList(1, 2) : Arrays.asList(2, 1));
        }
        return reqs;
    }

    private static List<PurchaseResult> runAll(FlashSaleEngine e, List<List<Integer>> reqs) {
        List<CompletableFuture<PurchaseResult>> fs = new ArrayList<>();
        for (int i = 0; i < reqs.size(); i++) {
            fs.add(e.submit(i + 1, reqs.get(i)));
        }
        List<PurchaseResult> out = new ArrayList<>();
        for (CompletableFuture<PurchaseResult> f : fs) out.add(f.join());
        return out;
    }

    private static int count(List<PurchaseResult> res, PurchaseResult.Status s) {
        int c = 0;
        for (PurchaseResult r : res) if (r.status == s) c++;
        return c;
    }

    private static void check(String what, boolean ok) {
        System.out.println("   " + (ok ? "PASS  " : "FAIL  ") + what);
        if (ok) passed++; else failed++;
    }
}
