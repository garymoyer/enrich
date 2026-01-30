package com.plaid.enrich.util;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Utility for generating GUIDs (Globally Unique Identifiers).
 * Uses UUID version 4 for random GUID generation.
 * <p>
 * This component is designed to be easily mockable in tests.
 */
@Component
public class GuidGenerator {

    /**
     * Generates a new random GUID.
     *
     * @return a new UUID string in canonical format (lowercase, hyphenated)
     */
    public String generate() {
        return UUID.randomUUID().toString();
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
