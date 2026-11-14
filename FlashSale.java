import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FlashSale {

    private static final AtomicInteger nextBurstUser = new AtomicInteger(100000);

    // result of a burst test (many users at the same moment)
    public static class Burst {
        public int users, booked, soldOut, rejected, other;
        public long millis;
        public double perSecond, avgLatencyMs;
        public int peakQueue;
    }

    // Buying goes: WorkerPool queue -> worker thread -> hold (2PL + WAL) -> payment -> confirmed
    // The payment gateway is only a pretend step here (the website has its own pay button)
    public static WorkerPool.PurchaseResult buyAndPay(int userId, int productId) {
        WorkerPool.PurchaseResult result = WorkerPool.submitSync(userId, productId, 10000);
        if (!result.success) return result;
        for (Database.PurchaseRecord item : result.items) {
            Database.BuyResult paid = Database.confirm(item.purchaseId);
            if (!paid.success) {
                result.success = false;
                result.reason = paid.reason;
                result.message = paid.message;
                return result;
            }
            result.pnr = item.pnr;
        }
        result.message = "Booked, PNR " + result.pnr;
        return result;
    }

    public static boolean purchase(int userId, int productId) {
        return buyAndPay(userId, productId).success;
    }

    public static CompletableFuture<WorkerPool.PurchaseResult> purchaseAsync(int userId, int productId) {
        return WorkerPool.submit(userId, productId);
    }

    // many users click Buy at the same moment: all requests go into the queue, the workers process them
    public static Burst burst(int productId, int users) {
        Burst burst = new Burst();
        burst.users = users;
        WorkerPool.resetPeakQueue();
        long start = System.nanoTime();
        int firstUser = nextBurstUser.getAndAdd(users);

        List<CompletableFuture<WorkerPool.PurchaseResult>> futures = new ArrayList<>();
        for (int i = 0; i < users; i++) futures.add(WorkerPool.submit(firstUser + i, productId));

        List<WorkerPool.PurchaseResult> results = new ArrayList<>();
        for (CompletableFuture<WorkerPool.PurchaseResult> future : futures) {
            try {
                results.add(future.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                results.add(WorkerPool.failed(0, productId, "TIMEOUT", "timeout", "burst"));
            }
        }

        // the customers who got a seat pay now (8 at a time)
        ExecutorService payers = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> payments = new ArrayList<>();
        long totalLatency = 0;
        for (WorkerPool.PurchaseResult r : results) {
            totalLatency += r.latencyMs;
            if (r.success) {
                payments.add(payers.submit(() -> Database.confirm(r.items.get(0).purchaseId).success));
            } else if (r.reason.equals("SOLD_OUT")) {
                burst.soldOut++;
            } else if (r.reason.equals("QUEUE_FULL")) {
                burst.rejected++;
            } else {
                burst.other++;
            }
        }
        for (Future<Boolean> payment : payments) {
            try {
                if (payment.get()) burst.booked++; else burst.other++;
            } catch (Exception e) {
                burst.other++;
            }
        }
        payers.shutdown();

        burst.millis = Math.max(1, (System.nanoTime() - start) / 1000000);
        burst.perSecond = users * 1000.0 / burst.millis;
        burst.avgLatencyMs = users == 0 ? 0 : (double) totalLatency / users;
        burst.peakQueue = WorkerPool.getPeakQueue();
        return burst;
    }
}
