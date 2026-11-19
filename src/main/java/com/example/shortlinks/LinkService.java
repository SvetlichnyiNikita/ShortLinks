package com.example.shortlinks;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class LinkService {
    private LinkStore store;
    private final Clock clock;
    private final Duration lifetime;

    LinkService(LinkStore store, Clock clock, Duration lifetime) {
        this.store = store;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    synchronized ShortLink create(UUID owner, URI target, int limit) throws IOException {
        LinkStore next = store.copy();
        ShortLink link = new ShortLink(next.nextCode(), owner, target, clock.instant().plus(lifetime), limit, 0);
        next.owners.add(owner);
        next.links.put(link.code(), link);
        commit(next);
        return link;
    }

    synchronized UUID login(String value) {
        for (UUID owner : store.owners) {
            if (owner.toString().equalsIgnoreCase(value)) {
                return owner;
            }
        }
        throw new IllegalArgumentException("UUID не найден. Проверьте идентификатор.");
    }

    synchronized void changeTarget(UUID owner, String code, URI target) throws IOException {
        ShortLink link = owned(owner, code);
        LinkStore next = store.copy();
        next.links.put(code, new ShortLink(code, owner, target, link.expiresAt(), link.limit(), link.visits()));
        commit(next);
    }

    @FunctionalInterface
    interface Browser {
        void open(URI target) throws IOException;
    }

    synchronized void open(String code, Browser browser) throws IOException {
        ShortLink link = active(code);
        LinkStore previous = store;
        LinkStore next = store.copy();
        ShortLink visited = link.visited();
        if (visited.visits() == visited.limit()) {
            next.links.remove(code);
            notifyOwner(next, link, "исчерпан лимит переходов");
        } else {
            next.links.put(code, visited);
        }
        commit(next);
        try {
            browser.open(link.target());
        } catch (IOException | RuntimeException e) {
            commit(previous);
            throw e;
        }
    }

    synchronized void changeLimit(UUID owner, String code, int limit) throws IOException {
        ShortLink link = owned(owner, code);
        if (limit <= link.visits()) {
            throw new IllegalArgumentException("Лимит должен быть больше числа уже сделанных переходов (" + link.visits() + ").");
        }
        LinkStore next = store.copy();
        next.links.put(code, link.withLimit(limit));
        commit(next);
    }

    synchronized void delete(UUID owner, String code) throws IOException {
        owned(owner, code);
        LinkStore next = store.copy();
        next.links.remove(code);
        commit(next);
    }

    synchronized List<ShortLink> list(UUID owner) throws IOException {
        removeExpired();
        return store.links.values().stream().filter(link -> link.owner().equals(owner)).toList();
    }

    synchronized List<String> takeNotices(UUID owner) throws IOException {
        List<String> result = store.notices.get(owner);
        if (result == null) {
            return List.of();
        }
        LinkStore next = store.copy();
        next.notices.remove(owner);
        commit(next);
        return List.copyOf(result);
    }

    synchronized void removeExpired() throws IOException {
        Instant now = clock.instant();
        List<ShortLink> expired = store.links.values().stream()
                .filter(link -> !now.isBefore(link.expiresAt()))
                .toList();
        if (expired.isEmpty()) {
            return;
        }
        LinkStore next = store.copy();
        for (ShortLink link : expired) {
            next.links.remove(link.code());
            notifyOwner(next, link, "истёк срок действия");
        }
        commit(next);
    }

    private ShortLink active(String code) throws IOException {
        removeExpired();
        ShortLink link = store.links.get(code);
        if (link == null) {
            throw new IllegalArgumentException("Ссылка недоступна. Проверьте адрес или создайте новую ссылку.");
        }
        return link;
    }

    private ShortLink owned(UUID owner, String code) throws IOException {
        ShortLink link = active(code);
        if (!link.owner().equals(owner)) {
            throw new IllegalArgumentException("Изменять ссылку может только её владелец.");
        }
        return link;
    }

    private void commit(LinkStore next) throws IOException {
        next.save();
        store = next;
    }

    private static void notifyOwner(LinkStore next, ShortLink link, String reason) {
        next.notify(link.owner(), link.address() + " — " + reason + ". Создайте новую ссылку.");
    }
}
