package es.in2.desmos.domain.services.broker.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.BrokerEntityWithIdAndType;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.services.broker.adapter.ScorpioAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

import static es.in2.desmos.domain.utils.ApplicationUtils.extractEntityIdFromDataLocation;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerPublisherServiceImpl implements BrokerPublisherService {

    private static final String VALUE_FIELD_NAME = "value";
    private final ScorpioAdapter scorpioAdapter;

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> publishDataToBroker(String processId, BlockchainNotification blockchainNotification, String retrievedBrokerEntity) {
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
    public <T extends BrokerEntityWithIdAndType> Mono<List<T>> findAllIdTypeAndAttributesByType(String processId, String type, String firstAttribute, String secondAttribute, String thirdAttribute, String forthAttribute, Class<T[]> responseClassArray) {
        return scorpioAdapter.findAllIdTypeAndAttributesByType(processId, type, firstAttribute, secondAttribute, thirdAttribute, forthAttribute, responseClassArray)
                .map(array -> Arrays.stream(array).toList());
    }

    @Override
    public Mono<List<String>> findAllById(String processId, Mono<List<Id>> idsMono, List<Id> processedEntities) {
        return idsMono.flatMapMany(Flux::fromIterable)
                .flatMap(id -> {
                    if (!processedEntities.contains(id)) {
                        return scorpioAdapter.getEntityById(processId, id.id())
                                .flatMap(entity -> {
                                    processedEntities.add(id);
                                    return getEntityRelationshipIds(Mono.just(entity))
                                            .flatMapMany(Flux::fromIterable)
                                            .flatMap(relatedId -> findAllById(processId, Mono.just(List.of(relatedId)), processedEntities))
                                            .collectList()
                                            .map(relatedEntities -> {
                                                List<String> resultList = relatedEntities.stream()
                                                        .flatMap(List::stream)
                                                        .collect(Collectors.toList());
                                                resultList.add(entity);
                                                return resultList;
                                            });
                                });
                    } else {
                        return Flux.empty();
                    }
                })
                .collectList()
                .flatMap(listsList -> {
                    List<String> resultList = new ArrayList<>();
                    for (List<String> list : listsList) {
                        resultList.addAll(list);
                    }
                    return Mono.just(resultList);
                });
    }

    @Override
    public Mono<String> getEntityById(String processId, String entityId) {
        return scorpioAdapter.getEntityById(processId, entityId);
    }

    @Override
    public Mono<Void> postEntity(String processId, String requestBody) {
        return scorpioAdapter.postEntity(processId, requestBody);
    }

    private Mono<List<Id>> getEntityRelationshipIds(Mono<String> entityMono) {
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
                                String propertyFieldName = "Property";
                                if (fieldType.equals(relationshipFieldName) && rootEntityNodeFieldValue.has(objectFieldName)) {
                                    return Mono.just(new Id(rootEntityNodeFieldValue.get(objectFieldName).asText()));
                                } else if (fieldType.equals(propertyFieldName) && rootEntityNodeFieldValue.has(VALUE_FIELD_NAME)) {
                                    var jsonArray = rootEntityNodeFieldValue.get(VALUE_FIELD_NAME);
                                    return Flux.fromIterable(jsonArray)
                                            .flatMap(arrayElement -> getEntityRelationshipIdsFromArray(Mono.just(arrayElement.toString())));
                                }
                            } else if (rootEntityNodeFieldValue.isArray()) {
                                return Flux.fromIterable(rootEntityNodeFieldValue)
                                        .flatMap(arrayElement -> getEntityRelationshipIdsFromArray(Mono.just(arrayElement.toString())));
                            }
                            return Mono.empty();
                        })
                        .collectList();
            } catch (JsonProcessingException e) {
                return Mono.error(e);
            }
        });
    }

    private Mono<Id> getEntityRelationshipIdsFromArray(Mono<String> entityMono) {
        return entityMono.flatMap(entity -> {
            try {
                JsonNode rootEntityJsonNode = objectMapper.readTree(entity);

                String typeFieldName = "type";

                if (rootEntityJsonNode.isObject() &&
                        rootEntityJsonNode.has(typeFieldName)) {

                    String fieldType = rootEntityJsonNode.get(typeFieldName).asText();

                    String relationshipFieldName = "Relationship";
                    String objectFieldName = "object";
                    if (fieldType.equals(relationshipFieldName) && rootEntityJsonNode.has(objectFieldName)) {
                        return Mono.just(new Id(rootEntityJsonNode.get(objectFieldName).asText()));
                    }
                }

                return Mono.empty();


            } catch (JsonProcessingException e) {
                return Mono.error(e);
            }
        });
    }

    private Mono<Void> updateEntity(String processId, String requestBody) {
        return scorpioAdapter.updateEntity(processId, requestBody);
    }

}
