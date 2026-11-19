package com.example.shortlinks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkServiceTest {
    private static final URI TARGET = URI.create("https://example.org/article?id=12#comments");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @TempDir
    Path dir;
    private LinkService service;

    @BeforeEach
    void start() throws IOException {
        service = at(START, Duration.ofHours(1));
    }

    private LinkService at(Instant time, Duration ttl) throws IOException {
        return new LinkService(new LinkStore(dir.resolve("links.tsv")), Clock.fixed(time, ZoneOffset.UTC), ttl);
    }

    @Test
    void sameTargetGetsDifferentCodesForTwoUsers() throws IOException {
        ShortLink a = service.create(alice, TARGET, 2);
        ShortLink b = service.create(bob, TARGET, 2);
        assertNotEquals(a.address(), b.address());
    }

    @Test
    void listShowsAllAndOnlyOwnedLinks() throws IOException {
        ShortLink a = service.create(alice, TARGET, 3);
        service.create(bob, TARGET, 3);
        ShortLink b = service.create(alice, URI.create("https://example.net"), 4);
        assertEquals(List.of(a, b), service.list(alice));
    }

    @Test
    void finalVisitOpensTargetExactlyOnceAndNotifiesOwner() throws IOException {
        ShortLink link = service.create(alice, TARGET, 2);
        List<URI> opened = new ArrayList<>();
        service.open(link.code(), opened::add);
        assertEquals(1, service.list(alice).get(0).visits());
        assertTrue(service.takeNotices(alice).isEmpty());
        service.open(link.code(), opened::add);
        assertThrows(IllegalArgumentException.class, () -> service.open(link.code(), opened::add));
        assertEquals(List.of(TARGET, TARGET), opened);
        assertTrue(service.takeNotices(bob).isEmpty());
        var notices = service.takeNotices(alice);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("лимит"));
        assertTrue(service.takeNotices(alice).isEmpty());
    }

    @Test
    void browserFailureDoesNotSpendVisit() throws IOException {
        ShortLink link = service.create(alice, TARGET, 1);
        assertThrows(IOException.class, () -> service.open(link.code(), uri -> {
            throw new IOException("Browser unavailable");
        }));
        assertEquals(List.of(link), service.list(alice));
        LinkService restarted = at(START, Duration.ofHours(1));
        assertEquals(List.of(link), restarted.list(alice));
        assertTrue(restarted.takeNotices(alice).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24})
    void expiryStartsAtExactDeadlineAndSurvivesRestart(int hours) throws IOException {
        Duration ttl = Duration.ofHours(hours);
        ShortLink link = at(START, ttl).create(alice, TARGET, 3);
        Instant deadline = START.plus(ttl);
        assertDoesNotThrow(() -> at(deadline.minusMillis(1), ttl).open(link.code(), uri -> {}));
        LinkService later = at(deadline, ttl);
        assertThrows(IllegalArgumentException.class, () -> later.open(link.code(), uri -> {}));
        LinkService restarted = at(deadline, ttl);
        assertTrue(restarted.list(alice).isEmpty());
        var notices = restarted.takeNotices(alice);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("срок"));
    }

    @Test
    void cleanupWorksWithoutVisit() throws IOException {
        service.create(alice, TARGET, 2);
        LinkService later = at(START.plusSeconds(3600), Duration.ofDays(1));
        later.removeExpired();
        assertFalse(later.takeNotices(alice).isEmpty());
    }

    @Test
    void cannotEditOrDeleteAnotherUsersLink() throws IOException {
        ShortLink link = service.create(alice, TARGET, 3);
        assertThrows(IllegalArgumentException.class, () -> service.changeLimit(bob, link.code(), 10));
        assertThrows(IllegalArgumentException.class, () -> service.changeTarget(bob, link.code(), TARGET));
        assertThrows(IllegalArgumentException.class, () -> service.delete(bob, link.code()));
        assertEquals(List.of(link), service.list(alice));
    }

    @Test
    void changingDestinationPreservesDeadlineAndVisits() throws IOException {
        ShortLink link = service.create(alice, TARGET, 3);
        URI replacement = URI.create("https://example.net/new");
        service.open(link.code(), uri -> {});
        service.changeTarget(alice, link.code(), replacement);
        service.open(link.code(), uri -> assertEquals(replacement, uri));
        ShortLink edited = service.list(alice).get(0);
        assertEquals(link.expiresAt(), edited.expiresAt());
        assertEquals(2, edited.visits());
    }

    @Test
    void changingLimitPreservesPreviouslyCountedVisits() throws IOException {
        ShortLink link = service.create(alice, TARGET, 3);
        service.open(link.code(), uri -> {});
        assertThrows(IllegalArgumentException.class, () -> service.changeLimit(alice, link.code(), 1));
        service.changeLimit(alice, link.code(), 2);
        service.open(link.code(), uri -> {});
        assertTrue(service.list(alice).isEmpty());
    }

    @Test
    void deletedCodeIsNeverReassignedAfterRestart() throws IOException {
        ShortLink first = service.create(alice, TARGET, 3);
        service.delete(alice, first.code());
        LinkService restarted = at(START, Duration.ofDays(1));
        assertNotEquals(first.code(), restarted.create(bob, TARGET, 3).code());
        assertThrows(IllegalArgumentException.class, () -> restarted.open(first.code(), uri -> {}));
        assertEquals(alice, restarted.login(alice.toString()));
    }

    @Test
    void damagedSequenceCannotReassignExistingCode() throws IOException {
        service.create(alice, TARGET, 5);
        Path file = dir.resolve("links.tsv");
        String damaged = Files.readString(file).replace("sequence\t1", "sequence\t0");
        Files.writeString(file, damaged);
        assertThrows(IOException.class, () -> new LinkStore(file));
        assertEquals(damaged, Files.readString(file));
    }

    @Test
    void rejectsUnknownUser() {
        assertThrows(IllegalArgumentException.class, () -> service.login(bob.toString()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void refusesNonPositiveLimit(int limit) {
        assertThrows(IllegalArgumentException.class, () -> service.create(alice, TARGET, limit));
    }

    @Test
    void failedWriteDoesNotOpenBrowserOrConsumeLastVisit() throws IOException {
        ShortLink link = service.create(alice, TARGET, 1);
        Path blocked = Files.createDirectory(dir.resolve("links.tsv.tmp"));
        List<URI> opened = new ArrayList<>();
        assertThrows(IOException.class, () -> service.open(link.code(), opened::add));
        assertTrue(opened.isEmpty());
        assertEquals(List.of(link), service.list(alice));
        assertTrue(service.takeNotices(alice).isEmpty());
        Files.delete(blocked);
        service.open(link.code(), opened::add);
        assertEquals(List.of(TARGET), opened);
    }

    @Test
    void ownerCanReadNoticeAfterFailedWrite() throws IOException {
        ShortLink link = service.create(alice, TARGET, 1);
        service.open(link.code(), uri -> {});
        Path blocked = Files.createDirectory(dir.resolve("links.tsv.tmp"));
        assertThrows(IOException.class, () -> service.takeNotices(alice));
        Files.delete(blocked);
        assertEquals(1, service.takeNotices(alice).size());
    }

    @Test
    void invalidDestinationCannotBeSavedThroughService() throws IOException {
        URI invalid = URI.create("file:///tmp/example");
        assertThrows(IllegalArgumentException.class, () -> service.create(alice, invalid, 1));
        ShortLink link = service.create(alice, TARGET, 2);
        assertThrows(IllegalArgumentException.class, () -> service.changeTarget(alice, link.code(), invalid));
        assertEquals(List.of(link), service.list(alice));
    }

    @Test
    void preservesPathQueryAndFragment() throws IOException {
        ShortLink link = service.create(alice, TARGET, 1);
        service.open(link.code(), uri -> assertEquals(TARGET.toASCIIString(), uri.toASCIIString()));
    }
}
