package dev.ledgerline;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return error(e.status(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        var field = e.getBindingResult().getFieldError();
        String detail = field == null ? "invalid_request" : field.getField() + " " + field.getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of("error", "validation_failed", "detail", detail));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "bad_request");
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, Object>> upstream(RestClientException e) {
        log.warn("stripe call failed: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "stripe_error");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
