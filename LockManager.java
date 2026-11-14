import java.util.*;

// Lock Manager: Strict Two-Phase Locking (2PL) with Shared (S) and Exclusive (X) locks.
//   Growing phase  : a transaction asks for the locks it needs (lock).
//   Shrinking phase: all its locks are released together at the end (unlockAll).
// S + S is allowed (many readers), S + X and X + X are not (writers need the row alone).
// If a lock is not free the thread waits (monitor wait/notifyAll) and the Wait-For Graph is told.
public class LockManager {

    // thrown when a transaction must stop: deadlock victim, timeout
    public static class LockAbort extends RuntimeException {
        public final String reason;

        public LockAbort(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    private static class Entry {
        Set<String> sharedBy = new HashSet<>();
        String exclusiveBy = null;
    }

    private static final Map<String, Entry> table = new HashMap<>();          // resource -> who holds it
    private static final Map<String, Set<String>> heldBy = new HashMap<>();   // transaction -> its resources
    private static final Map<String, Long> birth = new HashMap<>();           // transaction -> start number (small = old)
    private static final Set<String> victims = new HashSet<>();               // transactions that must abort
    private static long birthCounter = 0;

    // autoResolve = true: if this wait makes a deadlock, the youngest transaction in the cycle is aborted
    public static synchronized void lock(String txn, String resource, boolean exclusive, long timeoutMs, boolean autoResolve) {
        if (!birth.containsKey(txn)) birth.put(txn, ++birthCounter);
        long start = System.nanoTime();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean waited = false;
        try {
            while (true) {
                Entry entry = table.computeIfAbsent(resource, k -> new Entry());
                if (canGrant(entry, txn, exclusive)) break;
                if (victims.remove(txn)) throw new LockAbort("DEADLOCK_VICTIM");
                waited = true;
                WaitForGraph.setWaiting(txn, resource, blockers(entry, txn, exclusive));
                if (autoResolve) pickVictim(txn);
                if (victims.remove(txn)) throw new LockAbort("DEADLOCK_VICTIM");
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new LockAbort("LOCK_TIMEOUT");
                try {
                    LockManager.class.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LockAbort("INTERRUPTED");
                }
            }
        } finally {
            WaitForGraph.stopWaiting(txn);
            if (waited) Stats.addLockWait(System.nanoTime() - start);
        }
        Entry entry = table.get(resource);
        if (exclusive) {
            entry.sharedBy.remove(txn);   // S lock becomes X lock (upgrade)
            entry.exclusiveBy = txn;
        } else if (!txn.equals(entry.exclusiveBy)) {
            entry.sharedBy.add(txn);
        }
        heldBy.computeIfAbsent(txn, k -> new HashSet<>()).add(resource);
        victims.remove(txn);
    }

    private static boolean canGrant(Entry entry, String txn, boolean exclusive) {
        boolean otherWriter = entry.exclusiveBy != null && !entry.exclusiveBy.equals(txn);
        if (!exclusive) return !otherWriter;
        boolean otherReader = false;
        for (String reader : entry.sharedBy) {
            if (!reader.equals(txn)) otherReader = true;
        }
        return !otherWriter && !otherReader;
    }

    // the transactions that stop this request
    private static Set<String> blockers(Entry entry, String txn, boolean exclusive) {
        Set<String> set = new LinkedHashSet<>();
        if (entry.exclusiveBy != null && !entry.exclusiveBy.equals(txn)) set.add(entry.exclusiveBy);
        if (exclusive) {
            for (String reader : entry.sharedBy) {
                if (!reader.equals(txn)) set.add(reader);
            }
        }
        return set;
    }

    // if txn is in a deadlock cycle, abort the youngest transaction of the cycle
    private static void pickVictim(String txn) {
        List<String> cycle = WaitForGraph.findCycle();
        if (!cycle.contains(txn)) return;
        String youngest = null;
        for (String name : cycle) {
            if (youngest == null || birth.getOrDefault(name, 0L) > birth.getOrDefault(youngest, 0L)) youngest = name;
        }
        victims.add(youngest);
        LockManager.class.notifyAll();
    }

    // used by the deadlock simulation (admin presses Resolve)
    public static synchronized void abort(String txn) {
        victims.add(txn);
        LockManager.class.notifyAll();
    }

    // end of the transaction: release everything (shrinking phase)
    public static synchronized void unlockAll(String txn) {
        Set<String> mine = heldBy.remove(txn);
        if (mine != null) {
            for (String resource : mine) {
                Entry entry = table.get(resource);
                if (entry == null) continue;
                entry.sharedBy.remove(txn);
                if (txn.equals(entry.exclusiveBy)) entry.exclusiveBy = null;
            }
        }
        birth.remove(txn);
        victims.remove(txn);
        WaitForGraph.stopWaiting(txn);
        LockManager.class.notifyAll();
    }
}
