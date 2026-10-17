import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Fixed set of worker threads started once at boot, all pulling from one shared queue.
 * No matter how many requests come in, only `size` of them run at the same time.
 */
public class WorkerPool {

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread[] workers;
    private volatile boolean running = true;

    public WorkerPool(int size) {
        workers = new Thread[size];
        for (int i = 0; i < size; i++) {
            workers[i] = new Thread(this::loop, "worker-" + (i + 1));
            workers[i].start();
        }
    }

    public void submit(Runnable task) {
        if (!running) {
            throw new RejectedExecutionException("pool is shut down");
        }
        queue.add(task);
    }

    public int queued() {
        return queue.size();
    }

    private void loop() {
        while (running || !queue.isEmpty()) {
            try {
                Runnable task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                System.out.println("[" + Thread.currentThread().getName() + "] task crashed: " + e);
            }
        }
    }

    public void shutdown() {
        running = false;
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
