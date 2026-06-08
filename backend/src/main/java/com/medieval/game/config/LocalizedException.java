package com.medieval.game.config;

/**
 * [I18N] Erro de regra de negócio com mensagem LOCALIZÁVEL. Carrega uma {@code key} (resolvida no
 * GlobalExceptionHandler via Messages no idioma do request), o {@code defaultEn} (mensagem EN, também
 * usada no log) e {@code args} p/ placeholders {0},{1}…. Usado nos throws com texto INTERPOLADO (os
 * estáticos são traduzidos pelo handler usando a própria mensagem EN como key). Vira HTTP 400.
 */
// extends IllegalStateException (não RuntimeException) p/ preservar o contrato: handlers e testes que
// tratavam IllegalStateException (regra de negócio → 400) continuam valendo; o @ExceptionHandler mais
// específico (LocalizedException) tem precedência e faz a tradução. [I18N]
public class LocalizedException extends IllegalStateException {

    private final String key;
    private final transient Object[] args;

    public LocalizedException(String key, String defaultEn, Object... args) {
        super(defaultEn); // o getMessage() devolve o EN (log + fallback)
        this.key  = key;
        this.args = args;
    }

    public String   key()  { return key; }
    public Object[] args() { return args; }
}
