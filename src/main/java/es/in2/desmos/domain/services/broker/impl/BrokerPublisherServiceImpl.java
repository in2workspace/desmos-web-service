package es.in2.desmos.domain.services.broker.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVBrokerEntity4DataNegotiation;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.broker.adapter.BrokerAdapterService;
import es.in2.desmos.domain.services.broker.adapter.factory.BrokerAdapterFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static es.in2.desmos.domain.utils.ApplicationUtils.extractEntityIdFromDataLocation;

@Slf4j
@Service
public class BrokerPublisherServiceImpl implements BrokerPublisherService {

    private final BrokerAdapterService brokerAdapterService;

    private final ObjectMapper objectMapper;

    public BrokerPublisherServiceImpl(BrokerAdapterFactory brokerAdapterFactory, ObjectMapper objectMapper) {
        this.brokerAdapterService = brokerAdapterFactory.getBrokerAdapter();
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> publishEntityToContextBroker(String processId, BlockchainNotification blockchainNotification, String retrievedBrokerEntity) {
        // Get the entity ID from the data location in the blockchain notification.
        // This is used to check if the retrieved entity exists in the local broker or not.
        // If it exists, the entity will be updated, otherwise, it will be created.
        String entityId = extractEntityIdFromDataLocation(blockchainNotification.dataLocation());
        return getEntityById(processId, entityId)
                .switchIfEmpty(Mono.just(""))
                .flatMap(response -> {
                    if (response.isBlank()) {
                        log.info("ProcessID: {} - Entity doesn't exist", processId);
                        // Logic for when the entity does not exist, for example, creating it
                        return postEntity(processId, retrievedBrokerEntity);
                    } else {
                        // Logic for when the entity exists
                        log.info("ProcessId: {} - Entity exists", processId);
                        return updateEntity(processId, retrievedBrokerEntity);
                    }
                });
    }

    @Override
    public Mono<List<MVBrokerEntity4DataNegotiation>> getMVBrokerEntities4DataNegotiation(String processId, String type, String firstAttribute, String secondAttribute) {
        return brokerAdapterService.getMVBrokerEntities4DataNegotiation(processId, type, firstAttribute, secondAttribute);
    }

    @Override
    public Mono<Void> batchUpsertEntitiesToContextBroker(String processId, String retrievedBrokerEntities) {
        return batchUpsertEntities(processId, retrievedBrokerEntities);
    }

    @Override
    public Mono<List<String>> findAllById(String processId, Mono<List<Id>> idsMono) {
        return idsMono
                .flatMapIterable(ids -> ids)
                .flatMap(id -> brokerAdapterService.getEntityById(processId, id.id())
                        .flatMap(entity -> {
                            List<String> newList = new ArrayList<>();
                            newList.add(entity);
                            Mono<String> entityMono = Mono.just(entity);
                            Mono<List<Id>> entityRelationshipIdsMono = getEntityRelationShipIds(entityMono);
                            return entityRelationshipIdsMono.flatMap(entityRelationshipIds ->
                            {
                                Mono<List<Id>> entityRelationshipMonoMono = Mono.just(entityRelationshipIds);
                                return findAllById(processId, entityRelationshipMonoMono).flatMap(relationshipsEntities -> {
                                    newList.addAll(relationshipsEntities);
                                    return Mono.just(newList);
                                });
                            });
                        }))
                .collectList()
                .flatMap(listsList -> {
                    List<String> resultList = new ArrayList<>();
                    for (List<String> list : listsList) {
                        resultList.addAll(list);
                    }
                    return Mono.just(resultList);
                });
    }

    private Mono<List<Id>> getEntityRelationShipIds(Mono<String> entityMono) {
        return entityMono.flatMap(entity -> {
            try {
                JsonNode rootEntityJsonNode = objectMapper.readTree(entity);

                return Flux.fromIterable(rootEntityJsonNode::fields)
                        .flatMap(rootEntityNodeField -> {
                            JsonNode rootEntityNodeFieldValue = rootEntityNodeField.getValue();

                            String typeFieldName = "type";
                            if (rootEntityNodeFieldValue.isObject() &&
                                    rootEntityNodeFieldValue.has(typeFieldName)) {
                                String fieldType = rootEntityNodeFieldValue.get(typeFieldName).asText();

                                String relationshipFieldName = "Relationship";
                                String objectFieldName = "object";
                                if (fieldType.equals(relationshipFieldName) && rootEntityNodeFieldValue.has(objectFieldName)) {
                                    return Mono.just(new Id(rootEntityNodeFieldValue.get(objectFieldName).asText()));
                                }
                            }
                            return Mono.empty();
                        })
                        .collectList();
            } catch (JsonProcessingException e) {
                return Mono.error(e);
            }
        });
    }


    private Mono<Void> batchUpsertEntities(String processId, String requestBody) {
        return brokerAdapterService.batchUpsertEntities(processId, requestBody);
    }

    private Mono<Void> postEntity(String processId, String requestBody) {
        return brokerAdapterService.postEntity(processId, requestBody);
    }

    private Mono<String> getEntityById(String processId, String entityId) {
        return brokerAdapterService.getEntityById(processId, entityId);
    }

    private Mono<Void> updateEntity(String processId, String requestBody) {
        return brokerAdapterService.updateEntity(processId, requestBody);
    }

}
