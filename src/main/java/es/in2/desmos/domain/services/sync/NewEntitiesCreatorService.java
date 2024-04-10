package es.in2.desmos.domain.services.sync;

import reactor.core.publisher.Mono;

import java.util.List;

public interface NewEntitiesCreatorService {
    Mono<Void> addNewEntities(Mono<List<String>> externalEntityIds, Mono<String> issuer);
}
