package com.medieval.game.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Identidade do servidor/realm atual. Público (a tela de login mostra antes de autenticar). [SERVIDORES]
 * Cada deploy seta SERVER_ID/SERVER_NAME/SERVER_ENV no Railway.
 */
@RestController
@RequestMapping("/api/server-info")
public class ServerInfoController {

    @Value("${app.server.id:local}")   private String id;
    @Value("${app.server.name:Local Dev}") private String name;
    @Value("${app.server.env:dev}")    private String env;

    // [DISTRIB_UPDATE] trava de versão do cliente:
    //   minClientVersion    = versão MÍNIMA que ainda consegue jogar (abaixo disso o cliente BLOQUEIA)
    //   latestClientVersion = última versão publicada (cliente mais novo só avisa "tem update")
    //   clientDownloadUrl   = onde baixar/atualizar (launcher/itch/Steam) — exibido no aviso
    @Value("${app.client.min-version:0.0.0}")    private String minClientVersion;
    @Value("${app.client.latest-version:0.0.0}") private String latestClientVersion;
    @Value("${app.client.download-url:}")        private String clientDownloadUrl;

    @GetMapping
    public ResponseEntity<?> info() {
        return ResponseEntity.ok(Map.of(
            "id", id, "name", name, "env", env,
            "minClientVersion", minClientVersion,
            "latestClientVersion", latestClientVersion,
            "clientDownloadUrl", clientDownloadUrl
        ));
    }
}
