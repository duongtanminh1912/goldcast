package vn.goldcast.ingest;

public class IngestAlreadyRunningException extends RuntimeException {
    public IngestAlreadyRunningException() {
        super("Một lượt thu thập dữ liệu đang chạy. Vui lòng thử lại sau.");
    }
}
