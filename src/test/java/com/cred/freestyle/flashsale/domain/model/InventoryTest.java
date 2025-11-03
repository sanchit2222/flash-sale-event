package com.cred.freestyle.flashsale.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for Inventory domain model.
 */
@DisplayName("Inventory Domain Model Tests")
class InventoryTest {

    @Test
    @DisplayName("Should correctly report stock as available when availableCount > 0")
    void shouldReportStockAvailable() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setAvailableCount(50);

        // When
        boolean isAvailable = inventory.isAvailable();

        // Then
        assertThat(isAvailable).isTrue();
    }

    @Test
    @DisplayName("Should correctly report stock as unavailable when availableCount is 0")
    void shouldReportStockUnavailable() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setAvailableCount(0);

        // When
        boolean isAvailable = inventory.isAvailable();

        // Then
        assertThat(isAvailable).isFalse();
    }

    @Test
    @DisplayName("Should correctly report stock as unavailable when availableCount is null")
    void shouldReportStockUnavailableWhenNull() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setAvailableCount(null);

        // When
        boolean isAvailable = inventory.isAvailable();

        // Then
        assertThat(isAvailable).isFalse();
    }

    @Test
    @DisplayName("Should correctly report product as sold out when no stock available")
    void shouldReportProductSoldOut() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(100);
        inventory.setAvailableCount(0);
        inventory.setReservedCount(0);
        inventory.setSoldCount(100);

        // When
        boolean isSoldOut = inventory.isSoldOut();

        // Then
        assertThat(isSoldOut).isTrue();
    }

    @Test
    @DisplayName("Should correctly report product as sold out when all reserved or sold")
    void shouldReportProductSoldOutWhenAllCommitted() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(100);
        inventory.setAvailableCount(0);
        inventory.setReservedCount(30);
        inventory.setSoldCount(70);

        // When
        boolean isSoldOut = inventory.isSoldOut();

        // Then
        assertThat(isSoldOut).isTrue();
    }

    @Test
    @DisplayName("Should correctly report product as not sold out when stock available")
    void shouldReportProductNotSoldOut() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(100);
        inventory.setAvailableCount(50);
        inventory.setReservedCount(20);
        inventory.setSoldCount(30);

        // When
        boolean isSoldOut = inventory.isSoldOut();

        // Then
        assertThat(isSoldOut).isFalse();
    }

    @Test
    @DisplayName("Should initialize counts correctly on prePersist")
    void shouldInitializeCountsOnPrePersist() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(100);
        // Don't set reservedCount, soldCount, or availableCount - let @PrePersist handle it

        // When
        inventory.onCreate(); // Manually call the lifecycle method

        // Then
        assertThat(inventory.getInventoryId()).isNotNull();
        assertThat(inventory.getCreatedAt()).isNotNull();
        assertThat(inventory.getUpdatedAt()).isNotNull();
        assertThat(inventory.getReservedCount()).isEqualTo(0);
        assertThat(inventory.getSoldCount()).isEqualTo(0);
        assertThat(inventory.getAvailableCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should recalculate availableCount on preUpdate")
    void shouldRecalculateAvailableCountOnPreUpdate() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(100);
        inventory.setReservedCount(20);
        inventory.setSoldCount(30);
        inventory.setAvailableCount(0); // Initially wrong

        // When
        inventory.onUpdate(); // Manually call the lifecycle method

        // Then
        assertThat(inventory.getAvailableCount()).isEqualTo(50); // 100 - 20 - 30
        assertThat(inventory.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should maintain correct data relationships")
    void shouldMaintainCorrectDataRelationships() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(100);
        inventory.setReservedCount(25);
        inventory.setSoldCount(45);

        // When
        inventory.onUpdate();

        // Then
        int calculated = inventory.getAvailableCount() +
                        inventory.getReservedCount() +
                        inventory.getSoldCount();
        assertThat(calculated).isEqualTo(inventory.getTotalCount());
    }

    @Test
    @DisplayName("Should handle edge case with null availableCount in isSoldOut")
    void shouldHandleNullAvailableCountInIsSoldOut() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(100);
        inventory.setAvailableCount(null);
        inventory.setReservedCount(50);
        inventory.setSoldCount(50);

        // When/Then
        // Should not throw exception
        boolean isSoldOut = inventory.isSoldOut();
        assertThat(isSoldOut).isFalse(); // Null check prevents true
    }

    @Test
    @DisplayName("Should correctly identify inventory with stock partially committed")
    void shouldIdentifyPartiallyCommittedInventory() {
        // Given
        Inventory inventory = new Inventory();
        inventory.setTotalCount(1000);
        inventory.setAvailableCount(600);
        inventory.setReservedCount(150);
        inventory.setSoldCount(250);

        // When/Then
        assertThat(inventory.isAvailable()).isTrue();
        assertThat(inventory.isSoldOut()).isFalse();

        // Verify the sum makes sense
        int total = inventory.getAvailableCount() +
                    inventory.getReservedCount() +
                    inventory.getSoldCount();
        assertThat(total).isEqualTo(inventory.getTotalCount());
    }
}
