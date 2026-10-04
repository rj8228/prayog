package dev.prayog.exchange.app.api;

import dev.prayog.exchange.app.api.ApiTypes.ErrorBody;
import dev.prayog.exchange.app.core.ExchangeBusyException;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

/** Maps failures to clear HTTP answers with a JSON body {@code {"error", "message"}}. */
@RestControllerAdvice
public class ApiExceptions {

    /** Thrown when an account sends faster than its rate limit. */
    public static final class RateLimited extends RuntimeException {
        public RateLimited() {
            super("rate limit exceeded, slow down");
        }
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, ServerWebInputException.class})
    ResponseEntity<ErrorBody> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ErrorBody> notFound(NotFound e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(RateLimited.class)
    ResponseEntity<ErrorBody> rateLimited(RateLimited e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", e.getMessage());
    }

    @ExceptionHandler(ExchangeBusyException.class)
    ResponseEntity<ErrorBody> busy(ExchangeBusyException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "busy", e.getMessage());
    }

    @ExceptionHandler(CompletionException.class)
    ResponseEntity<ErrorBody> unwrap(CompletionException e) {
        if (e.getCause() instanceof ExchangeBusyException busy) {
            return busy(busy);
        }
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal", String.valueOf(e.getCause()));
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(error, message));
    }
}
