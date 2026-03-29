package com.plaid.enrich.util;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility for generating GUIDs (Globally Unique Identifiers).
 * Uses UUID version 4 for random GUID generation.
 * <p>
 * This component is designed to be easily mockable in tests.
 * Singleton-scoped: Spring manages a single shared instance.
 * Thread-safe: stateless; generation uses ThreadLocalRandom to avoid
 * the lock contention of SecureRandom under high concurrency.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
public class GuidGenerator {

    /**
     * Generates a new random GUID.
     * Uses ThreadLocalRandom for contention-free UUID v4 generation.
     *
     * @return a new UUID string in canonical format (lowercase, hyphenated)
     */
    public String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = random.nextLong();
        long lsb = random.nextLong();
        // Set version 4 bits (bits 12-15 of msb)
        msb = (msb & 0xffffffffffff0fffL) | 0x0000000000004000L;
        // Set IETF variant bits (bits 62-63 of lsb)
        lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    /**
     * Validates whether a given string is a valid GUID format.
     *
     * @param guid the string to validate
     * @return true if the string is a valid UUID, false otherwise
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
     * Normalizes a GUID string to lowercase canonical format.
     *
     * @param guid the GUID to normalize
     * @return normalized GUID string
     * @throws IllegalArgumentException if the guid is not valid
     */
    public String normalize(String guid) {
        return UUID.fromString(guid).toString();
    }
}
