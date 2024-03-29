package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.DLTAdapterProperties;
import es.in2.desmos.infrastructure.configs.properties.EventSubscriptionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DLTConfig {

    private final DLTAdapterProperties dltAdapterProperties;
    private final EventSubscriptionProperties eventSubscriptionProperties;

    public String getNotificationEndpoint() {
        return eventSubscriptionProperties.notificationEndpoint();
    }

    public List<String> getEntityTypes() {
        return eventSubscriptionProperties.eventTypes();
    }

}
