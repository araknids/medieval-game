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
}
