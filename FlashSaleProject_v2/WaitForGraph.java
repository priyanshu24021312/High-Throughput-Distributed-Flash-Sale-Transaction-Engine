import java.util.*;

// Wait-For Graph (used to find deadlocks)
// Every thread is a node in the graph.
// If thread A is waiting for a lock that thread B is holding, we draw an edge A -> B.
// If the edges make a cycle (A -> B -> A) then those threads are in a deadlock.
public class WaitForGraph {

    // lock name -> thread that is holding it
    private static final Map<String, String> lockHolder = new HashMap<>();
    // thread name -> lock name it is waiting for
    private static final Map<String, String> waitingFor = new LinkedHashMap<>();

    private static int totalWaits = 0;
    private static int totalDeadlocks = 0;
    private static String lastDeadlock = "None";

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

    // thread is going to ask for a lock
    public static synchronized void startWaiting(String thread, String lock) {
        waitingFor.put(thread, lock);
        String holder = lockHolder.get(lock);
        if (holder != null && !holder.equals(thread)) {
            totalWaits++;
            checkDeadlock(thread);
        }
    }

    // thread got the lock, so it is not waiting anymore
    public static synchronized void gotLock(String thread, String lock) {
        waitingFor.remove(thread);
        lockHolder.put(lock, thread);
    }

    // thread is done with the lock
    public static synchronized void releaseLock(String thread, String lock) {
        if (thread.equals(lockHolder.get(lock))) {
            lockHolder.remove(lock);
        }
    }

    // thread gave up waiting (timeout)
    public static synchronized void stopWaiting(String thread) {
        waitingFor.remove(thread);
    }

    // all current edges of the graph
    public static synchronized List<Edge> getEdges() {
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<String, String> entry : waitingFor.entrySet()) {
            String waiter = entry.getKey();
            String lock = entry.getValue();
            String holder = lockHolder.get(lock);
            if (holder != null && !holder.equals(waiter)) {
                edges.add(new Edge(waiter, holder, lock));
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

    // returns the threads that are in a cycle, empty list if there is no deadlock
    public static synchronized List<String> findCycle() {
        Map<String, List<String>> graph = buildGraph();
        Set<String> finished = new HashSet<>();
        for (String start : graph.keySet()) {
            List<String> cycle = dfs(start, graph, new ArrayList<>(), finished);
            if (!cycle.isEmpty()) return cycle;
        }
        return new ArrayList<>();
    }

    // depth first search, path = nodes on the current way
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

    private static void checkDeadlock(String thread) {
        List<String> cycle = findCycle();
        if (cycle.contains(thread)) {
            totalDeadlocks++;
            lastDeadlock = String.join(" -> ", cycle) + " -> " + cycle.get(0);
            System.out.println("[WaitForGraph] DEADLOCK found: " + lastDeadlock);
        }
    }

    public static synchronized int getTotalWaits() { return totalWaits; }
    public static synchronized int getTotalDeadlocks() { return totalDeadlocks; }
    public static synchronized String getLastDeadlock() { return lastDeadlock; }
}
