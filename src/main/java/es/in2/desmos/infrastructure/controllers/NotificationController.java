package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.services.blockchain.BlockchainListenerService;
import es.in2.desmos.domain.services.broker.BrokerListenerService;
import es.in2.desmos.infrastructure.security.JwtTokenProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final BrokerListenerService brokerListenerService;
    private final BlockchainListenerService blockchainListenerService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/broker")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> postBrokerNotification(@RequestBody @Valid BrokerNotification brokerNotification) {
        String processId = UUID.randomUUID().toString();
//        String id = getIdFromDataMap(brokerNotification.data());
        log.info("ProcessID: {} - Broker Notification received", processId);
//        log.debug("ProcessID: {} - Broker Notification received: {}", processId, brokerNotification);
        return brokerListenerService.processBrokerNotification(processId, brokerNotification);
    }

    @PostMapping("/dlt")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> postDLTNotification(@RequestBody @Valid BlockchainNotification blockchainNotification) {
        String processId = UUID.randomUUID().toString();
        log.info("ProcessID: {} - Blockchain Notification received with DataLocation: {}", processId, blockchainNotification.dataLocation());
        log.debug("ProcessID: {}, Blockchain Notification received: {}", processId, blockchainNotification);
        return blockchainListenerService.processBlockchainNotification(processId, blockchainNotification);
    }

//    private String getIdFromDataMap(@NotNull(message = "data cannot be null") List<Map<String, Object>> data) {
//        return data.get(0).get("id").toString();
//    }

}
