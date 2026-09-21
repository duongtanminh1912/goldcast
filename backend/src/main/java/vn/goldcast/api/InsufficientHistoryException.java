package vn.goldcast.api;

/**
 * Thrown when a series does not have enough observations for the operation requested.
 *
 * <p>This is deliberately an error rather than a best-effort answer. A forecast fitted to
 * a handful of points is not a weak forecast, it is a meaningless one, and returning it
 * with a wide interval would still put a number on the screen that people would read.
 * Maps to HTTP 422.
 */
public class InsufficientHistoryException extends RuntimeException {

    private final int available;
    private final int required;

    public InsufficientHistoryException(String instrumentCode, int available, int required) {
        super("Chuỗi '" + instrumentCode + "' mới có " + available + " quan sát, cần tối thiểu "
                + required + ". Hãy đợi ingest thu thập thêm dữ liệu.");
        this.available = available;
        this.required = required;
    }

    public int getAvailable() {
        return available;
    }

    public int getRequired() {
        return required;
    }
}
