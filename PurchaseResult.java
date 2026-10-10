public class PurchaseResult {

    public enum Status {
        CONFIRMED,
        SOLD_OUT,
        PAYMENT_FAILED,
        HOLD_EXPIRED,
        DEADLOCK_ABORTED,
        ERROR
    }

    public final Status status;
    public final String pnr;      // only set when CONFIRMED
    public final String message;

    public PurchaseResult(Status status, String pnr, String message) {
        this.status = status;
        this.pnr = pnr;
        this.message = message;
    }

    @Override
    public String toString() {
        return pnr == null ? status + " (" + message + ")" : status + " PNR=" + pnr;
    }
}
