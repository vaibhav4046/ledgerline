package dev.ledgerline;

import org.springframework.http.HttpStatus;

/** An error with an HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {
    private static final int MAX_KEY_LENGTH = 255;

    private final HttpStatus status;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
        return key;
    }
}
