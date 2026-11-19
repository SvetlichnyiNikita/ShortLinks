package com.example.shortlinks;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LinkStore {
    final Set<UUID> owners = new LinkedHashSet<>();
    final Map<String, ShortLink> links = new LinkedHashMap<>();
    final Map<UUID, List<String>> notices = new HashMap<>();
    private final Path file;
    private long sequence;

    LinkStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (Files.notExists(file)) {
            return;
        }
        boolean hasSequence = false;
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            String[] fields = line.split("\\t", -1);
            try {
                if (fields.length == 2 && fields[0].equals("sequence")) {
                    sequence = Long.parseLong(fields[1]);
                    if (hasSequence || sequence < 0) {
                        throw new IllegalArgumentException("Некорректный счётчик ссылок");
                    }
                    hasSequence = true;
                } else if (fields.length == 2 && fields[0].equals("owner")) {
                    owners.add(UUID.fromString(fields[1]));
                } else if (fields.length == 7 && fields[0].equals("link")) {
                    ShortLink link = new ShortLink(fields[1], UUID.fromString(fields[2]),
                            URI.create(fields[3]), Instant.parse(fields[4]),
                            Integer.parseInt(fields[5]), Integer.parseInt(fields[6]));
                    if (link.visits() >= link.limit() || links.putIfAbsent(link.code(), link) != null) {
                        throw new IllegalArgumentException("Некорректная или повторная запись ссылки");
                    }
                } else if (fields.length == 3 && fields[0].equals("notice")) {
                    notify(UUID.fromString(fields[1]), fields[2]);
                } else {
                    throw new IllegalArgumentException("Неизвестная запись");
                }
            } catch (RuntimeException e) {
                throw new IOException("Ошибка в " + file + ", строка " + lineNumber, e);
            }
        }
        if (!hasSequence) {
            throw new IOException("В " + file + " отсутствует счётчик ссылок.");
        }
        for (ShortLink link : links.values()) {
            long id;
            try {
                id = Long.parseLong(link.code(), 36);
            } catch (NumberFormatException e) {
                throw new IOException("Некорректный код ссылки в " + file, e);
            }
            if (id < 1 || id > sequence || !Long.toString(id, 36).equals(link.code()) || !owners.contains(link.owner())) {
                throw new IOException("Некорректная запись ссылки " + link.code() + " в " + file);
            }
        }
    }

    private LinkStore(LinkStore source) {
        file = source.file;
        sequence = source.sequence;
        owners.addAll(source.owners);
        links.putAll(source.links);
        source.notices.forEach((owner, messages) -> notices.put(owner, new ArrayList<>(messages)));
    }

    LinkStore copy() {
        return new LinkStore(this);
    }

    String nextCode() {
        sequence = Math.incrementExact(sequence);
        return Long.toString(sequence, 36);
    }

    void notify(UUID owner, String message) {
        notices.computeIfAbsent(owner, key -> new ArrayList<>()).add(message);
    }

    void save() throws IOException {
        List<String> rows = new ArrayList<>();
        rows.add("sequence\t" + sequence);
        owners.forEach(owner -> rows.add("owner\t" + owner));
        for (ShortLink link : links.values()) {
            rows.add(String.join("\t", "link", link.code(), link.owner().toString(), link.target().toString(),
                    link.expiresAt().toString(), String.valueOf(link.limit()), String.valueOf(link.visits())));
        }
        notices.forEach((owner, messages) -> messages.forEach(message -> rows.add("notice\t" + owner + "\t" + message)));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, rows, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
