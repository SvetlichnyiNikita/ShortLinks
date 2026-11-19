package com.example.shortlinks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShortLinkTest {
    private ShortLink link(String target) {
        return new ShortLink("1", UUID.randomUUID(), URI.create(target), Instant.EPOCH, 2, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.org", "file:///tmp/example", "https://example.org:0",
            "https://example.org:65536", "https://user:pass@example.org"})
    void rejectsInvalidDestination(String address) {
        assertThrows(IllegalArgumentException.class, () -> link(address));
    }

    @Test
    void acceptsIpv6HostAndHighestValidPort() {
        String address = "http://[::1]:65535/path?q=1#section";
        assertEquals(address, link(address).target().toString());
    }
}
