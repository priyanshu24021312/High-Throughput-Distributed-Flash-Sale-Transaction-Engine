import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WorkerPool Concurrency Algorithm
 * 
 * Manages an asynchronous bounded pool of dedicated worker threads with 
 * a bounded task queue. Smooths traffic spikes during flash drops,
 * enforces backpressure, and serializes transactions safely through WAL.
 */
public class WorkerPool {

    private static final int DEFAULT_WORKER_COUNT = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    private static final int QUEUE_CAPACITY = 2000;

    private static final BlockingQueue<PurchaseTask> taskQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final List<WorkerThread> workers = new CopyOnWriteArrayList<>();
    private static volatile boolean isRunning = true;

    private static final AtomicLong totalSubmitted = new AtomicLong(0);
    private static final AtomicLong totalCompleted = new AtomicLong(0);
    private static final AtomicLong totalRejected = new AtomicLong(0);

    // ==========================================
    // Task & Result Definitions
    // ==========================================
    public static class PurchaseTask {
        public final String taskId;
        public final int userId;
        public final int productId;
        public final long enqueueTime;
        public final CompletableFuture<PurchaseResult> future;

        public PurchaseTask(int userId, int productId) {
            this.taskId = "TASK-" + UUID.randomUUID().toString().substring(0, 8);
            this.userId = userId;
            this.productId = productId;
            this.enqueueTime = System.currentTimeMillis();
            this.future = new CompletableFuture<>();
        }
    }

    public static class PurchaseResult {
        public boolean success;
        public int userId;
        public int productId;
        public String productName;
        public double price;
        public int remaining;
        public long latencyMs;
        public String message;
        public String workerName;
        public long lsn;

        public PurchaseResult(boolean success, int userId, int productId, String productName, 
                              double price, int remaining, long latencyMs, String message, 
                              String workerName, long lsn) {
            this.success = success;
            this.userId = userId;
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.price = price;
            this.remaining = remaining;
            this.latencyMs = latencyMs;
            this.message = message;
            this.workerName = workerName;
            this.lsn = lsn;
        }
    }

    // ==========================================
    // Dedicated Worker Thread
    // ==========================================
    public static class WorkerThread extends Thread {
        private final int workerId;
        private volatile String status = "IDLE"; // IDLE, PROCESSING, FLUSHING_WAL
        private volatile String currentTaskId = "None";
        private final AtomicLong tasksProcessed = new AtomicLong(0);
        private volatile long lastExecutionTimeMs = 0;

        public WorkerThread(int workerId) {
            super("FlashWorker-" + workerId);
            this.workerId = workerId;
            setDaemon(true);
        }

        @Override
        public void run() {
            while (isRunning) {
                try {
                    status = "IDLE";
                    currentTaskId = "None";
                    PurchaseTask task = taskQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (task == null) continue;

                    status = "PROCESSING";
                    currentTaskId = task.taskId;
                    long start = System.currentTimeMillis();

                    // Generate atomic Transaction ID
                    String txnId = "TXN-" + System.currentTimeMillis() + "-" + task.userId;

                    // 1. Write-Ahead Log: Log TXN_BEGIN
                    WAL.logBegin(txnId, task.userId, task.productId);

                    // 2. Critical Section: Reserve Ticket in Database Engine
                    status = "FLUSHING_WAL";
                    boolean success = Database.purchaseTicket(task.userId, task.productId);
                    int remaining = Database.getAvailableQuantity(task.productId);
                    Database.Product prod = Database.getProduct(task.productId);

                    long executionTime = System.currentTimeMillis() - start;
                    lastExecutionTimeMs = executionTime;
                    tasksProcessed.incrementAndGet();
                    totalCompleted.incrementAndGet();

                    long recordLsn = 0;
                    if (success) {
                        // 3. Write-Ahead Log: Log ORDER_COMMIT with fsync
                        WAL.LogRecord commitRec = WAL.logCommit(txnId, task.userId, task.productId, 
                                prod != null ? prod.name : "Flash Item", remaining, prod != null ? prod.price : 0);
                        recordLsn = commitRec.lsn;

                        task.future.complete(new PurchaseResult(
                                true, task.userId, task.productId, prod != null ? prod.name : "",
                                prod != null ? prod.price : 0, remaining, executionTime,
                                "Purchase Confirmed via WorkerPool & WAL!", getName(), recordLsn));
                    } else {
                        // 3. Write-Ahead Log: Log TXN_ABORT
                        WAL.LogRecord abortRec = WAL.logAbort(txnId, task.userId, task.productId, 
                                remaining <= 0 ? "SOLD_OUT" : "DUPLICATE_CLAIM");
                        recordLsn = abortRec.lsn;

                        task.future.complete(new PurchaseResult(
                                false, task.userId, task.productId, prod != null ? prod.name : "",
                                prod != null ? prod.price : 0, remaining, executionTime,
                                remaining <= 0 ? "Sold Out" : "Duplicate reservation rejected",
                                getName(), recordLsn));
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[" + getName() + " Error] " + e.getMessage());
                }
            }
        }

        public int getWorkerId() { return workerId; }
        public String getStatus() { return status; }
        public String getCurrentTaskId() { return currentTaskId; }
        public long getTasksProcessed() { return tasksProcessed.get(); }
        public long getLastExecutionTimeMs() { return lastExecutionTimeMs; }
    }

    // ==========================================
    // Pool Lifecycle
    // ==========================================
    static {
        init(DEFAULT_WORKER_COUNT);
    }

    public static synchronized void init(int workerCount) {
        if (!workers.isEmpty()) return;

        System.out.println("[WorkerPool] Initializing " + workerCount + " dedicated worker threads with queue capacity " + QUEUE_CAPACITY);
        for (int i = 1; i <= workerCount; i++) {
            WorkerThread worker = new WorkerThread(i);
            workers.add(worker);
            worker.start();
        }
    }

    // ==========================================
    // Task Submission (Async & Sync with Backpressure)
    // ==========================================
    public static CompletableFuture<PurchaseResult> submit(int userId, int productId) {
        totalSubmitted.incrementAndGet();
        PurchaseTask task = new PurchaseTask(userId, productId);

        // Attempt non-blocking enqueue (Backpressure check)
        boolean accepted = taskQueue.offer(task);
        if (!accepted) {
            totalRejected.incrementAndGet();
            // Queue full -> reject with backpressure
            CompletableFuture<PurchaseResult> rejectedFuture = new CompletableFuture<>();
            rejectedFuture.complete(new PurchaseResult(
                    false, userId, productId, "", 0, 0, 0,
                    "High Traffic Spike: Worker Pool Queue Saturated (Backpressure Rejection)",
                    "RateLimiter", 0));
            return rejectedFuture;
        }

        return task.future;
    }

    public static PurchaseResult submitSync(int userId, int productId, long timeoutMs) {
        try {
            return submit(userId, productId).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new PurchaseResult(false, userId, productId, "", 0, 0, timeoutMs, 
                    "Transaction Timeout in WorkerPool", "TimeoutHandler", 0);
        } catch (Exception e) {
            return new PurchaseResult(false, userId, productId, "", 0, 0, 0, 
                    "WorkerPool Execution Error: " + e.getMessage(), "ErrorHandler", 0);
        }
    }

    // ==========================================
    // Telemetry & Inspector Getters
    // ==========================================
    public static int getQueueSize() {
        return taskQueue.size();
    }

    public static int getQueueCapacity() {
        return QUEUE_CAPACITY;
    }

    public static int getWorkerCount() {
        return workers.size();
    }

    public static long getTotalSubmitted() {
        return totalSubmitted.get();
    }

    public static long getTotalCompleted() {
        return totalCompleted.get();
    }

    public static long getTotalRejected() {
        return totalRejected.get();
    }

    public static List<WorkerThread> getWorkers() {
        return Collections.unmodifiableList(workers);
    }
}
