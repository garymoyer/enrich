package com.td.enrich.util;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates and validates UUID v4 identifiers (GUIDs) used throughout the service.
 *
 * <p>This class exists as a Spring-managed bean (rather than a static utility) so that
 * tests can inject a mock and control exactly which UUIDs are returned. That makes
 * test assertions deterministic — you can assert {@code response.requestId().equals("known-uuid")}
 * instead of something vague like {@code assertNotNull(response.requestId())}.
 *
 * <p><b>Why {@code ThreadLocalRandom} instead of {@code UUID.randomUUID()}?</b><br>
 * {@code UUID.randomUUID()} uses {@code SecureRandom} internally, which requires a
 * shared lock. Under high concurrency (many threads generating UUIDs simultaneously)
 * that lock becomes a bottleneck. {@code ThreadLocalRandom} gives each thread its own
 * random number generator with no sharing, so there's no lock contention. The trade-off
 * is that {@code ThreadLocalRandom} is not cryptographically secure — but UUIDs used
 * as request IDs don't need to be secret, they just need to be unique.
 *
 * <p>This bean is a singleton (one shared instance across the whole application), which
 * is safe because it holds no mutable state.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
public class GuidGenerator {

    /**
     * Generates a new random UUID v4 string.
     *
     * <p>UUID v4 format: {@code xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx} where:
     * <ul>
     *   <li>The {@code 4} in position 13 indicates version 4 (random).</li>
     *   <li>The {@code y} in position 17 is one of {@code 8}, {@code 9}, {@code a},
     *       or {@code b} — these are the IETF variant bits.</li>
     * </ul>
     * The bit manipulation below sets those two fixed fields while leaving all other
     * bits random, producing a standards-compliant UUID v4.
     *
     * @return a new UUID in lowercase hyphenated format, e.g.
     *         {@code "550e8400-e29b-41d4-a716-446655440000"}
     */
    public String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Generate 128 random bits split into two 64-bit longs
        long msb = random.nextLong(); // most-significant bits  (first 64 bits of the UUID)
        long lsb = random.nextLong(); // least-significant bits (last  64 bits of the UUID)

        // Set version bits: force bits 12–15 of msb to 0100 (binary) = version 4
        // The mask 0xffffffffffff0fffL clears bits 12-15; OR with 0x4000 sets them to 0100
        msb = (msb & 0xffffffffffff0fffL) | 0x0000000000004000L;

        // Set IETF variant bits: force bits 62–63 of lsb to 10 (binary)
        // The mask 0x3fffffffffffffffL clears bits 62-63; OR with 0x8000... sets them to 10
        lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;

        return new UUID(msb, lsb).toString();
    }

    /**
     * Checks whether a string is a syntactically valid UUID.
     *
     * <p>This does not make a database call — it only checks the format. Use it at
     * API boundaries to reject obviously malformed request IDs early, before touching
     * the database.
     *
     * @param guid the string to validate; may be {@code null}
     * @return {@code true} if the string is a valid UUID in any standard format;
     *         {@code false} if it is {@code null}, blank, or malformed
     */
    public boolean isValid(String guid) {
        if (guid == null || guid.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(guid);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Normalizes a UUID string to the standard lowercase hyphenated format.
     *
     * <p>Useful when a caller supplies a UUID in uppercase or without hyphens and we
     * need to compare it against values stored in the database.
     *
     * @param guid the UUID string to normalize (must be a valid UUID)
     * @return the UUID in lowercase hyphenated form
     * @throws IllegalArgumentException if {@code guid} is not a valid UUID
     */
    public String normalize(String guid) {
        return UUID.fromString(guid).toString();
    }
}
