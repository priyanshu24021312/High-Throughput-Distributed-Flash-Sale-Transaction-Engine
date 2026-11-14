import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// B+ Tree index. Key = product id, value = the product.
// All values are kept in the leaf nodes and the leaves are linked to each other,
// so we can also read all products in key order (range scan).
// Many threads can read at the same time, but a writer needs the tree alone (readers-writers lock).
public class BPlusTree<V> {

    private abstract class Node {
        List<Integer> keys = new ArrayList<>();
    }

    private class Leaf extends Node {
        List<V> values = new ArrayList<>();
        Leaf next;
    }

    private class Inner extends Node {
        List<Node> children = new ArrayList<>();
    }

    // result of a split: the key that goes up and the new right node
    private class Split {
        int key;
        Node right;
        Split(int key, Node right) {
            this.key = key;
            this.right = right;
        }
    }

    private final int order;   // max number of keys in one node
    private Node root = new Leaf();
    private int size = 0;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public BPlusTree(int order) {
        this.order = Math.max(3, order);
    }

    // ---------- search ----------
    public V get(int key) {
        lock.readLock().lock();
        try {
            Leaf leaf = findLeaf(key);
            int pos = Collections.binarySearch(leaf.keys, key);
            return pos >= 0 ? leaf.values.get(pos) : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    private Leaf findLeaf(int key) {
        Node node = root;
        while (node instanceof Inner) {
            Inner inner = (Inner) node;
            node = inner.children.get(childIndex(inner, key));
        }
        return (Leaf) node;
    }

    // number of keys that are <= key, this is the child we have to go to
    private int childIndex(Inner inner, int key) {
        int i = 0;
        while (i < inner.keys.size() && key >= inner.keys.get(i)) i++;
        return i;
    }

    // all values in key order, we just walk along the linked leaves
    public List<V> values() {
        lock.readLock().lock();
        try {
            List<V> list = new ArrayList<>();
            Node node = root;
            while (node instanceof Inner) node = ((Inner) node).children.get(0);
            for (Leaf leaf = (Leaf) node; leaf != null; leaf = leaf.next) {
                list.addAll(leaf.values);
            }
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------- insert ----------
    public void put(int key, V value) {
        lock.writeLock().lock();
        try {
            Split split = insert(root, key, value);
            if (split != null) {
                // the root was split, so the tree grows one level
                Inner newRoot = new Inner();
                newRoot.keys.add(split.key);
                newRoot.children.add(root);
                newRoot.children.add(split.right);
                root = newRoot;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Split insert(Node node, int key, V value) {
        if (node instanceof Leaf) {
            Leaf leaf = (Leaf) node;
            int pos = Collections.binarySearch(leaf.keys, key);
            if (pos >= 0) {
                leaf.values.set(pos, value);   // same key, just replace the value
                return null;
            }
            pos = -pos - 1;
            leaf.keys.add(pos, key);
            leaf.values.add(pos, value);
            size++;
            if (leaf.keys.size() <= order) return null;
            // leaf is full, move the right half to a new leaf
            int mid = leaf.keys.size() / 2;
            Leaf right = new Leaf();
            right.keys.addAll(leaf.keys.subList(mid, leaf.keys.size()));
            right.values.addAll(leaf.values.subList(mid, leaf.values.size()));
            leaf.keys.subList(mid, leaf.keys.size()).clear();
            leaf.values.subList(mid, leaf.values.size()).clear();
            right.next = leaf.next;
            leaf.next = right;
            return new Split(right.keys.get(0), right);
        }
        Inner inner = (Inner) node;
        int index = childIndex(inner, key);
        Split split = insert(inner.children.get(index), key, value);
        if (split == null) return null;
        inner.keys.add(index, split.key);
        inner.children.add(index + 1, split.right);
        if (inner.keys.size() <= order) return null;
        // inner node is full, the middle key goes up to the parent
        int mid = inner.keys.size() / 2;
        int upKey = inner.keys.get(mid);
        Inner right = new Inner();
        right.keys.addAll(inner.keys.subList(mid + 1, inner.keys.size()));
        right.children.addAll(inner.children.subList(mid + 1, inner.children.size()));
        inner.keys.subList(mid, inner.keys.size()).clear();
        inner.children.subList(mid + 1, inner.children.size()).clear();
        return new Split(upKey, right);
    }

    // ---------- delete ----------
    // lazy delete: the key is removed from the leaf, the nodes are not merged.
    // Search and range scan still work, the tree is just a bit emptier.
    public V remove(int key) {
        lock.writeLock().lock();
        try {
            Leaf leaf = findLeaf(key);
            int pos = Collections.binarySearch(leaf.keys, key);
            if (pos < 0) return null;
            leaf.keys.remove(pos);
            size--;
            return leaf.values.remove(pos);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            root = new Leaf();
            size = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------- info for the admin page ----------
    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int height() {
        lock.readLock().lock();
        try {
            int levels = 1;
            for (Node node = root; node instanceof Inner; node = ((Inner) node).children.get(0)) levels++;
            return levels;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getOrder() {
        return order;
    }
}
