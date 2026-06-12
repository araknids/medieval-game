package com.medieval.game.service;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * [I18N] Resolve uma key de tradução no locale do REQUEST atual ({@code Accept-Language}, via
 * {@link LocaleContextHolder}). Os textos PT vivem em {@code messages_pt.properties}; o EN é o
 * default do catálogo/enum (passado em {@link #getOr}). Ver docs/PLANO_I18N_BACKEND.md.
 */
@Component
public class Messages {

    private final MessageSource messageSource;
    /** Captura p/ o acessor estático {@link #tr} (usado em métodos estáticos como AchievementService.titleString). */
    private static MessageSource MS;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
        MS = messageSource;
    }

    /**
     * Locale do request atual; se NÃO houver contexto de request (testes, schedulers, startup), default
     * EN — NUNCA o locale da JVM (senão o servidor responderia no idioma do SO). [I18N]
     */
    private static Locale locale() {
        var ctx = LocaleContextHolder.getLocaleContext();
        Locale l = ctx != null ? ctx.getLocale() : null;
        return l != null ? l : Locale.ENGLISH;
    }

    /** Texto da {@code key} no idioma do request (args opcionais p/ placeholders {0},{1}…). Key faltando → a própria key. */
    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, key, locale());
    }

    /**
     * Texto da {@code key} no idioma do request, com {@code defaultEn} de fallback (a prosa EN que já vive
     * no catálogo/enum). EN → cai no defaultEn; PT → messages_pt (ou defaultEn se não traduzida). Só o PT
     * precisa ir pro .properties. [I18N]
     */
    public String getOr(String key, String defaultEn, Object... args) {
        return messageSource.getMessage(key, args, defaultEn, locale());
    }

    /** Versão ESTÁTICA do {@link #getOr} (p/ contextos estáticos, ex.: títulos de achievement no ranking). [I18N] */
    public static String tr(String key, String defaultEn, Object... args) {
        if (MS == null) // sem Spring (startup/testes/sonda) → formata o default EN com os args (senão sai "{0}" cru)
            return (args == null || args.length == 0) ? defaultEn : java.text.MessageFormat.format(defaultEn, args);
        return MS.getMessage(key, args, defaultEn, locale());
    }
}
