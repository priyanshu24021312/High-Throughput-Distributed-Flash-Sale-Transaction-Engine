import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        System.out.println("========================================");
        System.out.println("       FLASH SALE DISTRIBUTION SYSTEM");
        System.out.println("========================================");

        // ADMIN SIDE
        System.out.println("\n--- ADMIN: ADD PRODUCT ---");

        System.out.print("Enter Product/Event Name: ");
        String name = sc.nextLine();

        System.out.print("Enter Description: ");
        String description = sc.nextLine();

        System.out.print("Enter Total Tickets: ");
        int totalTickets = sc.nextInt();

        System.out.print("Enter Ticket Price: ");
        double price = sc.nextDouble();

        int productId = Database.addProduct(
                name, description, totalTickets, price);

        if (productId == -1) {
            System.out.println("Product could not be created.");
            sc.close();
            return;
        }

        System.out.println("\nProduct created successfully.");
        System.out.println("Product ID: " + productId);

        // CONCURRENT USERS
        System.out.print("\nEnter number of concurrent users: ");
        int numberOfUsers = sc.nextInt();

        System.out.println("\nStarting flash sale...");
        System.out.println("Creating " + numberOfUsers + " threads...");

        UserThread[] users = new UserThread[numberOfUsers];

        long startTime = System.currentTimeMillis();

        // Create threads
        for (int i = 0; i < numberOfUsers; i++) {
            users[i] = new UserThread(i + 1, productId);
        }

        System.out.println("Threads Created: " + numberOfUsers);

        // Start threads
        for (UserThread user : users) {
            user.start();
        }

        // Wait for all threads to complete
        for (UserThread user : users) {
            try {
                user.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long endTime = System.currentTimeMillis();

        // Calculate results
        int successful = 0;

        for (UserThread user : users) {
            if (user.isSuccess()) {
                successful++;
            }
        }

        int failed = numberOfUsers - successful;
        int remaining = Database.getAvailableQuantity(productId);
        long executionTime = endTime - startTime;

        // FINAL REPORT
        System.out.println("\n\n========================================");
        System.out.println("       FLASH SALE DISTRIBUTION SYSTEM");
        System.out.println("========================================");

        System.out.println("\nProduct             : " + name);
        System.out.println("Ticket Price        : Rs." + price);
        System.out.println("Total Tickets       : " + totalTickets);
        System.out.println("Concurrent Users    : " + numberOfUsers);
        System.out.println("Threads Created     : " + numberOfUsers);
        System.out.println("Successful Purchases: " + successful);
        System.out.println("Failed Purchases    : " + failed);
        System.out.println("Remaining Tickets   : " + remaining);

        if (successful <= totalTickets && remaining >= 0) {
            System.out.println("Database Status     : CONSISTENT");
        } else {
            System.out.println("Database Status     : INCONSISTENT");
        }

        System.out.println("Transaction Status  : COMPLETED");
        System.out.println("Execution Time      : " + executionTime + " ms");

        if (remaining == 0) {
            System.out.println("Sale Status         : SOLD OUT");
        } else {
            System.out.println("Sale Status         : AVAILABLE");
        }

        System.out.println("\n========================================");

        sc.close();
    }
}
