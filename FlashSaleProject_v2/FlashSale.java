import java.util.concurrent.CompletableFuture;

public class FlashSale {

    // Buying goes: WorkerPool queue -> worker thread -> WAL log -> Database (row lock)
    public static boolean purchase(int userId, int productId) {
        WorkerPool.PurchaseResult result = WorkerPool.submitSync(userId, productId, 5000);
        return result.success;
    }

    public static CompletableFuture<WorkerPool.PurchaseResult> purchaseAsync(int userId, int productId) {
        return WorkerPool.submit(userId, productId);
    }
}
