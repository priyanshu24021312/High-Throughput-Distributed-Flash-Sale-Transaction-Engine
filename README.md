# ⚡ FlashDeals — Flash Sale & Ticket Booking System

**B.Tech 5th Semester Computer Science / IT Project**

**Key Concepts:** Concurrency Control, Mutual Exclusion, WorkerPool Architecture, Write-Ahead Logging (WAL), ACID Transactions.

---

## 📌 Project Overview

Flash sales, such as ticket bookings and limited-stock product deals, can involve thousands of users trying to purchase items simultaneously.

Without proper synchronization, concurrent requests can cause lost updates and overselling. For example, the system might sell more tickets than are available.

This project addresses these problems using:

1. **Concurrency Control:** Java synchronization and database row locking.
2. **WorkerPool Architecture:** A bounded `ArrayBlockingQueue` and dedicated worker threads to manage incoming requests.
3. **Write-Ahead Logging (WAL):** Transaction logging and recovery to help maintain data consistency after a crash.

## 🎨 User Interface

- **Design:** Off-white (`#FAF8F5`) and warm orange (`#FF6B00`).
- **Currency:** Indian Rupees (₹).
- **Product Categories:** Travel tickets, wearables and electronics.
- **Customer Storefront:** Browse deals, filter products, book items and view receipts.
- **Admin Dashboard:** Manage products and stock, view orders, download order data and inspect system logs.
- **Admin PIN:** `admin123` (default demonstration PIN).

## 🚀 How to Run the Project

### Option 1: Windows Launcher

Run the following command from the project root:

```powershell
.\start_server.bat
```

### Option 2: Run Using Java

Open the project directory containing the Java source files and MySQL Connector JAR, then run:

```powershell
javac -cp ".;mysql-connector-j-26.7.0.jar" *.java
java -cp ".;mysql-connector-j-26.7.0.jar" WebServer 8080
```

Open `http://localhost:8080` in your browser.

**Customer View:** Browse available deals and make bookings.

**Admin Panel:** Use the Admin Login option. The default demonstration PIN is `admin123`.

## 🎓 Viva / Presentation Guide

**1. How do you prevent two users from buying the last item simultaneously?**

Synchronization and database row locking help ensure that concurrent purchases do not reserve the same available stock.

**2. Why is a WorkerPool used?**

A worker pool reuses a fixed number of threads and a bounded queue to manage incoming requests and reduce the overhead of creating a new thread for every request.

**3. What is Write-Ahead Logging (WAL)?**

WAL records transaction information before the corresponding changes are considered durable. During recovery, the log can help restore a consistent state after a failure.

---

**Project:** High-Throughput Distributed Flash-Sale Transaction Engine
