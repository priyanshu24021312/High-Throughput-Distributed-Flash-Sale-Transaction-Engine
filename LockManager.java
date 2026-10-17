import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exclusive per-product locks (growing phase = acquire, shrinking phase = releaseAll).
 * Every time a transaction has to wait we record an edge in a wait-for graph,
 * and if that edge closes a cycle the waiting transaction is told to abort.
 */
public class LockManager {

    public static class DeadlockException extends Exception {
        public DeadlockException(String msg) {
            super(msg);
        }
    }

    private final Map<Integer, Long> owners = new HashMap<>();      // product -> txn holding it
    private final Map<Long, List<Integer>> held = new HashMap<>();  // txn -> products it holds
    private final Map<Long, Long> waitsFor = new HashMap<>();       // txn -> txn it is blocked on

    /** Blocks until the lock is ours. Returns how long we waited in nanoseconds. */
    public synchronized long acquire(long txn, int productId) throws DeadlockException, InterruptedException {
        long start = System.nanoTime();

        Long holder = owners.get(productId);
        while (holder != null && holder != txn) {
            waitsFor.put(txn, holder);
            if (closesCycle(txn)) {
                waitsFor.remove(txn);
                throw new DeadlockException("txn " + txn + " would deadlock on product " + productId);
            }
            try {
                wait(20);   // timeout so we re-check the graph even without a notify
            } catch (InterruptedException e) {
                waitsFor.remove(txn);
                throw e;
            }
            holder = owners.get(productId);
        }

        waitsFor.remove(txn);
        if (holder == null) {
            owners.put(productId, txn);
            held.computeIfAbsent(txn, k -> new ArrayList<>()).add(productId);
        }
        return System.nanoTime() - start;
    }

    /** Drops every lock this transaction has. Safe to call more than once. */
    public synchronized void releaseAll(long txn) {
        List<Integer> mine = held.remove(txn);
        if (mine != null) {
            for (int productId : mine) {
                owners.remove(productId);
            }
        }
        waitsFor.remove(txn);
        // anyone who was waiting on us has to look again, so forget those edges
        waitsFor.values().removeIf(v -> v == txn);
        notifyAll();
    }

    // each txn waits on at most one other txn, so we just follow the chain
    private boolean closesCycle(long start) {
        Long cur = waitsFor.get(start);
        int steps = 0;
        while (cur != null && steps++ < 100000) {
            if (cur == start) {
                return true;
            }
            cur = waitsFor.get(cur);
        }
        return false;
    }
}
