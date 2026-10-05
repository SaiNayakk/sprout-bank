package app.sprout.bank.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the bank contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request isn't valid"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in to continue"),
    INVALID_PARTNER_KEY(HttpStatus.UNAUTHORIZED, "Unknown partner"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    ACCOUNT_EXISTS(HttpStatus.CONFLICT, "You already have an account"),
    WEAK_PIN(HttpStatus.BAD_REQUEST, "Choose a harder UPI PIN"),
    INVALID_PIN(HttpStatus.UNPROCESSABLE_ENTITY, "Wrong UPI PIN"),
    PIN_LOCKED(HttpStatus.LOCKED, "UPI PIN locked"),
    REQUEST_NOT_PENDING(HttpStatus.CONFLICT, "This request is no longer waiting"),
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough money in the account"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
