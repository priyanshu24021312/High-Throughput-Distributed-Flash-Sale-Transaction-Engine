import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ties all the layers from the proposal together:
 *   request queue + worker pool  ->  B+ tree lookup  ->  ordered locking (2PL)
 *   ->  5 minute seat hold  ->  payment  ->  WAL commit / rollback
 */
public class FlashSaleEngine {

    // where confirmed purchases get saved permanently (MySQL in Main, a stub in the tests)
    public interface PurchaseStore {
        boolean save(int userId, List<Integer> productIds);
    }

    private static final int MAX_ATTEMPTS = 5;
    private static final int PAYMENT_MIN_MS = 10;
    private static final int PAYMENT_MAX_MS = 30;
    // when locking is NOT ordered we pretend to do some work between locks,
    // otherwise deadlocks are too rare to ever show up in the demo
    private static final int UNORDERED_WORK_MS = 5;

    private static class Request {
        final long txnId;
        final int userId;
        final List<Integer> productIds;
        final CompletableFuture<PurchaseResult> future = new CompletableFuture<>();
        final long submittedAt = System.nanoTime();
        int attempts = 0;

        Request(long txnId, int userId, List<Integer> productIds) {
            this.txnId = txnId;
            this.userId = userId;
            this.productIds = productIds;
        }
    }

    private static class Hold {
        final long id;
        final int userId;
        final List<Integer> productIds;   // always sorted
        final long expiresAt;

        Hold(long id, int userId, List<Integer> productIds, long expiresAt) {
            this.id = id;
            this.userId = userId;
            this.productIds = productIds;
            this.expiresAt = expiresAt;
        }
    }

    private final BPlusTree<Product> index = new BPlusTree<>();
    private final LockManager locks = new LockManager();
    private final WorkerPool pool;
    private final WriteAheadLog wal;
    private final Metrics metrics = new Metrics();
    private final ConcurrentHashMap<Long, Hold> holds = new ConcurrentHashMap<>();
    private final AtomicLong txnCounter = new AtomicLong();
    private final ScheduledExecutorService reaper;

    private final long holdTtlMs;
    private final boolean orderedLocking;
    private final double paymentSuccessRate;
    private final PurchaseStore store;

    public FlashSaleEngine(int workers, long holdTtlMs, boolean orderedLocking,
                           double paymentSuccessRate, PurchaseStore store, String walPath) {
        this.holdTtlMs = holdTtlMs;
        this.orderedLocking = orderedLocking;
        this.paymentSuccessRate = paymentSuccessRate;
        this.store = store;
        this.wal = new WriteAheadLog(walPath, true);
        this.pool = new WorkerPool(workers);

        // background thread that gives back stock from holds nobody paid for in time
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hold-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(this::expireHolds, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void registerProduct(Product p) {
        index.put(p.getId(), p);
        wal.append("PRODUCT|" + p.getId() + "|" + p.getTotalQuantity());
    }

    public int getAvailable(int productId) {
        Product p = index.get(productId);
        return p == null ? -1 : p.getAvailable();
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public CompletableFuture<PurchaseResult> submit(int userId, List<Integer> productIds) {
        // drop duplicates, buying the same item twice in one order makes no sense
        List<Integer> ids = new ArrayList<>(new LinkedHashSet<>(productIds));
        Request r = new Request(txnCounter.incrementAndGet(), userId, ids);
        pool.submit(() -> handle(r));
        return r.future;
    }

    public void shutdown() {
        pool.shutdown();
        reaper.shutdownNow();
        wal.close();
    }

    // ---------------------------------------------------------------- request flow

    private void handle(Request r) {
        try {
            process(r);
        } catch (Throwable t) {
            finish(r, new PurchaseResult(PurchaseResult.Status.ERROR, null, t.toString()));
        }
    }

    private void process(Request r) {
        Hold hold;
        try {
            hold = reserve(r);
        } catch (LockManager.DeadlockException e) {
            // we were picked as the victim: locks are already released, put the request back in the queue
            metrics.recordDeadlock();
            if (r.attempts >= MAX_ATTEMPTS) {
                finish(r, new PurchaseResult(PurchaseResult.Status.DEADLOCK_ABORTED, null,
                        "gave up after " + MAX_ATTEMPTS + " retries"));
                return;
            }
            r.attempts++;
            metrics.recordRetry();
            pool.submit(() -> handle(r));
            return;
        }

        if (hold == null) {
            return;   // reserve() already answered (sold out / error)
        }

        boolean paid = simulatePayment();

        // whoever removes the hold from the map owns it, so stock can never be given back twice
        Hold mine = holds.remove(hold.id);
        if (mine == null) {
            finish(r, new PurchaseResult(PurchaseResult.Status.HOLD_EXPIRED, null, "hold timed out"));
            return;
        }
        if (System.currentTimeMillis() > mine.expiresAt) {
            releaseStock(mine);
            finish(r, new PurchaseResult(PurchaseResult.Status.HOLD_EXPIRED, null, "hold timed out"));
            return;
        }
        if (!paid) {
            releaseStock(mine);
            finish(r, new PurchaseResult(PurchaseResult.Status.PAYMENT_FAILED, null, "payment declined"));
            return;
        }

        String pnr = newPnr();
        // log first and fsync, only then tell anyone it worked
        wal.append("COMMIT|" + pnr + "|" + mine.userId + "|" + join(mine.productIds));
        if (store != null && !store.save(mine.userId, mine.productIds)) {
            System.out.println("Warning: PNR " + pnr + " is in the WAL but the database write failed");
        }
        finish(r, new PurchaseResult(PurchaseResult.Status.CONFIRMED, pnr, "ok"));
    }

    /**
     * Growing phase: lock every product, check stock, take the units and leave a hold.
     * Locks are always released before returning, even on failure.
     * Returns null if the request was already answered here.
     */
    private Hold reserve(Request r) throws LockManager.DeadlockException {
        List<Integer> order = new ArrayList<>(r.productIds);
        if (orderedLocking) {
            Collections.sort(order);   // same order for everyone = no circular wait possible
        }

        try {
            for (int id : order) {
                long waited = locks.acquire(r.txnId, id);
                metrics.recordLockWait(waited);
                if (!orderedLocking) {
                    pause(UNORDERED_WORK_MS);
                }
            }

            // check everything before touching anything, so a multi-item order is all or nothing
            List<Product> products = new ArrayList<>();
            for (int id : order) {
                Product p = index.get(id);
                if (p == null) {
                    finish(r, new PurchaseResult(PurchaseResult.Status.ERROR, null, "unknown product " + id));
                    return null;
                }
                if (p.getAvailable() <= 0) {
                    finish(r, new PurchaseResult(PurchaseResult.Status.SOLD_OUT, null,
                            "product " + id + " sold out"));
                    return null;
                }
                products.add(p);
            }

            for (Product p : products) {
                p.decrement();
            }

            List<Integer> sorted = new ArrayList<>(r.productIds);
            Collections.sort(sorted);
            Hold hold = new Hold(r.txnId, r.userId, sorted, System.currentTimeMillis() + holdTtlMs);
            holds.put(hold.id, hold);
            return hold;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(r, new PurchaseResult(PurchaseResult.Status.ERROR, null, "interrupted"));
            return null;
        } finally {
            locks.releaseAll(r.txnId);
        }
    }

    /** Puts the units of a failed / expired hold back into the pool. */
    private void releaseStock(Hold h) {
        while (true) {
            try {
                for (int id : h.productIds) {   // sorted already
                    locks.acquire(h.id, id);
                }
                for (int id : h.productIds) {
                    index.get(id).increment();
                }
                return;
            } catch (LockManager.DeadlockException e) {
                metrics.recordDeadlock();
                pause(5);   // let the other transaction go first, then try again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                locks.releaseAll(h.id);
            }
        }
    }

    private void expireHolds() {
        try {
            long now = System.currentTimeMillis();
            for (Hold h : holds.values()) {
                if (now > h.expiresAt && holds.remove(h.id, h)) {
                    releaseStock(h);
                }
            }
        } catch (Throwable t) {
            System.out.println("hold-reaper error: " + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void finish(Request r, PurchaseResult result) {
        if (r.future.complete(result)) {
            metrics.recordResult(result.status, System.nanoTime() - r.submittedAt);
        }
    }

    private boolean simulatePayment() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        pause(PAYMENT_MIN_MS + rnd.nextInt(PAYMENT_MAX_MS - PAYMENT_MIN_MS + 1));
        return rnd.nextDouble() < paymentSuccessRate;
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String newPnr() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String join(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }
}
