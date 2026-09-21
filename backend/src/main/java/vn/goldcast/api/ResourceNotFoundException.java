package vn.goldcast.api;

/** Thrown when a requested instrument or record does not exist. Maps to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException instrument(String code) {
        return new ResourceNotFoundException("Không tìm thấy instrument có mã '" + code + "'");
    }
}
