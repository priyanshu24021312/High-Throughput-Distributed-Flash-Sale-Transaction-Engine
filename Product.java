public class Product {

    private final int id;
    private final String name;
    private final String description;
    private final int totalQuantity;
    private final double price;

    // only changed while the lock manager says this product is locked by the caller
    private volatile int available;

    public Product(int id, String name, String description, int totalQuantity, double price) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.totalQuantity = totalQuantity;
        this.available = totalQuantity;
        this.price = price;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getTotalQuantity() { return totalQuantity; }
    public double getPrice() { return price; }
    public int getAvailable() { return available; }

    public void decrement() { available--; }
    public void increment() { available++; }

    public String getStatus() {
        return available == 0 ? "SOLD OUT" : "ACTIVE";
    }
}
