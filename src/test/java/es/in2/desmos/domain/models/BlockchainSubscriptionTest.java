package es.in2.desmos.domain.models;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockchainSubscriptionTest {

    private final List<String> eventTypes = List.of("ProductOffering", "Catalogue", "Category");
    private final String notificationEndpoint = "https://localhost:8080/api/v1/notifications/dlt";

    @Test
    void testBuilderAndLombokGeneratedMethods() {
        // Act
        BlockchainSubscription blockchainSubscription = BlockchainSubscription.builder()
                .eventTypes(eventTypes)
                .notificationEndpoint(notificationEndpoint)
                .build();
        // Assert
        assertEquals(eventTypes, blockchainSubscription.eventTypes());
        assertEquals(notificationEndpoint, blockchainSubscription.notificationEndpoint());
    }

    @Test
    void testImmutability() {
        BlockchainSubscription blockchainSubscription = BlockchainSubscription.builder()
                .eventTypes(eventTypes)
                .build();
        List<String> eventTypeList = blockchainSubscription.eventTypes();
        Assert.assertThrows(UnsupportedOperationException.class, () -> eventTypeList.add("ProductOfferingPrize"));
    }

    @Test
    void testToString() {
        // Arrange
        BlockchainSubscription blockchainSubscription = BlockchainSubscription.builder()
                .eventTypes(eventTypes)
                .notificationEndpoint(notificationEndpoint)
                .build();
        // Act
        String result = blockchainSubscription.toString();
        // Assert
        assertTrue(result.contains("eventTypes=" + eventTypes));
        assertTrue(result.contains("notificationEndpoint=" + notificationEndpoint));
    }

    @Test
    void testBlockchainNotificationBuilderToString() {
        // Arrange
        String expectedToString = "BlockchainSubscription.BlockchainSubscriptionBuilder(" +
                "eventTypes=[ProductOffering, Catalogue, Category], " +
                "notificationEndpoint=https://localhost:8080/api/v1/notifications/dlt)";
        // Act
        BlockchainSubscription.BlockchainSubscriptionBuilder blockchainSubscriptionBuilder = BlockchainSubscription.builder()
                .eventTypes(eventTypes)
                .notificationEndpoint(notificationEndpoint);
        // Assert
        assertEquals(expectedToString, blockchainSubscriptionBuilder.toString());
    }

}
