package com.aistock.research.common.error;

import com.aistock.research.configuration.RuntimeConfigRevisionConflictException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.put(error.getField(), error.getDefaultMessage()));
        ApiError body = new ApiError("VALIDATION_ERROR", "请求参数校验失败", Instant.now(), fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(HttpMessageNotReadableException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        findMappingException(exception).ifPresent(mappingException ->
                mappingException.getPath().stream()
                        .map(JsonMappingException.Reference::getFieldName)
                        .filter(field -> field != null && !field.isBlank())
                        .forEach(field -> fields.put(field, "请求参数格式错误")));
        ApiError body = new ApiError("VALIDATION_ERROR", "请求参数格式错误", Instant.now(), fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception) {
        ApiError body = new ApiError("VALIDATION_ERROR", exception.getMessage(), Instant.now(), Map.of());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        ApiError body = new ApiError("BAD_REQUEST", exception.getMessage(), Instant.now(), Map.of());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(RuntimeConfigRevisionConflictException.class)
    public ResponseEntity<ApiError> handleRuntimeConfigConflict(
            RuntimeConfigRevisionConflictException exception
    ) {
        ApiError body = new ApiError(
                "RUNTIME_CONFIG_CONFLICT",
                exception.getMessage(),
                Instant.now(),
                Map.of()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception) {
        ApiError body = new ApiError("SERVICE_UNAVAILABLE", exception.getMessage(), Instant.now(), Map.of());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception) {
        String message = exception.getReason() == null || exception.getReason().isBlank()
                ? "请求无法处理"
                : exception.getReason();
        ApiError body = new ApiError(
                "HTTP_" + exception.getStatusCode().value(),
                message,
                Instant.now(),
                Map.of()
        );
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        ApiError body = new ApiError("INTERNAL_ERROR", "服务暂时不可用", Instant.now(), Map.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private Optional<JsonMappingException> findMappingException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof JsonMappingException mappingException) {
                return Optional.of(mappingException);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
