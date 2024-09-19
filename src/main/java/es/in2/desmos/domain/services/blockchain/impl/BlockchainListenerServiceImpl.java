package es.in2.desmos.domain.services.blockchain.impl;

import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.DomeParticipantService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.blockchain.BlockchainListenerService;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.domain.services.blockchain.adapter.factory.BlockchainAdapterFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
public class BlockchainListenerServiceImpl implements BlockchainListenerService {

    private final BlockchainAdapterService blockchainAdapterService;
    private final AuditRecordService auditRecordService;
    private final QueueService pendingSubscribeEventsQueue;
    private final DomeParticipantService domeParticipantService;

    public BlockchainListenerServiceImpl(BlockchainAdapterFactory blockchainAdapterFactory, AuditRecordService auditRecordService,
                                         QueueService pendingSubscribeEventsQueue, DomeParticipantService domeParticipantService) {
        this.blockchainAdapterService = blockchainAdapterFactory.getBlockchainAdapter();
        this.auditRecordService = auditRecordService;
        this.pendingSubscribeEventsQueue = pendingSubscribeEventsQueue;
        this.domeParticipantService = domeParticipantService;
    }

    @Override
    public Mono<Void> createSubscription(String processId, BlockchainSubscription blockchainSubscription) {
        return checkIfSubscriptionExists(processId, Mono.just(blockchainSubscription))
                .flatMap(x -> {
                    if (Boolean.TRUE.equals(x)) {
                        return Mono.empty();
                    } else {
                        return blockchainAdapterService.createSubscription(processId, blockchainSubscription);
                    }
                });
    }

    @Override
    public Mono<Void> processBlockchainNotification(String processId, BlockchainNotification blockchainNotification) {
        // Create and AuditRecord with status RECEIVED
        return domeParticipantService.validateDomeParticipant(processId, blockchainNotification.ethereumAddress())
                .flatMap(domeParticipant -> auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId, blockchainNotification,
                                null, AuditRecordStatus.RECEIVED)
                        // Set priority for the pendingSubscribeEventsQueue event
                        .then(Mono.just(EventQueuePriority.MEDIUM))
                        // Enqueue DLTNotification to DataRetrievalQueue
                        .flatMap(eventQueuePriority -> {
                            log.debug("ProcessID: {} - Enqueuing Blockchain Notification to DataRetrievalQueue...", processId);
                            return pendingSubscribeEventsQueue.enqueueEvent(EventQueue.builder()
                                    .event(Collections.singletonList(blockchainNotification))
                                    .priority(eventQueuePriority)
                                    .build());
                        })
                );
    }

    private Mono<Boolean> checkIfSubscriptionExists(String processId, Mono<BlockchainSubscription> subscriptionToCreateMono) {
        return blockchainAdapterService
                .getSubscriptions(processId)
                .flatMap(existingSubscription ->
                        checkIfTwoSubscriptionsAreEquals(subscriptionToCreateMono, Mono.just(existingSubscription))
                                .filter(Boolean::booleanValue)
                                .hasElement()
                ).any(Boolean::booleanValue)
                .defaultIfEmpty(false);
    }

    private Mono<Boolean> checkIfTwoSubscriptionsAreEquals(
            Mono<BlockchainSubscription> sub1Mono,
            Mono<BlockchainSubscription> sub2Mono) {

        return Mono.zip(sub1Mono, sub2Mono)
                .map(tuple -> {
                    BlockchainSubscription sub1 = tuple.getT1();
                    BlockchainSubscription sub2 = tuple.getT2();

                    boolean areEventTypesEqual = new HashSet<>(sub1.eventTypes()).equals(new HashSet<>(sub2.eventTypes()));

                    boolean isNotificationEndpointEqual = sub1.notificationEndpoint().equals(sub2.notificationEndpoint());

                    return areEventTypesEqual && isNotificationEndpointEqual;
                });
    }

}
