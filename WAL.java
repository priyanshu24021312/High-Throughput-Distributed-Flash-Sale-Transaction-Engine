import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

// Write-Ahead Log (WAL)
// Every change is first written to a log file on disk, an order is confirmed only after its record is on the disk.
// Group commit: many threads add records to a buffer and one thread writes them all with a single fsync,
// so the slow disk flush is shared between many transactions.
public class WAL {

    private static final String LOG_DIR = "data";
    private static final String LOG_FILE = "data/wal.log";
    private static final String CHECKPOINT_FILE = "data/checkpoint.meta";

    private static final AtomicLong lsnCounter = new AtomicLong(1000);
    private static final Object bufferLock = new Object();   // protects pending list and LSN numbers
    private static final Object flushLock = new Object();    // only one thread writes to the disk at a time
    private static final List<LogRecord> pending = new ArrayList<>();   // added, but not on the disk yet
    private static FileOutputStream fileStream = null;
    private static OutputStreamWriter writer = null;
    private static volatile long flushedLsn = 0;

    // last logs kept in memory for the admin page
    private static final ConcurrentLinkedDeque<LogRecord> recentLogs = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_LOGS = 150;

    private static volatile long lastCheckpointLsn = 0;
    private static volatile long totalCheckpoints = 0;
    private static volatile long totalFlushes = 0;
    private static volatile long flushedRecords = 0;

    public static class LogRecord {
        public long lsn;
        public String timestamp;
        public String txnId;
        public String opType;   // TXN_BEGIN, STOCK_RESERVE, ORDER_COMMIT, TXN_ABORT, CHECKPOINT, PRODUCT_CREATE ...
        public int userId;
        public int productId;
        public String productName;
        public int prevQty;
        public int newQty;
        public double price;
        public String status;
        public String details;   // extra data as key=value pairs, needed to redo the change after a crash

        public LogRecord(long lsn, String timestamp, String txnId, String opType, int userId, int productId,
                         String productName, int prevQty, int newQty, double price, String status, String details) {
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
            this.details = details != null ? details : "";
        }

        // LSN|TIME|TXN_ID|OP|USER|PRODUCT|NAME|PREV_QTY|NEW_QTY|PRICE|STATUS|DETAILS
        public String toLogString() {
            return String.format(Locale.US, "%d|%s|%s|%s|%d|%d|%s|%d|%d|%.2f|%s|%s\n",
                    lsn, timestamp, txnId, opType, userId, productId,
                    productName.replace("|", "/").replace("\n", " "), prevQty, newQty, price, status, details);
        }

        // old log lines have 11 parts (no details), they are still readable
        public static LogRecord parse(String line) {
            try {
                String[] parts = line.trim().split("\\|", -1);
                if (parts.length < 11) return null;
                return new LogRecord(Long.parseLong(parts[0]), parts[1], parts[2], parts[3],
                        Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), parts[6],
                        Integer.parseInt(parts[7]), Integer.parseInt(parts[8]),
                        Double.parseDouble(parts[9]), parts[10], parts.length > 11 ? parts[11] : "");
            } catch (Exception e) {
                return null;
            }
        }
    }

    static {
        init();
    }

    public static synchronized void init() {
        if (writer != null) return;   // already started
        try {
            Path dir = Paths.get(LOG_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            File file = new File(LOG_FILE);
            boolean alreadyExists = file.exists() && file.length() > 0;
            fileStream = new FileOutputStream(file, true);
            writer = new OutputStreamWriter(fileStream, StandardCharsets.UTF_8);

            if (alreadyExists) {
                List<LogRecord> all = readAll();
                long maxLsn = lsnCounter.get();
                for (LogRecord record : all) {
                    if (record.lsn > maxLsn) maxLsn = record.lsn;
                }
                lsnCounter.set(maxLsn);
                flushedLsn = maxLsn;
                int from = Math.max(0, all.size() - MAX_RECENT_LOGS);
                for (int i = from; i < all.size(); i++) addToRecent(all.get(i));
                System.out.println("[WAL] Found old log with " + all.size() + " records, next LSN is " + (maxLsn + 1));
            } else {
                logCheckpoint("INITIAL_WAL_CREATION");
            }
        } catch (IOException e) {
            System.err.println("[WAL Error] Failed to initialize WAL file: " + e.getMessage());
        }
    }

    // ---------- writing to the log ----------
    // adds the record to the buffer, it is NOT on the disk yet (call flush for that)
    public static LogRecord append(String txnId, String opType, int userId, int productId, String productName,
                                   int prevQty, int newQty, double price, String status, String details) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        LogRecord record;
        synchronized (bufferLock) {
            // the LSN is given inside the lock, so the order in the buffer is the same as the LSN order
            record = new LogRecord(lsnCounter.incrementAndGet(), time, txnId, opType, userId, productId,
                    productName, prevQty, newQty, price, status, details);
            pending.add(record);
        }
        addToRecent(record);
        return record;
    }

    // adds the record and waits until it is on the disk
    public static LogRecord appendNow(String txnId, String opType, int userId, int productId, String productName,
                                      int prevQty, int newQty, double price, String status, String details) {
        LogRecord record = append(txnId, opType, userId, productId, productName, prevQty, newQty, price, status, details);
        flush(record.lsn);
        return record;
    }

    // group commit: make sure that the record with this LSN is on the disk
    public static void flush(long lsn) {
        synchronized (flushLock) {
            if (flushedLsn >= lsn) return;   // another thread already wrote it together with its own batch
            List<LogRecord> batch;
            synchronized (bufferLock) {
                batch = new ArrayList<>(pending);
                pending.clear();
            }
            if (batch.isEmpty()) return;
            try {
                StringBuilder text = new StringBuilder();
                for (LogRecord record : batch) text.append(record.toLogString());
                if (writer != null) {
                    writer.write(text.toString());
                    writer.flush();
                    fileStream.getFD().sync();   // force it onto the disk
                }
            } catch (IOException e) {
                System.err.println("[WAL Error] Failed to write log: " + e.getMessage());
            }
            flushedLsn = batch.get(batch.size() - 1).lsn;
            totalFlushes++;
            flushedRecords += batch.size();
        }
    }

    private static void addToRecent(LogRecord record) {
        recentLogs.addFirst(record);
        while (recentLogs.size() > MAX_RECENT_LOGS) {
            recentLogs.removeLast();
        }
    }

    public static LogRecord logBegin(String txnId, int userId, int productId) {
        return append(txnId, "TXN_BEGIN", userId, productId, "", 0, 0, 0, "QUEUED", "");
    }

    public static LogRecord logAbort(String txnId, int userId, int productId, String reason) {
        return append(txnId, "TXN_ABORT", userId, productId, "", 0, 0, 0, reason, "");
    }

    public static synchronized LogRecord logCheckpoint(String note) {
        totalCheckpoints++;
        LogRecord record = appendNow("CHK-" + totalCheckpoints, "CHECKPOINT", 0, 0, note, 0, 0, 0, "SUCCESS", "");
        lastCheckpointLsn = record.lsn;
        try {
            Files.writeString(Paths.get(CHECKPOINT_FILE), "CHECKPOINT_LSN=" + record.lsn + "\nTIMESTAMP=" + new Date() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {}
        return record;
    }

    // ---------- reading the log (used by crash recovery) ----------
    public static List<LogRecord> readAll() {
        List<LogRecord> list = new ArrayList<>();
        File file = new File(LOG_FILE);
        if (!file.exists()) return list;
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogRecord record = LogRecord.parse(line);
                if (record != null) list.add(record);
            }
        } catch (IOException e) {
            System.err.println("[WAL Error] Error reading WAL file: " + e.getMessage());
        }
        list.sort(Comparator.comparingLong(r -> r.lsn));
        return list;
    }

    // "a=1;b=text" <-> map. The values are URL encoded so they never contain | ; = or new lines
    public static String pack(Object... keyAndValue) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i + 1 < keyAndValue.length; i += 2) {
            if (text.length() > 0) text.append(';');
            text.append(keyAndValue[i]).append('=').append(URLEncoder.encode(String.valueOf(keyAndValue[i + 1]), StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    public static Map<String, String> unpack(String details) {
        Map<String, String> map = new HashMap<>();
        if (details == null || details.isEmpty()) return map;
        for (String part : details.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return map;
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
    public static long getTotalFlushes() { return totalFlushes; }
    public static long getFlushedRecords() { return flushedRecords; }
}
