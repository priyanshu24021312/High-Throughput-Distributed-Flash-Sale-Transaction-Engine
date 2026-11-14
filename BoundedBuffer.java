import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

// Bounded buffer (producer-consumer problem) made with 3 semaphores:
//   empty = free places, full = filled places, mutex = only one thread changes the buffer at a time.
// Producers are the web threads, consumers are the worker threads.
public class BoundedBuffer<T> {

    private final Object[] items;
    private final Semaphore empty;
    private final Semaphore full = new Semaphore(0);
    private final Semaphore mutex = new Semaphore(1);
    private int in = 0;    // where the next item is put
    private int out = 0;   // where the next item is taken

    public BoundedBuffer(int capacity) {
        items = new Object[capacity];
        empty = new Semaphore(capacity);
    }

    // does not wait: if there is no free place the request is rejected (back-pressure)
    public boolean offer(T item) {
        if (!empty.tryAcquire()) return false;
        mutex.acquireUninterruptibly();
        items[in] = item;
        in = (in + 1) % items.length;
        mutex.release();
        full.release();
        return true;
    }

    // waits at most waitMs for an item, returns null if nothing came
    @SuppressWarnings("unchecked")
    public T poll(long waitMs) throws InterruptedException {
        if (!full.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return null;
        mutex.acquireUninterruptibly();
        T item = (T) items[out];
        items[out] = null;
        out = (out + 1) % items.length;
        mutex.release();
        empty.release();
        return item;
    }

    public int size() {
        return full.availablePermits();
    }

    public int capacity() {
        return items.length;
    }
}
