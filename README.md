# ⚡ FlashDeals — High-Throughput Distributed Flash-Sale Transaction Engine
> **B.Tech Operating Systems + DBMS Project**
> **Key Concepts**: Multithreading, Worker Pool (producer-consumer), Strict Two-Phase Locking, Deadlock Detection (Wait-For Graph) and Prevention, B+ Tree Index, Write-Ahead Logging with Group Commit, Checkpoint and Crash Recovery, ACID.

---

## 📌 Project Overview
In a flash sale thousands of users try to buy a few items at the same second. If it is done naively, many threads read the same stock together (lost update, overselling), the server creates thousands of threads, and two transactions can wait for each other forever (deadlock).

This project handles that in a controlled way:

1. **Worker Pool + Bounded Buffer** (`WorkerPool.java`, `BoundedBuffer.java`): requests go into a queue of 2000 places made with semaphores. A fixed number of worker threads process them. When the queue is full a new request is rejected at once (back-pressure).
2. **Strict Two-Phase Locking** (`LockManager.java`): every product row has a Shared (S) / Exclusive (X) lock. Locks are asked in the growing phase and all released together at the end.
3. **Deadlock Detection and Recovery** (`WaitForGraph.java`): the lock manager reports every wait, a DFS finds cycles, the youngest transaction is the victim, it is rolled back and tried again later.
4. **Deadlock Prevention**: multi-item bookings (cart) take their locks in sorted product id order, so a circle of waiting can never form.
5. **B+ Tree index** (`BPlusTree.java`): products are found by id in a B+ Tree (leaf nodes are linked, so all products can be read in order). Readers-writers lock on the tree.
6. **Write-Ahead Log with Group Commit** (`WAL.java`): every change is logged before the data changes and before the customer gets an answer. Many transactions share one `fsync`.
7. **Checkpoint + Crash Recovery** (`Database.java`): a snapshot is saved at a checkpoint. After a crash the engine loads the snapshot, replays the log (REDO) and gives back the seats of unpaid holds (UNDO).
8. **Seat hold, payment, PNR**: Buy holds the seat for 5 minutes, Pay confirms the order with a PNR, Cancel or timeout gives the seat back (`HoldReaper` thread).
9. **Optional MySQL**: confirmed orders are also saved in MySQL with an ACID transaction and `SELECT ... FOR UPDATE`.

---

## 🧠 OS and DBMS topics used (where to find them)
| Subject | Topic | Where |
|---|---|---|
| OS | Multithreading, thread pool | `WorkerPool.java` |
| OS | Producer-consumer problem with semaphores | `BoundedBuffer.java` |
| OS | Mutual exclusion, monitors (`wait` / `notifyAll`) | `LockManager.java` |
| OS | Readers-writers problem | `BPlusTree.java` (ReadWriteLock), S/X locks in `LockManager.java` |
| OS | Deadlock: 4 conditions, Wait-For Graph, detection, victim, recovery | `WaitForGraph.java`, `DeadlockSimulation.java` |
| OS | Deadlock prevention (break circular wait by resource ordering) | sorted lock order in `Database.hold`, "Sorted order" mode of the simulation |
| OS | Daemon / timer thread | `HoldReaper` thread in `Database.java` |
| OS | File I/O, `fsync`, atomic file replace | `WAL.java`, snapshot in `Database.java` |
| DBMS | ACID (Atomicity: cart is all or nothing, Isolation: 2PL, Durability: WAL) | `Database.java` |
| DBMS | Strict 2PL, lock modes S/X, lock upgrade | `LockManager.java` |
| DBMS | Indexing with B+ Tree (insert with node split, search, range scan) | `BPlusTree.java` |
| DBMS | Write-Ahead Logging, LSN, group commit | `WAL.java` |
| DBMS | Checkpoint, REDO / UNDO recovery | `Database.checkpoint`, `Database.recover` |
| DBMS | Row level locking with `SELECT ... FOR UPDATE` | `Database.saveConfirmedOrder` (MySQL) |

---

## 🎨 User Interface
- **Customer Storefront**: live deals, filter by category, **Book Ticket** holds the seat, **payment screen with 5 minute countdown**, receipt with **PNR**. Tick several products and use **Book Selected** to book them together (all or nothing).
- **Admin Dashboard** (PIN `admin123`):
  - Products: add, **edit**, delete, restock.
  - Orders ledger: status (CONFIRMED, PENDING, PAYMENT_FAILED, EXPIRED), PNR, CSV download.
  - **Engine Core** tab: Wait-For Graph and **Run Flash Sale Threads** (deadlock or sorted-order mode), **Performance** numbers, **High Traffic Burst Test**, **Crash Recovery** button, WorkerPool and WAL consoles.

---

## 🚀 How to Run
Put `mysql-connector-j-26.7.0.jar` in this folder if you want MySQL (it is optional, without MySQL the in-memory engine is used).

**Windows:** double-click `start_server.bat`.

**Terminal:**
```
javac -cp ".;mysql-connector-j-26.7.0.jar" *.java
java -cp ".;mysql-connector-j-26.7.0.jar" WebServer 8080
```
(use `:` instead of `;` on Linux / Mac, or run `./start_server.sh`)

Open `http://localhost:8080`. For MySQL run `database.sql` once (the engine also creates the tables if they are missing).

Delete the `data/` folder if you want a fresh start (it keeps the WAL and the snapshot).

---

## 🧪 Things to show in the demo
1. **Overselling test**: Engine Core -> *High Traffic Burst Test* with 1000 users on a product with 30 units: exactly 30 booked, nobody over-sold, speed and peak queue are shown. With 5000 users the queue (2000) fills and extra requests are rejected.
2. **Deadlock**: *Run Flash Sale Threads* with "Any order": red cycle, alert, *Resolve Deadlock*, the victim is rolled back and tries again. Then run "Sorted order": the same buyers never deadlock.
3. **Hold / Pay / Expiry**: book a ticket, watch the timer; start the server with `java -Dhold.seconds=20 ...` to see an expiry quickly.
4. **Crash recovery**: do some bookings, press *Trigger Checkpoint*, do more bookings, press *Simulate Crash & Recover*: before and after are identical. You can also stop the server with `kill -9` / Task Manager and start it again.
5. **Group commit**: Performance card shows records per fsync (many records are written with one fsync).

---

## 🎓 Viva / Presentation Guide
1. **How do you stop two users from buying the last ticket together?**
   Every product row has an X lock in the Lock Manager (strict 2PL). The buyer checks the stock, writes the log and reduces the stock while holding the lock. Others wait. In MySQL the same is done with `SELECT ... FOR UPDATE`.
2. **Why a worker pool and a bounded queue?**
   A new thread for every request crashes the server in a traffic spike. We use a fixed number of workers (consumers) and a queue made with 3 semaphores (empty, full, mutex). If the queue is full we reject at once, so the server is never overloaded.
3. **What is strict 2PL?**
   Growing phase: the transaction only takes locks. Shrinking phase: all locks are released together when the transaction ends (commit or abort). That gives serializable schedules and no dirty reads.
4. **What is a Wait-For Graph?**
   Every transaction is a node, an edge A -> B means A waits for a lock held by B. After each new wait we run DFS, a cycle means deadlock. The youngest transaction in the cycle is aborted (its locks are released), the others continue, and the victim is retried.
5. **How do you prevent deadlock?**
   By breaking circular wait: a cart takes its product locks in sorted product id order. Two carts [2,3] and [3,2] both ask for 2 first, so they can not wait for each other in a circle. The admin simulation shows both modes.
6. **What is WAL and group commit?**
   Log first, data second. A record is on the disk before the customer is told "confirmed". Many threads put records in a buffer and one thread writes them with one `fsync`, so the slow disk write is shared. The log order is the same as the lock order.
7. **How does crash recovery work?**
   At a checkpoint the engine saves a snapshot and its LSN. After a crash: load the snapshot, REDO the log records after that LSN in order, then UNDO the holds that were never paid (their seats go back). The Crash Recovery button shows the state before and after, they are identical.
8. **Why a B+ Tree?**
   All values are in the leaves, leaves are linked, so a search is O(log n) and a range scan (list all products in order) just walks the leaves. A read-write lock lets many readers search together.
9. **What is the readers-writers problem here?**
   Reading the stock for a report takes an S lock (many readers together), changing it takes an X lock (alone). The same idea is used for the B+ Tree.
