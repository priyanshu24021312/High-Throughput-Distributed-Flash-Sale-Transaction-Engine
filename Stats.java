// Performance numbers for the admin page: latency, throughput and lock waiting time.
public class Stats {

    private static final int MAX = 5000;
    private static final long[] latencies = new long[MAX];   // microseconds
    private static final long[] doneTimes = new long[MAX];   // when the request finished (ms)
    private static int count = 0;
    private static int next = 0;
    private static long lockWaitNanos = 0;
    private static long lockWaits = 0;

    public static class Summary {
        public double avgMs, p95Ms, p99Ms, maxMs, perSecond;
        public int samples;
    }

    public static synchronized void addLatency(long micros) {
        latencies[next] = micros;
        doneTimes[next] = System.currentTimeMillis();
        next = (next + 1) % MAX;
        if (count < MAX) count++;
    }

    public static synchronized void addLockWait(long nanos) {
        lockWaitNanos += nanos;
        lockWaits++;
    }

    public static synchronized Summary summary() {
        Summary s = new Summary();
        s.samples = count;
        if (count == 0) return s;
        long[] sorted = java.util.Arrays.copyOf(latencies, count);
        java.util.Arrays.sort(sorted);
        long total = 0;
        for (long value : sorted) total += value;
        s.avgMs = total / 1000.0 / count;
        s.p95Ms = sorted[Math.min(count - 1, (int) (count * 0.95))] / 1000.0;
        s.p99Ms = sorted[Math.min(count - 1, (int) (count * 0.99))] / 1000.0;
        s.maxMs = sorted[count - 1] / 1000.0;
        // requests finished in the last 10 seconds
        long limit = System.currentTimeMillis() - 10000;
        int recent = 0;
        for (int i = 0; i < count; i++) {
            if (doneTimes[i] > limit) recent++;
        }
        s.perSecond = recent / 10.0;
        return s;
    }

    public static synchronized double avgLockWaitMs() {
        return lockWaits == 0 ? 0 : lockWaitNanos / 1000000.0 / lockWaits;
    }

    public static synchronized long getLockWaits() {
        return lockWaits;
    }
}
