package com.medieval.game.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * [I18N] Resolve uma key de tradução no locale do REQUEST atual ({@code Accept-Language}, via
 * {@link LocaleContextHolder}). Os textos vivem em {@code messages.properties} (EN) /
 * {@code messages_pt.properties} (PT). Ver docs/PLANO_I18N_BACKEND.md.
 *
 * Key faltando → devolve a própria key (aparece como texto, não quebra) — facilita achar buracos.
 */
@Component
@RequiredArgsConstructor
public class Messages {

    private final MessageSource messageSource;

    /** Texto da {@code key} no idioma do request (args opcionais p/ placeholders {0},{1}…). */
    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /**
     * Texto da {@code key} no idioma do request, com {@code defaultEn} de fallback (em geral a prosa
     * EN que já vive no catálogo/enum). Em EN não há messages_en → cai no {@code defaultEn}; em PT,
     * messages_pt fornece a tradução (ou {@code defaultEn} se a key ainda não foi traduzida).
     * Assim só o PT precisa ir pro .properties — o EN continua sendo o catálogo (sem duplicar). [I18N]
     */
    public String getOr(String key, String defaultEn, Object... args) {
        return messageSource.getMessage(key, args, defaultEn, LocaleContextHolder.getLocale());
    }
}
