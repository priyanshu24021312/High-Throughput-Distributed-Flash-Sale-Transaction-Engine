public class UserThread extends Thread {

    private final int userId;
    private final int productId;
    private boolean success;
    private long latencyMs;

    public UserThread(int userId, int productId) {
        this.userId = userId;
        this.productId = productId;
        setName("UserThread-" + userId);
    }

    @Override
    public void run() {
        long start = System.currentTimeMillis();
        success = FlashSale.purchase(userId, productId);
        latencyMs = System.currentTimeMillis() - start;

        if (success) {
            System.out.println(getName() + " -> SUCCESS (" + latencyMs + "ms)");
        } else {
            System.out.println(getName() + " -> SOLD OUT (" + latencyMs + "ms)");
        }
    }

    public boolean isSuccess() {
        return success;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public int getUserId() {
        return userId;
    }

    public int getProductId() {
        return productId;
    }
}
