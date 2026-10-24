package com.aquiroz.transactions.adapter.in.web;

import com.aquiroz.transactions.application.IdempotencyKeyReusedException;
import com.aquiroz.transactions.application.RequestInProgressException;
import com.aquiroz.transactions.domain.rule.BusinessRuleViolationException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every error into an RFC 9457 ProblemDetail with a stable {@code code} for clients (ARCHITECTURE §9).
 * Extends ResponseEntityExceptionHandler so Spring MVC's own errors (405, 415, ...) get the same format.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String MISSING_IDEMPOTENCY_KEY = "MISSING_IDEMPOTENCY_KEY";
    static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    private static final String TYPE_BASE = "https://errors.transactions.local/";

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail handleBusinessRule(BusinessRuleViolationException e) {
        log.info("Request rejected by business rule {}: {}", e.violation().code(), e.violation().message());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.violation().code(), "Business rule violation",
                e.violation().message(), "business-rule-violation");
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleKeyReused(IdempotencyKeyReusedException e) {
        log.info("Request rejected: idempotency key reused with a different payload");
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                e.getMessage(), null);
    }

    @ExceptionHandler(RequestInProgressException.class)
    ProblemDetail handleInProgress(RequestInProgressException e) {
        log.info("Request rejected: original request with the same idempotency key is still in progress");
        return problem(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Request in progress", e.getMessage(), null);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail handleNotFound(TransactionNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found", e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid parameter",
                "Invalid value for '" + e.getName() + "'", null);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred", null);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException e,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        if (e instanceof MissingRequestHeaderException missing) {
            var code = TransactionController.IDEMPOTENCY_KEY.equals(missing.getHeaderName())
                    ? MISSING_IDEMPOTENCY_KEY
                    : "MISSING_HEADER";
            return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, code, "Missing header",
                    "Required header '" + missing.getHeaderName() + "' is missing", null));
        }
        return super.handleServletRequestBindingException(e, headers, status, request);
    }

    /**
     * Constraints on controller parameters (Idempotency-Key, page, limit) arrive here. Because method validation
     * then also covers the {@code @Valid} body, its field errors arrive here too, as {@link ParameterErrors}.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        var keyProblem = idempotencyKeyProblem(e);
        if (keyProblem != null) {
            return ResponseEntity.badRequest().body(keyProblem);
        }
        var bodyErrors = e.getParameterValidationResults().stream()
                .filter(ParameterErrors.class::isInstance)
                .map(ParameterErrors.class::cast)
                .flatMap(result -> result.getFieldErrors().stream())
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        if (!bodyErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(validationFailed(bodyErrors));
        }
        var paramErrors = e.getParameterValidationResults().stream()
                .map(result -> Map.of("field", String.valueOf(result.getMethodParameter().getParameterName()),
                        "message", firstMessage(result)))
                .toList();
        var body = problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid parameter",
                "The request has " + paramErrors.size() + " invalid parameter(s)", null);
        body.setProperty("errors", paramErrors);
        return ResponseEntity.badRequest().body(body);
    }

    // A blank key counts as missing (MISSING_IDEMPOTENCY_KEY); a too long one is INVALID_IDEMPOTENCY_KEY
    private static ProblemDetail idempotencyKeyProblem(HandlerMethodValidationException e) {
        return e.getParameterValidationResults().stream()
                .filter(result -> {
                    var header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
                    return header != null && TransactionController.IDEMPOTENCY_KEY.equals(header.value());
                })
                .findFirst()
                .map(result -> failedConstraint(result, "NotBlank")
                        ? problem(HttpStatus.BAD_REQUEST, MISSING_IDEMPOTENCY_KEY, "Missing header",
                                "Idempotency-Key header must not be blank", null)
                        : problem(HttpStatus.BAD_REQUEST, INVALID_IDEMPOTENCY_KEY, "Invalid Idempotency-Key",
                                "Idempotency-Key must be at most " + TransactionController.MAX_IDEMPOTENCY_KEY_LENGTH
                                        + " characters", null))
                .orElse(null);
    }

    private static boolean failedConstraint(ParameterValidationResult result, String constraint) {
        return result.getResolvableErrors().stream()
                .anyMatch(error -> error.getCodes() != null && Arrays.asList(error.getCodes()).contains(constraint));
    }

    private static String firstMessage(ParameterValidationResult result) {
        return result.getResolvableErrors().stream()
                .map(error -> String.valueOf(error.getDefaultMessage()))
                .findFirst()
                .orElse("invalid");
    }

    private static ProblemDetail validationFailed(List<Map<String, String>> errors) {
        var body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                "The request has " + errors.size() + " invalid field(s)", null);
        body.setProperty("errors", errors);
        return body;
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Malformed request", "The body is not valid JSON or has a value of the wrong type", null));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail, String type) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + (type != null ? type : slug(code))));
        problem.setProperty("code", code);
        return problem;
    }

    private static String slug(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
