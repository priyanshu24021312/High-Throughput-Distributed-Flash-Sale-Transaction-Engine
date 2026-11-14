import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("========================================");
        System.out.println("       FLASH SALE DISTRIBUTION SYSTEM");
        System.out.println("========================================");

        // admin adds the product
        System.out.println("\n--- ADMIN: ADD PRODUCT ---");
        System.out.print("Enter Product/Event Name: ");
        String name = scanner.nextLine();
        System.out.print("Enter Description: ");
        String description = scanner.nextLine();
        System.out.print("Enter Total Tickets: ");
        int totalTickets = scanner.nextInt();
        System.out.print("Enter Ticket Price: ");
        double price = scanner.nextDouble();

        int productId = Database.addProduct(name, description, totalTickets, price);
        if (productId == -1) {
            System.out.println("Product could not be created.");
            scanner.close();
            return;
        }
        System.out.println("\nProduct created successfully.");
        System.out.println("Product ID: " + productId);

        // users
        System.out.print("\nEnter number of concurrent users: ");
        int numberOfUsers = scanner.nextInt();

        System.out.println("\nStarting flash sale...");
        System.out.println("Creating " + numberOfUsers + " threads...");

        UserThread[] users = new UserThread[numberOfUsers];
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numberOfUsers; i++) {
            users[i] = new UserThread(i + 1, productId);
        }
        System.out.println("Threads Created: " + numberOfUsers);

        for (UserThread user : users) {
            user.start();
        }

        // wait for all threads to finish
        for (UserThread user : users) {
            try {
                user.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;

        int successful = 0;
        for (UserThread user : users) {
            if (user.isSuccess()) successful++;
        }
        int failed = numberOfUsers - successful;
        int remaining = Database.getAvailableQuantity(productId);   // read with a shared lock

        // final report
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

        if (successful <= totalTickets && remaining >= 0 && successful + remaining == totalTickets) {
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

        // optional: show the deadlock (Wait-For Graph) on this product
        System.out.print("\nRun deadlock simulation on this product? (y/n): ");
        if (scanner.next().equalsIgnoreCase("y")) {
            runDeadlockSimulation(productId);
        }
        scanner.close();
    }

    // 3 buyer threads wait for each other's locks, the Wait-For Graph finds the cycle
    private static void runDeadlockSimulation(int productId) {
        System.out.println("\nStarting 3 buyer threads that wait for each other...");
        String problem = DeadlockSimulation.start(productId, 3, 3, false);   // resolves itself after 3 seconds
        if (problem != null) {
            System.out.println(problem);
            return;
        }
        DeadlockSimulation.waitUntilDone();

        System.out.println("\nDeadlock cycle : " + DeadlockSimulation.getCycleText());
        System.out.println("Victim         : " + DeadlockSimulation.getVictim());
        for (DeadlockSimulation.Buyer buyer : DeadlockSimulation.getBuyers()) {
            System.out.println(buyer.name + " -> " + buyer.state);
        }
    }
}
