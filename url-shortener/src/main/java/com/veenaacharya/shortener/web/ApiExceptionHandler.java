package com.veenaacharya.shortener.web;

import com.veenaacharya.shortener.link.CodeGenerationExhaustedException;
import com.veenaacharya.shortener.link.LinkExpiredException;
import com.veenaacharya.shortener.link.LinkNotFoundException;
import com.veenaacharya.shortener.ratelimit.RateLimitExceededException;
import com.veenaacharya.shortener.validation.InvalidUrlException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ProblemDetail> handleInvalidUrl(InvalidUrlException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid URL", e.getMessage(), null);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", e.getMessage(), null);
    }

    @ExceptionHandler(LinkExpiredException.class)
    public ResponseEntity<ProblemDetail> handleExpired(LinkExpiredException e) {
        return problem(HttpStatus.GONE, "Link expired", e.getMessage(), null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", e.getMessage(),
                String.valueOf(e.getRetryAfterSeconds()));
    }

    @ExceptionHandler(CodeGenerationExhaustedException.class)
    public ResponseEntity<ProblemDetail> handleExhausted(CodeGenerationExhaustedException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Short code unavailable", e.getMessage(), null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail,
                                                         String retryAfter) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(body);
    }
}
