import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Append-only log. Each line looks like
 *   lsn|timestamp|TYPE|fields...|END
 * and force() is called after every write so the data really hits the disk.
 * The END marker lets recovery skip a line that was only half written.
 */
public class WriteAheadLog implements Closeable {

    private final FileChannel channel;
    private long lsn = 0;

    public WriteAheadLog(String path, boolean startFresh) {
        try {
            Path p = Paths.get(path);
            if (startFresh) {
                Files.deleteIfExists(p);
            }
            channel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void append(String record) {
        lsn++;
        String line = lsn + "|" + System.currentTimeMillis() + "|" + record + "|END\n";
        try {
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            channel.force(false);   // fsync
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            // nothing useful to do here
        }
    }

    /**
     * Rebuilds available stock from the log alone:
     * start from every PRODUCT record, then subtract one for each COMMIT.
     * Holds that were never committed are not in the log, so they just vanish (= rollback).
     */
    public static Map<Integer, Integer> replay(String path) {
        Map<Integer, Integer> stock = new TreeMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (String line : lines) {
            if (!line.endsWith("|END")) {
                continue;   // torn write at the tail, ignore
            }
            try {
                String[] f = line.split("\\|");
                String type = f[2];
                if (type.equals("PRODUCT")) {
                    stock.put(Integer.parseInt(f[3]), Integer.parseInt(f[4]));
                } else if (type.equals("COMMIT")) {
                    for (String id : f[5].split(",")) {
                        stock.merge(Integer.parseInt(id), -1, Integer::sum);
                    }
                }
            } catch (RuntimeException e) {
                // garbage line, skip it
            }
        }
        return stock;
    }
}
