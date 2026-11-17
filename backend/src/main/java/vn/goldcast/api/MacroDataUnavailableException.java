package vn.goldcast.api;

/**
 * ARX_MACRO was requested but cannot be fitted for this series: it is not world spot
 * gold, or the macro drivers are missing or too stale. Maps to HTTP 422, like
 * {@link InsufficientHistoryException}, rather than silently using another model.
 */
public class MacroDataUnavailableException extends RuntimeException {

    public MacroDataUnavailableException(String message) {
        super(message);
    }
}
