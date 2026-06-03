package com.medieval.game.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("[GlobalExceptionHandler] Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(Map.of("error", errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[GlobalExceptionHandler] Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    // Regra de negócio rejeitada (saldo insuficiente, bag cheia, já coletado, etc.)
    // → 400, mantendo o contrato que os controllers já entregavam. Conflito de
    // concorrência real é tratado separadamente abaixo (409). [AUDITORIA A6]
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {
        log.warn("[GlobalExceptionHandler] Business rule rejected: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    // Conflito de escrita concorrente (optimistic locking) — ex.: dois cliques no mesmo
    // collect. A 2ª transação falha no commit; devolvemos 409 para o cliente tentar de novo. [AUDITORIA C3]
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("[GlobalExceptionHandler] Concurrent modification: {}", ex.getMessage());
        return ResponseEntity.status(409).body(Map.of(
            "error", "Ação concorrente detectada. Tente novamente."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex) {
        log.error("[GlobalExceptionHandler] Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
    }
}
