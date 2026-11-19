package com.example.shortlinks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTest {
    @TempDir
    Path dir;
    private final List<URI> opened = new ArrayList<>();

    private String session(String commands) throws IOException {
        var store = new LinkStore(dir.resolve("links.tsv"));
        var service = new LinkService(store, Clock.systemUTC(), Duration.ofDays(1));
        var buffer = new ByteArrayOutputStream();
        try (var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
             var input = new Scanner(commands)) {
            new Console(input, service, 10, out, opened::add).run();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void guestOpensLinkAndOwnerGetsNoticeOnNextLogin() throws IOException {
        String first = session("create https://example.org/docs 1\nexit\n");
        var id = Pattern.compile("[0-9a-f-]{36}").matcher(first);
        var address = Pattern.compile("https://s\\.local/[a-z0-9]+").matcher(first);
        assertTrue(id.find());
        assertTrue(address.find());
        String guest = session(address.group() + "\n" + address.group() + "\n");
        assertEquals(List.of(URI.create("https://example.org/docs")), opened);
        assertTrue(guest.contains("недоступна"));
        assertFalse(guest.contains("исчерпан лимит"));
        assertTrue(session("login " + id.group() + "\nlist\n").contains("исчерпан лимит"));
    }

    @Test
    void switchingUsersSeparatesLinksAndEditRights() throws IOException {
        String first = session("create https://example.org/alice\n");
        var id = Pattern.compile("[0-9a-f-]{36}").matcher(first);
        var address = Pattern.compile("https://s\\.local/[a-z0-9]+").matcher(first);
        assertTrue(id.find());
        assertTrue(address.find());
        String link = address.group();
        String second = session(String.join("\n", "login " + id.group(), "logout",
                "create https://example.org/bob", "target " + link + " https://example.net/new",
                "delete " + link, "list", "exit"));
        assertTrue(second.contains("только её владелец"));
        assertTrue(second.contains("example.org/bob"));
        assertFalse(second.contains("example.org/alice"));
        String back = session("login " + id.group() + "\nlist\n" + link + "\n");
        assertTrue(back.contains("example.org/alice"));
        assertFalse(back.contains("example.org/bob"));
        assertEquals(List.of(URI.create("https://example.org/alice")), opened);
    }

    @Test
    void pastedUrlIsShortenedAndItsShortLinkOpensOriginal() throws IOException {
        String url = "https://github.com/SvetlichnyiNikita/ShortLinks";
        String output = session(url + "\nlist\n");
        assertTrue(output.contains("Сохраните ваш UUID"));
        assertTrue(output.contains(url + " | переходов 0/10"));
        assertTrue(opened.isEmpty());
        var address = Pattern.compile("https://s\\.local/[a-z0-9]+").matcher(output);
        assertTrue(address.find());
        session(address.group() + "\n");
        assertEquals(List.of(URI.create(url)), opened);
    }

    @Test
    void unknownOrMalformedShortLinkDoesNotCreateANewOne() throws IOException {
        String output = session("https://s.local/missing\nhttps://s.local/bad/path\n");
        assertTrue(output.contains("недоступна"));
        assertTrue(output.contains("Скопируйте короткую ссылку"));
        assertFalse(output.contains("Сохраните ваш UUID"));
        assertTrue(opened.isEmpty());
    }

    @Test
    void mixedCaseCommandsKeepUrlCaseAndStopAtExit() throws IOException {
        String url = "https://example.org/Docs?Key=AbC#Top";
        String output = session(String.join("\n", "CrEaTe " + url, "LiSt", "eXiT", "help"));
        assertTrue(output.contains(url + " | переходов 0/10"));
        assertFalse(output.contains("Пример: create"));
    }

    @Test
    void badInputDoesNotEndSessionOrCreateUser() throws IOException {
        String result = session("create invalid 2\ncreate https://example.org 0\nlogin wrong\nlist\nhelp\n");
        assertFalse(result.contains("Сохраните ваш UUID"));
        assertTrue(result.contains("целое положительное"));
        assertTrue(result.contains("UUID не найден"));
        assertTrue(result.contains("Пример: create"));
    }
}
