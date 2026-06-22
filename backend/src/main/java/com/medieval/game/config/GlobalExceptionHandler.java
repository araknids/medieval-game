package com.medieval.game.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
        // Loga o valor rejeitado (só no servidor) para diagnóstico — ex.: descobrir qual char bloqueou.
        String rejected = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + "='" + e.getRejectedValue() + "'")
                .collect(Collectors.joining(", "));
        log.warn("[GlobalExceptionHandler] Validation failed: {} | rejected: {}", errors, rejected);
        return ResponseEntity.badRequest().body(Map.of("error", errors));
    }

    // [I18N] Erro com mensagem interpolada (key + args) → resolve no idioma do request.
    @ExceptionHandler(LocalizedException.class)
    public ResponseEntity<?> handleLocalized(LocalizedException ex) {
        log.warn("[GlobalExceptionHandler] Business rule rejected: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error",
                com.medieval.game.service.Messages.tr(ex.key(), ex.getMessage(), ex.args())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[GlobalExceptionHandler] Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", tr(ex.getMessage())));
    }

    // Regra de negócio rejeitada (saldo insuficiente, bag cheia, já coletado, etc.)
    // → 400, mantendo o contrato que os controllers já entregavam. Conflito de
    // concorrência real é tratado separadamente abaixo (409). [AUDITORIA A6]
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {
        log.warn("[GlobalExceptionHandler] Business rule rejected: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", tr(ex.getMessage())));
    }

    // [I18N] Traduz uma mensagem de erro ESTÁTICA (sem interpolação) usando a própria mensagem EN como
    // key (messages_pt.properties tem a EN com espaços escapados → PT). Em EN, ou se não houver tradução,
    // devolve a própria mensagem (graceful). Os erros interpolados usam LocalizedException (key+args).
    private static String tr(String enMessage) {
        return enMessage == null ? null : com.medieval.game.service.Messages.tr(enMessage, enMessage);
    }

    // Conflito de escrita concorrente (optimistic locking) — ex.: dois cliques no mesmo
    // collect. A 2ª transação falha no commit; devolvemos 409 para o cliente tentar de novo. [AUDITORIA C3]
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("[GlobalExceptionHandler] Concurrent modification: {}", ex.getMessage());
        return ResponseEntity.status(409).body(Map.of(
            "error", com.medieval.game.service.Messages.tr("error.concurrent", "Concurrent action detected. Please try again.")));
    }

    // [VARREDURA] Violação de constraint do banco — na prática, corrida de unique parcial (1 sessão
    // IN_PROGRESS por player: work/quest/training) ou nome de guild duplicado, que escapou do guard de app.
    // É conflito concorrente → 409 "tente de novo" (mesma UX do optimistic-lock). Log em WARN p/ não
    // mascarar uma eventual violação genuína (FK/not-null) — que ainda aparece no log.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("[GlobalExceptionHandler] Data integrity / concurrent insert: {}",
                ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage());
        return ResponseEntity.status(409).body(Map.of(
            "error", com.medieval.game.service.Messages.tr("error.concurrent", "Concurrent action detected. Please try again.")));
    }

    // Método HTTP errado para a rota (ex.: GET num endpoint POST). Em URL pública isto é
    // basicamente ruído de bots/scanners varrendo endpoints (GET /api/auth/login, etc.).
    // É erro de CLIENTE → 405, com 1 linha de log (sem stack trace) que mostra método+rota.
    // Sem este handler cairia no handleGeneric e logaria ERROR 500 + stack a cada hit. [LOG NOISE]
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        log.warn("[GlobalExceptionHandler] 405 {} {} (suportado: {})",
                req.getMethod(), req.getRequestURI(), ex.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error",
                com.medieval.game.service.Messages.tr("error.method_not_allowed", "Method not allowed")));
    }

    // Path inexistente (bots probando /wp-login.php, /.env, etc.). Mesma classe de ruído:
    // 404 com 1 linha de log, em vez de cair no handleGeneric como ERROR 500. [LOG NOISE]
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        log.warn("[GlobalExceptionHandler] 404 {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error",
                com.medieval.game.service.Messages.tr("error.not_found", "Not found")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex) {
        log.error("[GlobalExceptionHandler] Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(500).body(Map.of("error",
                com.medieval.game.service.Messages.tr("error.internal", "Internal server error")));
    }
}
