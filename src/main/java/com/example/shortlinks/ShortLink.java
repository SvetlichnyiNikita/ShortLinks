package com.example.shortlinks;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

record ShortLink(String code, UUID owner, URI target, Instant expiresAt, int limit, int visits) {
    static final String HOST = "s.local";
    static final String PREFIX = "https://" + HOST + "/";

    ShortLink {
        if (target == null || target.getHost() == null || target.getRawUserInfo() != null
                || target.getPort() == 0 || target.getPort() > 65535
                || !("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))) {
            throw new IllegalArgumentException("Введите полный HTTP(S)-адрес с корректным именем хоста и портом.");
        }
        if (limit < 1 || visits < 0 || visits > limit) {
            throw new IllegalArgumentException("Некорректный лимит или число переходов.");
        }
    }

    String address() {
        return PREFIX + code;
    }

    ShortLink visited() {
        return new ShortLink(code, owner, target, expiresAt, limit, visits + 1);
    }

    ShortLink withLimit(int newLimit) {
        return new ShortLink(code, owner, target, expiresAt, newLimit, visits);
    }
}
