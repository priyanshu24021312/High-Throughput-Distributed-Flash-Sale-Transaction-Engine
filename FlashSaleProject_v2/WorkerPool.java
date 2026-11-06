import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// WorkerPool
// A fixed number of worker threads take purchase requests from a bounded queue.
// This stops the server from creating thousands of threads when many users click together.
public class WorkerPool {

    private static final int DEFAULT_WORKER_COUNT = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    private static final int QUEUE_CAPACITY = 2000;

    private static final BlockingQueue<PurchaseTask> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final List<WorkerThread> workers = new CopyOnWriteArrayList<>();
    private static volatile boolean running = true;

    private static final AtomicLong totalSubmitted = new AtomicLong(0);
    private static final AtomicLong totalCompleted = new AtomicLong(0);
    private static final AtomicLong totalRejected = new AtomicLong(0);

    // one purchase request
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

    // answer of one purchase request
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

    public static class WorkerThread extends Thread {
        private final int workerId;
        private volatile String status = "IDLE";   // IDLE, PROCESSING, FLUSHING_WAL
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
            while (running) {
                try {
                    status = "IDLE";
                    currentTaskId = "None";
                    PurchaseTask task = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (task == null) continue;

                    status = "PROCESSING";
                    currentTaskId = task.taskId;
                    long start = System.currentTimeMillis();
                    String txnId = "TXN-" + System.currentTimeMillis() + "-" + task.userId;

                    // 1. write TXN_BEGIN in the log first
                    WAL.logBegin(txnId, task.userId, task.productId);

                    // 2. now try to buy the ticket
                    status = "FLUSHING_WAL";
                    boolean success = Database.purchaseTicket(task.userId, task.productId);
                    int remaining = Database.getAvailableQuantity(task.productId);
                    Database.Product product = Database.getProduct(task.productId);
                    String productName = product != null ? product.name : "";
                    double price = product != null ? product.price : 0;

                    long timeTaken = System.currentTimeMillis() - start;
                    lastExecutionTimeMs = timeTaken;
                    tasksProcessed.incrementAndGet();
                    totalCompleted.incrementAndGet();

                    if (success) {
                        // 3. write ORDER_COMMIT in the log
                        String logName = product != null ? product.name : "Flash Item";
                        WAL.LogRecord record = WAL.logCommit(txnId, task.userId, task.productId, logName, remaining, price);
                        task.future.complete(new PurchaseResult(true, task.userId, task.productId, productName,
                                price, remaining, timeTaken, "Purchase Confirmed via WorkerPool & WAL!", getName(), record.lsn));
                    } else {
                        // 3. write TXN_ABORT in the log
                        WAL.LogRecord record = WAL.logAbort(txnId, task.userId, task.productId,
                                remaining <= 0 ? "SOLD_OUT" : "DUPLICATE_CLAIM");
                        task.future.complete(new PurchaseResult(false, task.userId, task.productId, productName,
                                price, remaining, timeTaken,
                                remaining <= 0 ? "Sold Out" : "Duplicate reservation rejected",
                                getName(), record.lsn));
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

    // put the request in the queue, if the queue is full reject it
    public static CompletableFuture<PurchaseResult> submit(int userId, int productId) {
        totalSubmitted.incrementAndGet();
        PurchaseTask task = new PurchaseTask(userId, productId);

        if (!queue.offer(task)) {
            totalRejected.incrementAndGet();
            CompletableFuture<PurchaseResult> rejected = new CompletableFuture<>();
            rejected.complete(new PurchaseResult(false, userId, productId, "", 0, 0, 0,
                    "High Traffic Spike: Worker Pool Queue Saturated (Backpressure Rejection)",
                    "RateLimiter", 0));
            return rejected;
        }
        return task.future;
    }

    // submit and wait for the answer (max timeoutMs)
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

    // ---------- getters for admin page ----------
    public static int getQueueSize() { return queue.size(); }
    public static int getQueueCapacity() { return QUEUE_CAPACITY; }
    public static int getWorkerCount() { return workers.size(); }
    public static long getTotalSubmitted() { return totalSubmitted.get(); }
    public static long getTotalCompleted() { return totalCompleted.get(); }
    public static long getTotalRejected() { return totalRejected.get(); }

    public static List<WorkerThread> getWorkers() {
        return Collections.unmodifiableList(workers);
    }
}
