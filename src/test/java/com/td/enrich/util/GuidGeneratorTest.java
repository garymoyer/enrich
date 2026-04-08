package com.td.enrich.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GuidGenerator}.
 *
 * <p><b>What these tests cover:</b>
 * <ul>
 *   <li>Output format — each generated ID must match the canonical UUID v4 pattern.</li>
 *   <li>Uniqueness — 10 000 calls must produce 10 000 distinct IDs.</li>
 *   <li>UUID version nibble — character at index 14 must be {@code '4'}.</li>
 *   <li>IETF variant bits — character at index 19 must be {@code 8}, {@code 9},
 *       {@code a}, or {@code b}.</li>
 *   <li>Validation — {@link GuidGenerator#isValid} accepts well-formed UUIDs and
 *       rejects null, empty string, and malformed strings.</li>
 *   <li>Normalization — {@link GuidGenerator#normalize} lowercases the input;
 *       throws {@link IllegalArgumentException} for invalid UUIDs.</li>
 *   <li>Round-trip — generated IDs can be parsed back to a {@link UUID} object.</li>
 * </ul>
 */
@DisplayName("GuidGenerator Unit Tests")
class GuidGeneratorTest {

    private GuidGenerator guidGenerator;

    @BeforeEach
    void setUp() {
        guidGenerator = new GuidGenerator();
    }

    // ── generate() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should generate valid UUID format")
    void shouldGenerateValidUuid() {
        String guid = guidGenerator.generate();

        assertThat(guid).isNotNull();
        assertThat(guid).hasSize(36); // 32 hex digits + 4 hyphens
        // UUID canonical form: 8-4-4-4-12 hex digits separated by hyphens, all lowercase
        assertThat(guid).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    @Test
    @DisplayName("Should generate unique GUIDs")
    void shouldGenerateUniqueGuids() {
        Set<String> guids = new HashSet<>();
        int iterations = 10_000;

        for (int i = 0; i < iterations; i++) {
            guids.add(guidGenerator.generate());
        }

        // A HashSet deduplicates entries — if all 10 000 are present the generator is collision-free
        assertThat(guids).hasSize(iterations);
    }

    @Test
    @DisplayName("Should generate UUID version 4 (version nibble must be '4')")
    void shouldGenerateUuidVersion4() {
        // UUID layout: xxxxxxxx-xxxx-Mxxx-Nxxx-xxxxxxxxxxxx
        // Index 14 (first character of the third group) is the version nibble M.
        // Version 4 UUIDs always have M = '4'.
        String guid = guidGenerator.generate();
        assertThat(guid.charAt(14)).isEqualTo('4');
    }

    @Test
    @DisplayName("Should generate UUID with IETF variant bits (first char of 4th group must be 8/9/a/b)")
    void shouldGenerateUuidWithCorrectVariantBits() {
        // Index 19 (first character of the fourth group) is the variant nibble N.
        // IETF variant (RFC 4122) requires the two most significant bits of N to be 1,0,
        // which restricts N to the values 8 (1000), 9 (1001), a (1010), or b (1011).
        String guid = guidGenerator.generate();
        char variantNibble = guid.charAt(19);
        assertThat(variantNibble).isIn('8', '9', 'a', 'b');
    }

    @Test
    @DisplayName("Should parse generated GUID back to UUID")
    void shouldParseGeneratedGuid() {
        String guid = guidGenerator.generate();
        // UUID.fromString() throws IllegalArgumentException if the format is invalid
        assertThat(UUID.fromString(guid)).isNotNull();
    }

    // ── isValid() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should validate valid GUID")
    void shouldValidateValidGuid() {
        String validGuid = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(guidGenerator.isValid(validGuid)).isTrue();
    }

    @Test
    @DisplayName("Should invalidate null GUID")
    void shouldInvalidateNullGuid() {
        assertThat(guidGenerator.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("Should invalidate empty GUID")
    void shouldInvalidateEmptyGuid() {
        assertThat(guidGenerator.isValid("")).isFalse();
    }

    @Test
    @DisplayName("Should invalidate malformed GUID")
    void shouldInvalidateMalformedGuid() {
        assertThat(guidGenerator.isValid("not-a-guid")).isFalse();
    }

    // ── normalize() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should normalize GUID to lowercase")
    void shouldNormalizeGuid() {
        String mixedCaseGuid = "550E8400-E29B-41D4-A716-446655440000";

        String normalized = guidGenerator.normalize(mixedCaseGuid);

        // Normalized form must be fully lowercase
        assertThat(normalized).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("Should throw exception when normalizing invalid GUID")
    void shouldThrowExceptionOnInvalidNormalize() {
        // normalize() calls UUID.fromString() internally; invalid input → IllegalArgumentException
        assertThatThrownBy(() -> guidGenerator.normalize("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
