package vn.goldcast.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Arrays;

/**
 * Turns exceptions into RFC 7807 problem documents.
 *
 * <p>Error responses carry a machine-readable {@code type} and a message written for a
 * person, because most failures here are situational — a series that is too short, a code
 * that does not exist — and the client needs to explain them rather than show a stack trace.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String BASE = "https://goldcast.local/problems/";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail notFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Không tìm thấy", ex.getMessage(), "not-found");
    }

    @ExceptionHandler(InsufficientHistoryException.class)
    public ProblemDetail insufficientHistory(InsufficientHistoryException ex) {
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY,
                "Chưa đủ dữ liệu lịch sử", ex.getMessage(), "insufficient-history");
        detail.setProperty("available", ex.getAvailable());
        detail.setProperty("required", ex.getRequired());
        return detail;
    }

    /**
     * A query parameter that could not be converted — a non-numeric horizon, or a model
     * name that is not in the enum. The valid values are listed, since guessing them from
     * a 400 is unreasonable.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
        String allowed = "";
        Class<?> required = ex.getRequiredType();
        if (required != null && required.isEnum()) {
            allowed = " Giá trị hợp lệ: "
                    + String.join(", ", Arrays.stream(required.getEnumConstants())
                            .map(Object::toString).toList())
                    + ".";
        }
        return problem(HttpStatus.BAD_REQUEST, "Tham số không hợp lệ",
                "Giá trị '" + ex.getValue() + "' không hợp lệ cho tham số '" + ex.getName() + "'."
                        + allowed,
                "invalid-parameter");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraintViolation(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Tham số không hợp lệ", ex.getMessage(),
                "invalid-parameter");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Yêu cầu không hợp lệ", ex.getMessage(),
                "invalid-request");
    }

    /**
     * A request for a path with no handler or static resource behind it.
     *
     * <p>Without this, a browser asking for /favicon.ico would be logged as a server error.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail noResource(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Không tìm thấy",
                "Không có tài nguyên tại đường dẫn này.", "not-found");
    }

    /**
     * Everything else.
     *
     * <p>Spring's own MVC exceptions implement {@link ErrorResponse} and already know the
     * status they deserve — an unsupported method is a 405, not a 500 — so those are passed
     * through with their own body. Only genuinely unexpected failures become a 500, and
     * their message is logged rather than returned: internal detail is not the caller's
     * business.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode()).body(errorResponse.getBody());
        }
        log.error("Lỗi không lường trước: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống",
                        "Đã có lỗi xảy ra phía máy chủ. Vui lòng thử lại sau.", "internal-error"));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE + type));
        problem.setProperty("timestamp", OffsetDateTime.now().toString());
        return problem;
    }
}
