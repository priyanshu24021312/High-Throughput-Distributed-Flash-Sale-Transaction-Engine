# ⚡ FlashDeals — Flash Sale & Ticket Booking System
> **B.Tech 5th Semester Computer Science / IT Project**  
> **Key Concepts**: Concurrency Control, Mutual Exclusion, WorkerPool Architecture, Write-Ahead Logging (WAL), ACID Transactions.

---

## 📌 Project Overview
Flash sales (like booking Tatkal tickets, flight flash drops, or high-demand smartwatches) experience thousands of users attempting to purchase limited stock simultaneously.

If implemented naively:
$$\text{Stock} = \text{Stock} - 1$$
Multiple threads read the same stock at the same millisecond, leading to **Lost Updates** and **Overselling** (e.g., selling 35 tickets when only 25 exist).

This project implements:
1. **Application-Level Synchronization** (`synchronized`) & **Database Row Locks** (`SELECT ... FOR UPDATE`).
2. **WorkerPool Algorithm**: Uses a bounded `ArrayBlockingQueue` with dedicated worker threads to handle spikes and prevent thread starvation.
3. **Write-Ahead Logging (WAL) Algorithm**: Logs every transaction to `data/wal.log` with synchronous disk flushing (`fsync`) before memory state changes, guaranteeing **zero data loss** on crashes.

---

## 🎨 User Interface (Humanized & Clean)
- **Design Theme**: Clean Off-White (`#FAF8F5`) and warm Orange (`#FF6B00`).
- **Currency**: Native Indian Rupees (**₹**) (e.g. `₹2,999.00`, `₹1,750.00`).
- **Product Categories**:
  - ✈️ **Travel Tickets** (Goa Flights, Vande Bharat Express, Music Festival VIP passes)
  - ⌚ **Wearables** (Titan Smartwatch, Noise Smartwatch, Ultrahuman Titanium Smart Ring)
  - ⚡ **Electronics** (PlayStation 5 Pro)
- **Role Separation**:
  - **Customer Storefront**: View live deals, filter by category, instant 1-click booking, digital receipt modal.
  - **Admin Dashboard (Protected)**: Requires PIN `admin123`. Allows adding new items, managing quotas, resetting stock, downloading customer orders CSV, and inspecting real-time WorkerPool & WAL logs.

---

## 🚀 How to Run the Project

### Option 1: Double-Click Launcher (Windows)
Double-click `start_server.bat` in the project root:
```powershell
.\start_server.bat
```
This automatically compiles the Java files, frees the port, and opens **`http://localhost:8080`** in your browser.

### Option 2: Run via Terminal
```powershell
cd FlashSaleProject
javac -cp ".;mysql-connector-j-26.7.0.jar" *.java
java -cp ".;mysql-connector-j-26.7.0.jar" WebServer 8080
```

- **Customer View**: [http://localhost:8080](http://localhost:8080)
- **Admin Panel**: Click **`🔒 Admin Login`** in top-right (Default PIN: `admin123`).

---

## 🎓 Viva / Presentation Guide for Students

When your professor asks about the project:
1. **"How do you prevent two users from buying the last ticket simultaneously?"**
   - *Answer:* "We use a multi-tiered lock. At the application layer, the reservation method is synchronized. At the database layer, we execute `SELECT available_quantity FROM product WHERE product_id = ? FOR UPDATE`, which places an exclusive row lock on the record until the transaction commits."
2. **"What is the WorkerPool algorithm and why did you use it?"**
   - *Answer:* "Creating an unbounded thread for every HTTP request crashes the JVM during flash traffic spikes (`OutOfMemoryError`). Our `WorkerPool` uses a fixed set of dedicated worker threads pulling from a bounded queue (`ArrayBlockingQueue`), smoothing traffic spikes and applying backpressure safely."
3. **"What is Write-Ahead Logging (WAL)?"**
   - *Answer:* "WAL enforces the Durability property of ACID. Before we decrement in-memory stock, the worker thread appends an append-only log record (`TXN_BEGIN`, `STOCK_RESERVE`, `ORDER_COMMIT`) to `data/wal.log` with `sync()`. If the server crashes or loses power, our startup recovery function scans the log and replays all committed transactions."
