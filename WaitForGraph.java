import java.util.*;

// Wait-For Graph (used to find deadlocks)
// Every transaction (thread) is a node. If A is waiting for a lock that B holds, there is an edge A -> B.
// A cycle in the graph (A -> B -> A) means a deadlock.
// The LockManager tells this class every time a thread starts or stops waiting.
public class WaitForGraph {

    private static class Wait {
        String lock;
        Set<String> holders;
    }

    // transaction -> the lock it is waiting for
    private static final Map<String, Wait> waits = new LinkedHashMap<>();
    private static int totalWaits = 0;
    private static int totalDeadlocks = 0;
    private static String lastDeadlock = "None";
    private static String countedCycle = "";   // the cycle we already counted, so we do not count it again

    public static class Edge {
        public String from;   // thread that is waiting
        public String to;     // thread that holds the lock
        public String lock;

        public Edge(String from, String to, String lock) {
            this.from = from;
            this.to = to;
            this.lock = lock;
        }
    }

    // thread is waiting for this lock, holders = threads that block it
    public static synchronized void setWaiting(String txn, String lock, Set<String> holders) {
        Wait old = waits.get(txn);
        if (old == null || !old.lock.equals(lock)) totalWaits++;
        Wait now = new Wait();
        now.lock = lock;
        now.holders = new LinkedHashSet<>(holders);
        waits.put(txn, now);
        checkDeadlock(txn);
    }

    // thread got the lock or gave up
    public static synchronized void stopWaiting(String txn) {
        if (waits.remove(txn) != null && findCycle().isEmpty()) countedCycle = "";
    }

    public static synchronized List<Edge> getEdges() {
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<String, Wait> entry : waits.entrySet()) {
            for (String holder : entry.getValue().holders) {
                if (!holder.equals(entry.getKey())) {
                    edges.add(new Edge(entry.getKey(), holder, entry.getValue().lock));
                }
            }
        }
        return edges;
    }

    private static Map<String, List<String>> buildGraph() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (Edge edge : getEdges()) {
            graph.computeIfAbsent(edge.from, k -> new ArrayList<>()).add(edge.to);
        }
        return graph;
    }

    // threads that are in a cycle, empty list = no deadlock
    public static synchronized List<String> findCycle() {
        Map<String, List<String>> graph = buildGraph();
        Set<String> finished = new HashSet<>();
        for (String start : graph.keySet()) {
            List<String> cycle = dfs(start, graph, new ArrayList<>(), finished);
            if (!cycle.isEmpty()) return cycle;
        }
        return new ArrayList<>();
    }

    // depth first search, path = nodes on the way we are walking now
    private static List<String> dfs(String node, Map<String, List<String>> graph, List<String> path, Set<String> finished) {
        if (path.contains(node)) {
            // we came back to a node that is already on the path, so this is a cycle
            return new ArrayList<>(path.subList(path.indexOf(node), path.size()));
        }
        if (finished.contains(node)) return new ArrayList<>();
        path.add(node);
        for (String next : graph.getOrDefault(node, new ArrayList<>())) {
            List<String> cycle = dfs(next, graph, path, finished);
            if (!cycle.isEmpty()) return cycle;
        }
        path.remove(path.size() - 1);
        finished.add(node);
        return new ArrayList<>();
    }

    private static void checkDeadlock(String txn) {
        List<String> cycle = findCycle();
        if (!cycle.contains(txn)) return;
        List<String> sorted = new ArrayList<>(cycle);
        Collections.sort(sorted);
        String key = String.join(",", sorted);
        if (key.equals(countedCycle)) return;
        countedCycle = key;
        totalDeadlocks++;
        lastDeadlock = String.join(" -> ", cycle) + " -> " + cycle.get(0);
        System.out.println("[WaitForGraph] DEADLOCK found: " + lastDeadlock);
    }

    public static synchronized int getTotalWaits() { return totalWaits; }
    public static synchronized int getTotalDeadlocks() { return totalDeadlocks; }
    public static synchronized String getLastDeadlock() { return lastDeadlock; }
}
