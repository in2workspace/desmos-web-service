package es.in2.desmos.application.workflows.jobs.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.jobs.DataNegotiationJob;
import es.in2.desmos.application.workflows.jobs.DataTransferJob;
import es.in2.desmos.domain.models.DataNegotiationEvent;
import es.in2.desmos.domain.models.DataNegotiationResult;
import es.in2.desmos.domain.models.Issuer;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataNegotiationJobImpl implements DataNegotiationJob {
    private final DataTransferJob dataTransferJob;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> negotiateDataSyncWithMultipleIssuers(String processId, Mono<Map<Issuer, List<MVEntity4DataNegotiation>>> externalMVEntities4DataNegotiationByIssuerMono, Mono<List<MVEntity4DataNegotiation>> localMVEntities4DataNegotiationMono) {
        log.info("ProcessID: {} - Starting Data Negotiation Job", processId);

        return localMVEntities4DataNegotiationMono.flatMap(localMVEntities4DataNegotiation ->
                externalMVEntities4DataNegotiationByIssuerMono
                        .flatMapIterable(Map::entrySet)
                        .flatMap(externalMVEntities4DataNegotiationByIssuer ->
                                getCorrectLifecycleStatusMVEntities(Mono.just(externalMVEntities4DataNegotiationByIssuer.getValue()))
                                        .flatMap(externalMvEntities4DataNegotiation ->
                                                getCorrectValidForMVEntities(Mono.just(externalMvEntities4DataNegotiation))
                                                        .flatMap(validExternalMvEntities4DataNegotiation -> {
                                                            Mono<List<MVEntity4DataNegotiation>> externalMVEntities4DataNegotiationMono = Mono.just(validExternalMvEntities4DataNegotiation);
                                                            return checkWithExternalDataIsMissing(externalMVEntities4DataNegotiationMono, localMVEntities4DataNegotiationMono)
                                                                    .zipWith(checkVersionsAndLastUpdateFromEntityIdMatched(externalMVEntities4DataNegotiationMono, localMVEntities4DataNegotiationMono))
                                                                    .flatMap(tuple -> {
                                                                        List<MVEntity4DataNegotiation> newEntitiesToSync = tuple.getT1();
                                                                        List<MVEntity4DataNegotiation> existingEntitiesToSync = tuple.getT2();

                                                                        log.debug("ProcessID: {} - New entities to sync: {}", processId, newEntitiesToSync);
                                                                        log.debug("ProcessID: {} - Existing entities to sync: {}", processId, existingEntitiesToSync);

                                                                        Mono<String> issuerMono = Mono.just(externalMVEntities4DataNegotiationByIssuer.getKey().value());
                                                                        return createDataNegotiationResult(issuerMono, Mono.just(newEntitiesToSync), Mono.just(existingEntitiesToSync));
                                                                    });

                                                        })))
                        .collectList()
                        .flatMap(dataNegotiationResults -> dataTransferJob.syncDataFromList(processId, Mono.just(dataNegotiationResults))));
    }

    @Override
    public Mono<Void> negotiateDataSyncFromEvent(DataNegotiationEvent dataNegotiationEvent) {
        String processId = dataNegotiationEvent.processId();

        log.info("ProcessID: {} - Starting Data Negotiation Job", processId);

        Mono<List<MVEntity4DataNegotiation>> externalMVEntities4DataNegotiationMono = dataNegotiationEvent.externalMVEntities4DataNegotiation();
        Mono<List<MVEntity4DataNegotiation>> localMVEntities4DataNegotiationMono = dataNegotiationEvent.localMVEntities4DataNegotiation();

        return checkWithExternalDataIsMissing(externalMVEntities4DataNegotiationMono, localMVEntities4DataNegotiationMono)
                .zipWith(checkVersionsAndLastUpdateFromEntityIdMatched(externalMVEntities4DataNegotiationMono, localMVEntities4DataNegotiationMono))
                .flatMap(tuple -> {
                    List<MVEntity4DataNegotiation> externalMVEntities4DataNegotiation = tuple.getT1();
                    List<MVEntity4DataNegotiation> localMVEntities4DataNegotiation = tuple.getT2();

                    log.debug("ProcessID: {} - External MV Entities 4 Data Negotiation: {}", processId, externalMVEntities4DataNegotiation);
                    log.debug("ProcessID: {} - Local MV Entities 4 Data Negotiation: {}", processId, localMVEntities4DataNegotiation);

                    return createDataNegotiationResult(dataNegotiationEvent, Mono.just(externalMVEntities4DataNegotiation), Mono.just(localMVEntities4DataNegotiation));
                })
                .flatMap(dataNegotiationResult -> {
                    log.debug("ProcessID: {} - Data Negotiation Result: {}", dataNegotiationEvent.processId(), dataNegotiationResult);
                    return dataTransferJob.syncData(dataNegotiationEvent.processId(), Mono.just(dataNegotiationResult));
                });
    }

    private Mono<List<MVEntity4DataNegotiation>> checkWithExternalDataIsMissing(
            Mono<List<MVEntity4DataNegotiation>> externalEntityIds,
            Mono<List<MVEntity4DataNegotiation>> localEntityIds) {
        return externalEntityIds.zipWith(localEntityIds)
                .map(tuple -> {
                    List<MVEntity4DataNegotiation> originalList = tuple.getT1();
                    Set<String> idsToCheck = tuple.getT2().stream()
                            .map(MVEntity4DataNegotiation::id)
                            .collect(Collectors.toSet());

                    return originalList.stream()
                            .filter(entity -> !idsToCheck.contains(entity.id()))
                            .toList();
                });
    }

    private Mono<List<MVEntity4DataNegotiation>> getCorrectLifecycleStatusMVEntities(
            Mono<List<MVEntity4DataNegotiation>> externalMVEntityListMono) {
        return externalMVEntityListMono
                .map(externalMVEntityList -> externalMVEntityList
                        .stream()
                        .filter(mvEntity -> mvEntity.lifecycleStatus().equals("Launched") || mvEntity.lifecycleStatus().equals("Retired"))
                        .toList());
    }

    private Mono<List<MVEntity4DataNegotiation>> getCorrectValidForMVEntities(
            Mono<List<MVEntity4DataNegotiation>> externalMVEntityListMono) {
        return externalMVEntityListMono
                .flatMap(externalMVEntityList ->
                        Flux.fromIterable(externalMVEntityList)
                                .filterWhen(mvEntity -> isValidFor(Mono.just(mvEntity)))
                                .collectList());
    }

    private Mono<Boolean> isValidFor(Mono<MVEntity4DataNegotiation> mvEntityMono) {
        return mvEntityMono.flatMap(mvEntity -> {
            ZonedDateTime startDateTime = ZonedDateTime.parse(mvEntity.validFor(), DateTimeFormatter.ISO_DATE_TIME);

            ZonedDateTime now = ZonedDateTime.now();

            return Mono.just(!now.isBefore(startDateTime));
        });
    }


    private Mono<List<MVEntity4DataNegotiation>> checkVersionsAndLastUpdateFromEntityIdMatched(
            Mono<List<MVEntity4DataNegotiation>> externalEntityIds,
            Mono<List<MVEntity4DataNegotiation>> localEntityIds) {
        return externalEntityIds.zipWith(localEntityIds)
                .map(tuple -> {
                    List<MVEntity4DataNegotiation> externalList = tuple.getT1();
                    List<MVEntity4DataNegotiation> localList = tuple.getT2();

                    return externalList
                            .stream()
                            .filter(externalEntity ->
                                    localList
                                            .stream()
                                            .filter(localEntity -> localEntity.id().equals(externalEntity.id()))
                                            .findFirst()
                                            .map(sameLocalEntity ->
                                                    {
                                                        Float externalEntityVersion = externalEntity.getFloatVersion();
                                                        Float sameLocalEntityVersion = sameLocalEntity.getFloatVersion();
                                                        return isExternalEntityVersionNewer(
                                                                externalEntityVersion,
                                                                sameLocalEntityVersion) ||
                                                                (isVersionEqual(
                                                                        externalEntityVersion,
                                                                        sameLocalEntityVersion) &&
                                                                        isExternalEntityLastUpdateNewer(
                                                                                externalEntity.getInstantLastUpdate(),
                                                                                sameLocalEntity.getInstantLastUpdate()));
                                                    }
                                            )
                                            .orElse(false))
                            .toList();
                });
    }

    private boolean isExternalEntityVersionNewer(Float externalEntityVersion, Float sameLocalEntityVersion) {
        return externalEntityVersion > sameLocalEntityVersion;
    }

    private boolean isVersionEqual(Float externalEntityVersion, Float sameLocalEntityVersion) {
        return Objects.equals(externalEntityVersion, sameLocalEntityVersion);
    }

    private boolean isExternalEntityLastUpdateNewer(Instant externalEntityLastUpdate, Instant sameLocalEntityLastUpdate) {
        return externalEntityLastUpdate.isAfter(sameLocalEntityLastUpdate);
    }

    private Mono<DataNegotiationResult> createDataNegotiationResult(DataNegotiationEvent dataNegotiationEvent, Mono<List<MVEntity4DataNegotiation>> newEntitiesToSync, Mono<List<MVEntity4DataNegotiation>> existingEntitiesToSync) {
        return Mono.zip(dataNegotiationEvent.issuer(), newEntitiesToSync, existingEntitiesToSync).map(
                tuple -> {
                    String issuer = tuple.getT1();
                    List<MVEntity4DataNegotiation> newEntitiesToSyncValue = tuple.getT2();
                    List<MVEntity4DataNegotiation> existingEntitiesToSyncValue = tuple.getT3();

                    return new DataNegotiationResult(issuer, newEntitiesToSyncValue, existingEntitiesToSyncValue);
                }
        );
    }

    private Mono<DataNegotiationResult> createDataNegotiationResult(Mono<String> issuerMono, Mono<List<MVEntity4DataNegotiation>> newEntitiesToSync, Mono<List<MVEntity4DataNegotiation>> existingEntitiesToSync) {
        return Mono.zip(issuerMono, newEntitiesToSync, existingEntitiesToSync).map(
                tuple -> {
                    String issuer = tuple.getT1();
                    List<MVEntity4DataNegotiation> newEntitiesToSyncValue = tuple.getT2();
                    List<MVEntity4DataNegotiation> existingEntitiesToSyncValue = tuple.getT3();

                    return new DataNegotiationResult(issuer, newEntitiesToSyncValue, existingEntitiesToSyncValue);
                }
        );
    }
}