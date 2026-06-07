package com.medieval.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * [I18N] Fundação da i18n do backend (Camada 2 — conteúdo servido pela API; ver docs/PLANO_I18N_BACKEND.md).
 *
 * - Locale por request = header {@code Accept-Language} ({@code en}/{@code pt}), restrito aos suportados
 *   (default {@code en}). O cliente (web/Godot) lê {@code Player.language} e manda esse header em toda
 *   chamada — zero leitura de DB por request.
 * - {@code MessageSource} basename {@code messages}: {@code messages.properties} (EN, fallback) +
 *   {@code messages_pt.properties} (PT).
 */
@Configuration
public class I18nConfig {

    public static final Locale EN = Locale.ENGLISH;
    public static final Locale PT = Locale.forLanguageTag("pt");
    public static final List<Locale> SUPPORTED = List.of(EN, PT);
    /** Tags de idioma suportadas (p/ validação no endpoint de settings + resposta ao cliente). [I18N] */
    public static final List<String> SUPPORTED_TAGS = List.of("en", "pt");

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(EN);          // sem header / idioma não suportado → inglês
        resolver.setSupportedLocales(SUPPORTED); // só en/pt
        return resolver;
    }

    @Bean
    public ReloadableResourceBundleMessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);     // idioma faltante → messages.properties (EN), não o locale da JVM
        ms.setUseCodeAsDefaultMessage(false);    // o helper Messages controla o default
        return ms;
    }
}
