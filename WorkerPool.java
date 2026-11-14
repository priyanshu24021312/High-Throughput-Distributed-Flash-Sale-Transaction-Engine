import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// WorkerPool
// A fixed number of worker threads (consumers) take purchase requests from a bounded buffer (made with semaphores).
// This stops the server from creating a new thread for every request when thousands of users click together.
// If the buffer is full a new request is rejected at once (back-pressure), so the server is never overloaded.
public class WorkerPool {

    private static final int DEFAULT_WORKER_COUNT = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    private static final int QUEUE_CAPACITY = 2000;
    private static final int MAX_TRIES = 3;   // a deadlock victim is put back in the queue, at most 3 times

    private static final BoundedBuffer<PurchaseTask> queue = new BoundedBuffer<>(QUEUE_CAPACITY);
    private static final List<WorkerThread> workers = new CopyOnWriteArrayList<>();
    private static volatile boolean running = true;

    private static final AtomicLong totalSubmitted = new AtomicLong(0);
    private static final AtomicLong totalCompleted = new AtomicLong(0);
    private static final AtomicLong totalRejected = new AtomicLong(0);
    private static final AtomicLong totalRequeued = new AtomicLong(0);
    private static final AtomicInteger peakQueue = new AtomicInteger(0);

    // one purchase request (one or more products)
    public static class PurchaseTask {
        public final String taskId;
        public final int userId;
        public final int[] productIds;
        public final long enqueueNanos;
        public int tries = 0;
        public final CompletableFuture<PurchaseResult> future;

        public PurchaseTask(int userId, int[] productIds) {
            this.taskId = "TASK-" + UUID.randomUUID().toString().substring(0, 8);
            this.userId = userId;
            this.productIds = productIds;
            this.enqueueNanos = System.nanoTime();
            this.future = new CompletableFuture<>();
        }
    }

    // answer of one purchase request
    public static class PurchaseResult {
        public boolean success;
        public String reason = "";
        public int userId;
        public int productId;
        public String productName = "";
        public double price;          // total price of all items
        public int remaining;
        public long latencyMs;
        public String message;
        public String workerName;
        public long lsn;
        public String pnr = "-";      // filled after payment
        public List<Database.PurchaseRecord> items = new ArrayList<>();
        public int expiresInSeconds = 0;
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
                    PurchaseTask task = queue.poll(500);
                    if (task == null) continue;

                    status = "PROCESSING";
                    currentTaskId = task.taskId;
                    String txnId = "TXN-" + System.currentTimeMillis() + "-" + task.userId;
                    WAL.logBegin(txnId, task.userId, task.productIds[0]);

                    // hold the seat (strict 2PL inside the database)
                    Database.BuyResult buy = Database.hold(task.userId, task.productIds);

                    // deadlock victim: put the request back in the queue and try again later
                    if (!buy.success && buy.reason.equals("DEADLOCK_VICTIM") && task.tries < MAX_TRIES) {
                        task.tries++;
                        if (queue.offer(task)) {
                            totalRequeued.incrementAndGet();
                            continue;
                        }
                    }

                    status = "FLUSHING_WAL";
                    if (buy.success) {
                        WAL.flush(buy.lsn);   // the answer goes to the customer only after the log is on the disk
                    } else {
                        WAL.logAbort(txnId, task.userId, task.productIds[0], buy.reason);
                    }

                    long micros = (System.nanoTime() - task.enqueueNanos) / 1000;
                    lastExecutionTimeMs = micros / 1000;
                    Stats.addLatency(micros);
                    tasksProcessed.incrementAndGet();
                    totalCompleted.incrementAndGet();

                    PurchaseResult result = new PurchaseResult();
                    result.success = buy.success;
                    result.reason = buy.reason;
                    result.userId = task.userId;
                    result.productId = task.productIds[0];
                    result.productName = buy.productName;
                    result.price = buy.price;
                    result.remaining = buy.remaining;
                    result.latencyMs = micros / 1000;
                    result.message = buy.message;
                    result.workerName = getName();
                    result.lsn = buy.lsn;
                    result.items = buy.records;
                    if (buy.success) result.expiresInSeconds = Database.HOLD_SECONDS;
                    task.future.complete(result);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[" + getName() + " Error] " + e);
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
        System.out.println("[WorkerPool] Initializing " + workerCount + " worker threads, queue capacity " + QUEUE_CAPACITY);
        for (int i = 1; i <= workerCount; i++) {
            WorkerThread worker = new WorkerThread(i);
            workers.add(worker);
            worker.start();
        }
    }

    public static CompletableFuture<PurchaseResult> submit(int userId, int productId) {
        return submit(userId, new int[]{productId});
    }

    // put the request in the queue, if the queue is full reject it
    public static CompletableFuture<PurchaseResult> submit(int userId, int[] productIds) {
        totalSubmitted.incrementAndGet();
        PurchaseTask task = new PurchaseTask(userId, productIds);
        if (!queue.offer(task)) {
            totalRejected.incrementAndGet();
            PurchaseResult rejected = new PurchaseResult();
            rejected.success = false;
            rejected.reason = "QUEUE_FULL";
            rejected.userId = userId;
            rejected.productId = productIds[0];
            rejected.message = "High Traffic Spike: Worker Pool Queue Saturated (Backpressure Rejection)";
            rejected.workerName = "RateLimiter";
            return CompletableFuture.completedFuture(rejected);
        }
        int size = queue.size();
        peakQueue.accumulateAndGet(size, Math::max);
        return task.future;
    }

    // submit and wait for the answer (max timeoutMs)
    public static PurchaseResult submitSync(int userId, int productId, long timeoutMs) {
        try {
            return submit(userId, productId).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return failed(userId, productId, "TIMEOUT", "Transaction Timeout in WorkerPool", "TimeoutHandler");
        } catch (Exception e) {
            return failed(userId, productId, "ERROR", "WorkerPool Execution Error: " + e.getMessage(), "ErrorHandler");
        }
    }

    public static PurchaseResult failed(int userId, int productId, String reason, String message, String who) {
        PurchaseResult result = new PurchaseResult();
        result.success = false;
        result.reason = reason;
        result.userId = userId;
        result.productId = productId;
        result.message = message;
        result.workerName = who;
        return result;
    }

    // ---------- getters for admin page ----------
    public static int getQueueSize() { return queue.size(); }
    public static int getQueueCapacity() { return QUEUE_CAPACITY; }
    public static int getWorkerCount() { return workers.size(); }
    public static long getTotalSubmitted() { return totalSubmitted.get(); }
    public static long getTotalCompleted() { return totalCompleted.get(); }
    public static long getTotalRejected() { return totalRejected.get(); }
    public static long getTotalRequeued() { return totalRequeued.get(); }
    public static int getPeakQueue() { return peakQueue.get(); }
    public static int resetPeakQueue() { return peakQueue.getAndSet(0); }

    public static List<WorkerThread> getWorkers() {
        return Collections.unmodifiableList(workers);
    }
}
