import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Small in-memory B+ tree (int key -> value) used as the product index.
 * Values only live in the leaves and the leaves are chained together.
 * Order is kept tiny on purpose so splits actually happen in the demo.
 */
public class BPlusTree<V> {

    private static final int MAX_KEYS = 3;

    private class Node {
        final boolean leaf;
        final List<Integer> keys = new ArrayList<>();
        final List<Node> children = new ArrayList<>();   // internal nodes only
        final List<V> values = new ArrayList<>();        // leaves only
        Node next;                                       // next leaf

        Node(boolean leaf) {
            this.leaf = leaf;
        }
    }

    // what a child hands back to its parent when it had to split
    private class Split {
        final int key;
        final Node right;

        Split(int key, Node right) {
            this.key = key;
            this.right = right;
        }
    }

    private Node root = new Node(true);
    private int size = 0;
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();

    public void put(int key, V value) {
        rw.writeLock().lock();
        try {
            Split s = insert(root, key, value);
            if (s != null) {
                Node newRoot = new Node(false);
                newRoot.keys.add(s.key);
                newRoot.children.add(root);
                newRoot.children.add(s.right);
                root = newRoot;
            }
        } finally {
            rw.writeLock().unlock();
        }
    }

    public V get(int key) {
        rw.readLock().lock();
        try {
            Node n = root;
            while (!n.leaf) {
                n = n.children.get(childIndex(n, key));
            }
            int pos = Collections.binarySearch(n.keys, key);
            return pos >= 0 ? n.values.get(pos) : null;
        } finally {
            rw.readLock().unlock();
        }
    }

    public int size() {
        rw.readLock().lock();
        try {
            return size;
        } finally {
            rw.readLock().unlock();
        }
    }

    // walks the leaf chain, handy for checking the tree is sorted
    public List<Integer> keysInOrder() {
        rw.readLock().lock();
        try {
            Node n = root;
            while (!n.leaf) {
                n = n.children.get(0);
            }
            List<Integer> out = new ArrayList<>();
            while (n != null) {
                out.addAll(n.keys);
                n = n.next;
            }
            return out;
        } finally {
            rw.readLock().unlock();
        }
    }

    private int childIndex(Node n, int key) {
        int i = 0;
        while (i < n.keys.size() && key >= n.keys.get(i)) {
            i++;
        }
        return i;
    }

    private Split insert(Node node, int key, V value) {
        if (node.leaf) {
            int pos = Collections.binarySearch(node.keys, key);
            if (pos >= 0) {
                node.values.set(pos, value);
                return null;
            }
            pos = -pos - 1;
            node.keys.add(pos, key);
            node.values.add(pos, value);
            size++;

            if (node.keys.size() <= MAX_KEYS) {
                return null;
            }

            int mid = node.keys.size() / 2;
            Node right = new Node(true);
            right.keys.addAll(node.keys.subList(mid, node.keys.size()));
            right.values.addAll(node.values.subList(mid, node.values.size()));
            node.keys.subList(mid, node.keys.size()).clear();
            node.values.subList(mid, node.values.size()).clear();

            right.next = node.next;
            node.next = right;
            return new Split(right.keys.get(0), right);
        }

        int idx = childIndex(node, key);
        Split s = insert(node.children.get(idx), key, value);
        if (s == null) {
            return null;
        }

        node.keys.add(idx, s.key);
        node.children.add(idx + 1, s.right);

        if (node.keys.size() <= MAX_KEYS) {
            return null;
        }

        int mid = node.keys.size() / 2;
        int upKey = node.keys.get(mid);
        Node right = new Node(false);
        right.keys.addAll(node.keys.subList(mid + 1, node.keys.size()));
        right.children.addAll(node.children.subList(mid + 1, node.children.size()));
        node.keys.subList(mid, node.keys.size()).clear();
        node.children.subList(mid + 1, node.children.size()).clear();
        return new Split(upKey, right);
    }
}
