package com.plaid.enrich.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GuidGenerator Unit Tests")
class GuidGeneratorTest {

    private GuidGenerator guidGenerator;

    @BeforeEach
    void setUp() {
        guidGenerator = new GuidGenerator();
    }

    @Test
    @DisplayName("Should generate valid UUID format")
    void shouldGenerateValidUuid() {
        // When
        String guid = guidGenerator.generate();

        // Then
        assertThat(guid).isNotNull();
        assertThat(guid).hasSize(36);
        assertThat(guid).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    @Test
    @DisplayName("Should generate unique GUIDs")
    void shouldGenerateUniqueGuids() {
        // Given
        Set<String> guids = new HashSet<>();
        int iterations = 10000;

        // When
        for (int i = 0; i < iterations; i++) {
            guids.add(guidGenerator.generate());
        }

        // Then
        assertThat(guids).hasSize(iterations);
    }

    @Test
    @DisplayName("Should validate valid GUID")
    void shouldValidateValidGuid() {
        // Given
        String validGuid = "550e8400-e29b-41d4-a716-446655440000";

        // When
        boolean isValid = guidGenerator.isValid(validGuid);

        // Then
        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Should invalidate null GUID")
    void shouldInvalidateNullGuid() {
        // When
        boolean isValid = guidGenerator.isValid(null);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Should invalidate empty GUID")
    void shouldInvalidateEmptyGuid() {
        // When
        boolean isValid = guidGenerator.isValid("");

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Should invalidate malformed GUID")
    void shouldInvalidateMalformedGuid() {
        // Given
        String malformedGuid = "not-a-guid";

        // When
        boolean isValid = guidGenerator.isValid(malformedGuid);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Should normalize GUID to lowercase")
    void shouldNormalizeGuid() {
        // Given
        String mixedCaseGuid = "550E8400-E29B-41D4-A716-446655440000";

        // When
        String normalized = guidGenerator.normalize(mixedCaseGuid);

        // Then
        assertThat(normalized).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("Should throw exception when normalizing invalid GUID")
    void shouldThrowExceptionOnInvalidNormalize() {
        // Given
        String invalidGuid = "invalid";

        // When/Then
        assertThatThrownBy(() -> guidGenerator.normalize(invalidGuid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should parse generated GUID back to UUID")
    void shouldParseGeneratedGuid() {
        // Given
        String guid = guidGenerator.generate();

        // When/Then
        assertThat(UUID.fromString(guid)).isNotNull();
    }

    @Test
    @DisplayName("Should generate UUID version 4 (version nibble must be '4')")
    void shouldGenerateUuidVersion4() {
        // UUID format: xxxxxxxx-xxxx-Mxxx-Nxxx-xxxxxxxxxxxx
        // Index 14 (after two groups and their dashes) is the version nibble M
        String guid = guidGenerator.generate();
        assertThat(guid.charAt(14)).isEqualTo('4');
    }

    @Test
    @DisplayName("Should generate UUID with IETF variant bits (first char of 4th group must be 8/9/a/b)")
    void shouldGenerateUuidWithCorrectVariantBits() {
        // UUID format: xxxxxxxx-xxxx-Mxxx-Nxxx-xxxxxxxxxxxx
        // Index 19 (first char of the 4th group) encodes the IETF variant: must be 8, 9, a, or b
        String guid = guidGenerator.generate();
        char variantNibble = guid.charAt(19);
        assertThat(variantNibble).isIn('8', '9', 'a', 'b');
    }
}
