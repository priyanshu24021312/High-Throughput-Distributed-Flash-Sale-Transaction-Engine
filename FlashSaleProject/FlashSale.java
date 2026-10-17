import java.util.concurrent.CompletableFuture;

public class FlashSale {

    /*
     * Critical Section routed through the WorkerPool Concurrency Engine
     * and Write-Ahead Logging (WAL) Algorithm.
     *
     * 1. Request enters bounded task queue with backpressure protection.
     * 2. Dedicated WorkerThread claims task.
     * 3. Operation is logged to disk via WAL before in-memory state transition.
     * 4. Pessimistic row locking ensures zero oversell.
     */
    public static boolean purchase(int userId, int productId) {
        WorkerPool.PurchaseResult result = WorkerPool.submitSync(userId, productId, 5000);
        return result.success;
    }

    public static CompletableFuture<WorkerPool.PurchaseResult> purchaseAsync(int userId, int productId) {
        return WorkerPool.submit(userId, productId);
    }
}
