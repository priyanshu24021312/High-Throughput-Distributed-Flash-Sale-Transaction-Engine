import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Metrics {

    private final Map<PurchaseResult.Status, AtomicInteger> counts = new EnumMap<>(PurchaseResult.Status.class);
    private final List<Long> latencies = new ArrayList<>();
    private final AtomicLong lockWaitNanos = new AtomicLong();
    private final AtomicLong lockAcquisitions = new AtomicLong();
    private final AtomicInteger deadlocks = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();

    public Metrics() {
        for (PurchaseResult.Status s : PurchaseResult.Status.values()) {
            counts.put(s, new AtomicInteger());
        }
    }

    public void recordResult(PurchaseResult.Status status, long latencyNanos) {
        counts.get(status).incrementAndGet();
        synchronized (latencies) {
            latencies.add(latencyNanos);
        }
    }

    public void recordLockWait(long nanos) {
        lockWaitNanos.addAndGet(nanos);
        lockAcquisitions.incrementAndGet();
    }

    public void recordDeadlock() { deadlocks.incrementAndGet(); }
    public void recordRetry() { retries.incrementAndGet(); }

    public int count(PurchaseResult.Status s) { return counts.get(s).get(); }
    public int deadlocks() { return deadlocks.get(); }
    public int retries() { return retries.get(); }

    public void printSummary() {
        List<Long> sorted;
        synchronized (latencies) {
            sorted = new ArrayList<>(latencies);
        }
        Collections.sort(sorted);

        System.out.println("\n--- PERFORMANCE METRICS ---");
        if (sorted.isEmpty()) {
            System.out.println("No requests were processed.");
            return;
        }

        double sum = 0;
        for (long l : sorted) {
            sum += l;
        }

        System.out.printf("Requests Processed      : %d%n", sorted.size());
        System.out.printf("Avg Latency             : %.2f ms%n", sum / sorted.size() / 1e6);
        System.out.printf("Median Latency (p50)    : %.2f ms%n", percentile(sorted, 50));
        System.out.printf("p95 Latency             : %.2f ms%n", percentile(sorted, 95));
        System.out.printf("p99 Latency             : %.2f ms%n", percentile(sorted, 99));
        System.out.printf("Max Latency             : %.2f ms%n", sorted.get(sorted.size() - 1) / 1e6);

        long acq = lockAcquisitions.get();
        double avgWait = acq == 0 ? 0 : lockWaitNanos.get() / (double) acq / 1e6;
        System.out.printf("Avg Lock Wait           : %.3f ms (over %d lock acquisitions)%n", avgWait, acq);
        System.out.println("Deadlocks Resolved      : " + deadlocks.get());
        System.out.println("Requests Re-queued      : " + retries.get());
    }

    private double percentile(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx) / 1e6;
    }
}
