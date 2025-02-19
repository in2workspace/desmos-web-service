package es.in2.desmos.objectmothers;

import es.in2.desmos.domain.models.BlockchainSubscription;

import java.util.List;

public final class BlockchainSubscriptionMother {

    private BlockchainSubscriptionMother() {
    }


    public static BlockchainSubscription sample() {
        List<String> eventTypes = List.of(
                "catalog",
                "product-offering",
                "category",
                "individual",
                "organization",
                "product",
                "service-specification",
                "product-offering-price",
                "resource-specification",
                "product-specification");

        List<String> metadata = List.of("dev");

        String notificationEndpoint = "/api/v1/notifications/dlt";

        return new BlockchainSubscription(eventTypes, metadata, notificationEndpoint);
    }

    public static BlockchainSubscription otherEventTypesSubscription() {
        List<String> eventTypes = List.of(
                "other thing",
                "other event");

        return new BlockchainSubscription(eventTypes, sample().metadata(), sample().notificationEndpoint());
    }

    public static BlockchainSubscription otherNotificationEndpointSubscription() {
        String notificationEndpoint = "/other/endpoint";

        return new BlockchainSubscription(sample().eventTypes(), sample().metadata(), notificationEndpoint);
    }

    public static BlockchainSubscription defaultConfigured() {
        List<String> eventTypes = List.of(
                "product-offering", "category", "catalog");

        List<String> metadata = List.of("local");

        String notificationEndpoint = "http://localhost:8081/api/v1/notifications/dlt";

        return new BlockchainSubscription(eventTypes, metadata, notificationEndpoint);
    }
}
