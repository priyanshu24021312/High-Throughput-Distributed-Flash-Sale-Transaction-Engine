import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

// Write-Ahead Log (WAL)
// Every change is first written to a log file on disk, then the data is changed.
// If the server crashes we can read the log again on startup.
public class WAL {

    private static final String LOG_DIR = "data";
    private static final String LOG_FILE = "data/wal.log";
    private static final String CHECKPOINT_FILE = "data/checkpoint.meta";

    private static final AtomicLong lsnCounter = new AtomicLong(1000);
    private static final Object writeLock = new Object();
    private static FileOutputStream fileStream = null;
    private static OutputStreamWriter writer = null;

    // last logs kept in memory for the admin page
    private static final ConcurrentLinkedDeque<LogRecord> recentLogs = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_LOGS = 150;

    private static volatile long lastCheckpointLsn = 0;
    private static volatile long totalCheckpoints = 0;

    public static class LogRecord {
        public long lsn;
        public String timestamp;
        public String txnId;
        public String opType;   // TXN_BEGIN, STOCK_RESERVE, ORDER_COMMIT, TXN_ABORT, CHECKPOINT
        public int userId;
        public int productId;
        public String productName;
        public int prevQty;
        public int newQty;
        public double price;
        public String status;

        public LogRecord(long lsn, String timestamp, String txnId, String opType, int userId,
                         int productId, String productName, int prevQty, int newQty, double price, String status) {
            this.lsn = lsn;
            this.timestamp = timestamp;
            this.txnId = txnId;
            this.opType = opType;
            this.userId = userId;
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.prevQty = prevQty;
            this.newQty = newQty;
            this.price = price;
            this.status = status;
        }

        // LSN|TIME|TXN_ID|OP|USER|PRODUCT|NAME|PREV_QTY|NEW_QTY|PRICE|STATUS
        public String toLogString() {
            return String.format(Locale.US, "%d|%s|%s|%s|%d|%d|%s|%d|%d|%.2f|%s\n",
                    lsn, timestamp, txnId, opType, userId, productId,
                    productName.replace("|", "/"), prevQty, newQty, price, status);
        }

        public static LogRecord parse(String line) {
            try {
                String[] parts = line.trim().split("\\|", -1);
                if (parts.length < 11) return null;
                return new LogRecord(Long.parseLong(parts[0]), parts[1], parts[2], parts[3],
                        Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), parts[6],
                        Integer.parseInt(parts[7]), Integer.parseInt(parts[8]),
                        Double.parseDouble(parts[9]), parts[10]);
            } catch (Exception e) {
                return null;
            }
        }
    }

    static {
        init();
    }

    public static synchronized void init() {
        try {
            Path dir = Paths.get(LOG_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);

            File file = new File(LOG_FILE);
            boolean alreadyExists = file.exists();
            fileStream = new FileOutputStream(file, true);
            writer = new OutputStreamWriter(fileStream, StandardCharsets.UTF_8);

            if (alreadyExists) {
                recover();
            } else {
                logCheckpoint("INITIAL_WAL_CREATION");
            }
        } catch (IOException e) {
            System.err.println("[WAL Error] Failed to initialize WAL file: " + e.getMessage());
        }
    }

    // ---------- writing to the log ----------
    public static LogRecord append(String txnId, String opType, int userId, int productId,
                                   String productName, int prevQty, int newQty, double price, String status) {
        long lsn = lsnCounter.incrementAndGet();
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        LogRecord record = new LogRecord(lsn, time, txnId, opType, userId, productId,
                productName, prevQty, newQty, price, status);

        synchronized (writeLock) {
            try {
                if (writer != null) {
                    writer.write(record.toLogString());
                    writer.flush();
                    fileStream.getFD().sync();   // force it onto the disk
                }
            } catch (IOException e) {
                System.err.println("[WAL Error] Failed to write log: " + e.getMessage());
            }
        }

        addToRecent(record);
        return record;
    }

    private static void addToRecent(LogRecord record) {
        recentLogs.addFirst(record);
        while (recentLogs.size() > MAX_RECENT_LOGS) {
            recentLogs.removeLast();
        }
    }

    public static LogRecord logBegin(String txnId, int userId, int productId) {
        return append(txnId, "TXN_BEGIN", userId, productId, "", 0, 0, 0, "QUEUED");
    }

    public static LogRecord logReserve(String txnId, int userId, int productId, String productName,
                                       int prevQty, int newQty, double price) {
        return append(txnId, "STOCK_RESERVE", userId, productId, productName, prevQty, newQty, price, "RESERVED");
    }

    public static LogRecord logCommit(String txnId, int userId, int productId, String productName,
                                      int remainingQty, double price) {
        return append(txnId, "ORDER_COMMIT", userId, productId, productName, remainingQty + 1, remainingQty, price, "COMMITTED");
    }

    public static LogRecord logAbort(String txnId, int userId, int productId, String reason) {
        return append(txnId, "TXN_ABORT", userId, productId, "", 0, 0, 0, reason);
    }

    public static synchronized LogRecord logCheckpoint(String note) {
        totalCheckpoints++;
        long lsn = lsnCounter.get();
        lastCheckpointLsn = lsn;
        LogRecord record = append("CHK-" + totalCheckpoints, "CHECKPOINT", 0, 0, note, 0, 0, 0, "SUCCESS");

        try {
            Files.writeString(Paths.get(CHECKPOINT_FILE), "CHECKPOINT_LSN=" + lsn + "\nTIMESTAMP=" + new Date() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {}

        return record;
    }

    // ---------- crash recovery ----------
    public static synchronized int recover() {
        File file = new File(LOG_FILE);
        if (!file.exists() || file.length() == 0) return 0;

        System.out.println("[WAL Recovery] Scanning Write-Ahead Log for uncommitted / committed states...");
        int replayedCount = 0;
        long maxLsn = lsnCounter.get();

        // group all records by transaction id
        Map<String, List<LogRecord>> transactions = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogRecord record = LogRecord.parse(line);
                if (record == null) continue;

                if (record.lsn > maxLsn) maxLsn = record.lsn;
                transactions.computeIfAbsent(record.txnId, k -> new ArrayList<>()).add(record);
                addToRecent(record);
            }
        } catch (IOException e) {
            System.err.println("[WAL Recovery Error] Error reading WAL file: " + e.getMessage());
        }

        lsnCounter.set(maxLsn + 1);

        // only the transactions that have ORDER_COMMIT are replayed
        for (List<LogRecord> records : transactions.values()) {
            boolean committed = records.stream().anyMatch(r -> "ORDER_COMMIT".equals(r.opType));
            if (!committed) continue;
            for (LogRecord record : records) {
                if ("STOCK_RESERVE".equals(record.opType) || "ORDER_COMMIT".equals(record.opType)) {
                    replayedCount++;
                }
            }
        }

        System.out.println("[WAL Recovery] Completed! Active LSN: " + lsnCounter.get() + ", Transactions replayed: " + replayedCount);
        return replayedCount;
    }

    // ---------- getters for admin page ----------
    public static List<LogRecord> getRecentLogs(int limit) {
        List<LogRecord> list = new ArrayList<>(recentLogs);
        if (list.size() > limit) return list.subList(0, limit);
        return list;
    }

    public static long getLogFileSize() {
        File file = new File(LOG_FILE);
        return file.exists() ? file.length() : 0;
    }

    public static long getCurrentLsn() { return lsnCounter.get(); }
    public static long getLastCheckpointLsn() { return lastCheckpointLsn; }
    public static long getTotalCheckpoints() { return totalCheckpoints; }
}
