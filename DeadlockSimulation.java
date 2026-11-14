import java.util.*;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// Deadlock Simulation (to show the Wait-For Graph and the LockManager)
// The admin starts a flash sale of one product with 2 to 5 buyer threads.
// Mode 1, deadlock: buyer 1 takes lock 1 and asks for lock 2, buyer 2 takes lock 2 and asks for lock 3 ...
//   the last buyer asks for lock 1, so they wait in a circle and the deadlock always happens.
//   The Wait-For Graph finds the cycle. The youngest buyer is the victim: it is rolled back (its locks are released),
//   the others finish, and then the victim tries again (like a real DBMS does).
// Mode 2, sorted lock order (deadlock prevention): every buyer takes the lower lock first,
//   a circle can never form and nobody has to be rolled back.
public class DeadlockSimulation {

    private static final String[] LOCK_NAMES = {"Stock-Lock", "Payment-Lock", "Order-Lock", "Coupon-Lock", "Delivery-Lock"};
    private static final AtomicInteger nextUserId = new AtomicInteger(9001);

    // one buyer thread (the admin page shows this in a table)
    public static class Buyer {
        public String name;
        public int userId;
        public volatile String holds = "-";
        public volatile String waitsFor = "-";
        public volatile String state = "STARTING";
    }

    private static final List<Buyer> buyers = new CopyOnWriteArrayList<>();
    private static final AtomicInteger threadsLeft = new AtomicInteger(0);

    private static volatile String status = "IDLE";   // IDLE, RUNNING, DEADLOCK, RESOLVED
    private static volatile boolean running = false;
    private static volatile boolean ordered = false;
    private static volatile int productId = 0;
    private static volatile String productName = "";
    private static volatile String victim = "None";
    private static volatile String cycleText = "None";
    private static volatile boolean resolveRequested = false;
    private static volatile long deadlockTime = 0;
    private static volatile int autoResolveSeconds = 30;
    private static volatile long waitMs = 60000;

    // graph picture taken at the moment of the deadlock (so admin can still see it after it is resolved)
    private static volatile List<WaitForGraph.Edge> snapshotEdges = new ArrayList<>();
    private static volatile List<String> snapshotCycle = new ArrayList<>();

    // returns null if started, otherwise the reason why it could not start
    public static synchronized String start(int productIdToSell, int threadCount, int autoSeconds, boolean sortedOrder) {
        if (running) return "A simulation is already running.";
        Database.Product product = Database.getProduct(productIdToSell);
        if (product == null) return "Product not found.";

        int count = Math.max(2, Math.min(5, threadCount));

        // reset old result
        buyers.clear();
        status = "RUNNING";
        running = true;
        ordered = sortedOrder;
        productId = productIdToSell;
        productName = product.name;
        victim = "None";
        cycleText = "None";
        resolveRequested = false;
        deadlockTime = 0;
        autoResolveSeconds = autoSeconds;
        waitMs = (autoSeconds + 30) * 1000L;
        snapshotEdges = new ArrayList<>();
        snapshotCycle = new ArrayList<>();
        threadsLeft.set(count);

        for (int i = 0; i < count; i++) {
            Buyer buyer = new Buyer();
            buyer.userId = nextUserId.getAndIncrement();
            buyer.name = "Buyer-" + buyer.userId;
            buyers.add(buyer);
        }

        // in deadlock mode every buyer waits here until all buyers have their first lock
        CyclicBarrier barrier = new CyclicBarrier(count);

        for (int i = 0; i < count; i++) {
            int index = i;
            int next = (i + 1) % count;   // the last buyer asks for the lock of the first buyer
            Buyer buyer = buyers.get(i);
            new Thread(() -> buyerTask(buyer, index, next, barrier), buyer.name).start();
        }

        Thread monitor = new Thread(DeadlockSimulation::monitorTask, "Deadlock-Monitor");
        monitor.setDaemon(true);
        monitor.start();
        return null;
    }

    private static void buyerTask(Buyer me, int index, int next, CyclicBarrier barrier) {
        String first = LOCK_NAMES[index];
        String second = LOCK_NAMES[next];
        if (ordered && index > next) {
            // sorted order: always the lower lock first
            first = LOCK_NAMES[next];
            second = LOCK_NAMES[index];
        }
        try {
            try {
                // 1. growing phase: take the first lock
                LockManager.lock(me.name, first, true, waitMs, false);
                me.holds = first;
                me.state = "HOLDING " + first;

                // 2. in deadlock mode wait until every buyer has its first lock, so the circle is sure to form
                if (ordered) Thread.sleep(400); else barrier.await(20, TimeUnit.SECONDS);

                // 3. ask for the second lock, which another buyer is holding
                me.waitsFor = second;
                me.state = "WAITING for " + second;
                LockManager.lock(me.name, second, true, waitMs, false);
                me.waitsFor = "-";
                me.holds = first + ", " + second;
                book(me, "BOOKED");
            } catch (LockManager.LockAbort e) {
                // this buyer is the victim: roll back, release the locks, nothing is booked
                LockManager.unlockAll(me.name);
                me.holds = "-";
                me.waitsFor = "-";
                String txnId = "TXN-" + System.currentTimeMillis() + "-" + me.userId;
                WAL.logBegin(txnId, me.userId, productId);
                WAL.logAbort(txnId, me.userId, productId, "DEADLOCK_VICTIM");
                me.state = "ROLLED BACK (deadlock victim), will retry";

                // re-run the transaction later, this time in sorted order
                Thread.sleep(800);
                String low = LOCK_NAMES[Math.min(index, next)];
                String high = LOCK_NAMES[Math.max(index, next)];
                me.state = "RETRYING (sorted lock order)";
                LockManager.lock(me.name, low, true, waitMs, false);
                me.holds = low;
                LockManager.lock(me.name, high, true, waitMs, false);
                me.holds = low + ", " + high;
                book(me, "BOOKED after retry");
            }
        } catch (LockManager.LockAbort again) {
            me.state = "FAILED: " + again.reason;
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            me.state = "ERROR: " + e.getClass().getSimpleName();
            Thread.currentThread().interrupt();
        } finally {
            LockManager.unlockAll(me.name);
            me.holds = "-";
            me.waitsFor = "-";
            threadsLeft.decrementAndGet();
        }
    }

    // both locks are ours, so book the ticket (WorkerPool + WAL + payment)
    private static void book(Buyer me, String label) {
        WorkerPool.PurchaseResult result = FlashSale.buyAndPay(me.userId, productId);
        if (result.success) {
            me.state = label + " (PNR " + result.pnr + ")";
        } else {
            me.state = "REJECTED: " + result.message;
        }
    }

    // watches the graph: finds the deadlock, then picks the victim
    private static void monitorTask() {
        try {
            while (threadsLeft.get() > 0) {
                Thread.sleep(100);

                if (status.equals("RUNNING") && cycleIsOurs()) {
                    snapshotEdges = WaitForGraph.getEdges();
                    snapshotCycle = WaitForGraph.findCycle();
                    cycleText = String.join(" -> ", snapshotCycle) + " -> " + snapshotCycle.get(0);
                    deadlockTime = System.currentTimeMillis();
                    status = "DEADLOCK";
                }

                if (status.equals("DEADLOCK") && victim.equals("None")) {
                    boolean timeIsUp = System.currentTimeMillis() - deadlockTime > autoResolveSeconds * 1000L;
                    if (resolveRequested || timeIsUp) chooseVictim();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        status = "RESOLVED";
        running = false;
    }

    private static boolean cycleIsOurs() {
        List<String> cycle = WaitForGraph.findCycle();
        for (Buyer buyer : buyers) {
            if (cycle.contains(buyer.name)) return true;
        }
        return false;
    }

    // the youngest buyer (biggest user id) in the cycle is the victim
    private static void chooseVictim() {
        List<String> cycle = WaitForGraph.findCycle();
        Buyer chosen = null;
        for (Buyer buyer : buyers) {
            if (cycle.contains(buyer.name) && (chosen == null || buyer.userId > chosen.userId)) {
                chosen = buyer;
            }
        }
        if (chosen == null) {
            victim = "-";
            return;
        }
        victim = chosen.name;
        LockManager.abort(chosen.name);   // wakes the buyer, its lock request fails with DEADLOCK_VICTIM
        System.out.println("[DeadlockSimulation] Victim chosen: " + chosen.name + " (rolled back to break the deadlock)");
    }

    // admin pressed "Resolve Deadlock"
    public static boolean requestResolve() {
        if (status.equals("DEADLOCK") && victim.equals("None")) {
            resolveRequested = true;
            return true;
        }
        return false;
    }

    // used by the console version (Main.java)
    public static void waitUntilDone() {
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static int getSecondsLeft() {
        if (!status.equals("DEADLOCK") || !victim.equals("None")) return 0;
        long passed = (System.currentTimeMillis() - deadlockTime) / 1000;
        return (int) Math.max(0, autoResolveSeconds - passed);
    }

    public static List<Buyer> getBuyers() { return buyers; }
    public static String getStatus() { return status; }
    public static boolean isOrdered() { return ordered; }
    public static int getProductId() { return productId; }
    public static String getProductName() { return productName; }
    public static String getVictim() { return victim; }
    public static String getCycleText() { return cycleText; }
    public static List<WaitForGraph.Edge> getSnapshotEdges() { return snapshotEdges; }
    public static List<String> getSnapshotCycle() { return snapshotCycle; }
}
