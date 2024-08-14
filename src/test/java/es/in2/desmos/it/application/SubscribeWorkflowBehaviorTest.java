package es.in2.desmos.it.application;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.DomeParticipant;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.repositories.DomeParticipantRepository;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.it.ContainerManager;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscribeWorkflowBehaviorTest {

    private final Logger log = LoggerFactory.getLogger(SubscribeWorkflowBehaviorTest.class);

    private final ObjectMapper objectMapper =
            JsonMapper.builder()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .build();

    String request = """
            {
                "id": "urn:ngsi-ld:ProductOffering:122355255",
                "type": "ProductOffering",
                "name": {
                    "type": "Property",
                    "value": "ProductOffering 1"
                },
                "description": {
                    "type": "Property",
                    "value": "ProductOffering 1 description"
                }
            }""";

    String updateRequest = """
            {
                "id": "urn:ngsi-ld:ProductOffering:122355255",
                "type": "ProductOffering",
                "name": {
                    "type": "Property",
                    "value": "ProductOffering 2"
                },
                "description": {
                    "type": "Property",
                    "value": "ProductOffering 2 description"
                }
            }""";

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private QueueService pendingSubscribeEventsQueue;

    @Autowired
    private DomeParticipantRepository domeParticipantRepository;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    private static String getString() {
        String url = ContainerManager.getBaseUriForScorpioA();
        return String.format("""
                {
                    "id": 2240,
                    "publisherAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90",
                    "eventType": "ProductOffering",
                    "timestamp": 1712753824,
                    "dataLocation": "%s/ngsi-ld/v1/entities/urn:ngsi-ld:ProductOffering:122355255?hl=abbc168236d38354add74d65698f37941947127290cd40a90b4dbe7eb68d25c0",
                    "relevantMetadata": [],
                    "entityId": "0x4eb401aa1248b6a95c298d0747eb470b6ba6fc3f54ea630dc6c77f23ad1abe3e",
                    "previousEntityHash": "0xabbc168236d38354add74d65698f37941947127290cd40a90b4dbe7eb68d25c0",
                    "ethereumAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90"
                }""", url);
    }

    private static String getBlockchainNotificationJson() {
        String url = ContainerManager.getBaseUriForScorpioA();
        return String.format("""
                {
                    "id": 2240,
                    "publisherAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90",
                    "eventType": "ProductOffering",
                    "timestamp": 1712753824,
                    "dataLocation": "%s/ngsi-ld/v1/entities/urn:ngsi-ld:ProductOffering:122355255?hl=64f516030ab35b7f18d8d06054079e85070ea04f1c0dab5291be8184a28025fa",
                    "relevantMetadata": [],
                    "entityId": "0x4eb401aa1248b6a95c298d0747eb470b6ba6fc3f54ea630dc6c77f23ad1abe3e",
                    "previousEntityHash": "0xabbc168236d38354add74d65698f37941947127290cd40a90b4dbe7eb68d25c0",
                    "ethereumAddress": "0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90"
                }""", url);
    }

    /*
        Given a BlockchainNotification, we will send a POST request emulating the broker behavior.
        When the POST request is received, the application will retrieve a BrokerEntity from the external broker,
        and then it will send a POST request to the local broker.
        Finally, the application will create an AuditRecord with the status of the operation.
    */
    @Order(0)
    @Test
    void subscribeWorkflowBehaviorTest() {
        // Need to insert a DomeParticipant in the database, if not, the test will fail because of the validation
        domeParticipantRepository.save(DomeParticipant.builder()
                .ethereumAddress("0x40b0ab9dfd960064fb7e9fdf77f889c71569e349055ff563e8d699d8fa97fa90")
                .build()).block();

        log.info("Starting Subscribe Workflow Behavior Test...");
        log.info("1. Send a POST request to the external broker in order to retrieve the entity later");
        WebClient.create().post()
                .uri(ContainerManager.getBaseUriForScorpioA() + "/ngsi-ld/v1/entities")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatusCode.valueOf(409)), response -> Mono.empty())
                .bodyToMono(String.class).block();

        String blockchainNotificationJson = getString();
        // When
        try {
            log.info("1. Create a BlockchainNotification and send a POST request to the application");
            BlockchainNotification blockchainNotification = objectMapper.readValue(blockchainNotificationJson, BlockchainNotification.class);
            StepVerifier.create(notificationController.postDLTNotification(blockchainNotification))
                    .verifyComplete();
            log.info("1.1. Get the event stream from the pendingSubscribeQueue and subscribe to it.");
            pendingSubscribeEventsQueue.getEventStream().subscribe(event -> log.info("Event: {}", event));
            log.info("2. Check values in the AuditRecord table:");
            List<AuditRecord> auditRecordList = auditRecordRepository.findAll().collectList().block();
            log.info("Result: {}", auditRecordList);
        } catch (Exception e) {
            log.error("Error while sending the BlockchainNotification: {}", e.getMessage());
        }
    }

    @Order(1)
    @Test
    void subscribeWorkflowBehaviorTest_UPDATE() {
        log.info("Starting Subscribe Workflow Behavior Test...");
        log.info("2. Send a POST request to the external broker in order to retrieve the entity later");
        WebClient.create().patch()
                .uri(ContainerManager.getBaseUriForScorpioA() + "/ngsi-ld/v1/entities" + "/urn:ngsi-ld:ProductOffering:122355255/attrs")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequest)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatusCode.valueOf(409)), response -> Mono.empty())
                .bodyToMono(String.class).block();

        String blockchainNotificationJson = getBlockchainNotificationJson();
        // When
        try {
            log.info("1. Create a BlockchainNotification and send a POST request to the application");
            BlockchainNotification blockchainNotification = objectMapper.readValue(blockchainNotificationJson, BlockchainNotification.class);
            StepVerifier.create(notificationController.postDLTNotification(blockchainNotification))
                    .verifyComplete();
            log.info("1.1. Get the event stream from the pendingSubscribeQueue and subscribe to it.");
            pendingSubscribeEventsQueue.getEventStream().subscribe(event -> log.info("Event: {}", event));
            log.info("2. Check values in the AuditRecord table:");
            List<AuditRecord> auditRecordList = auditRecordRepository.findAll().collectList().block();
            log.info("Result: {}", auditRecordList);
        } catch (Exception e) {
            log.error("Error while sending the BlockchainNotification: {}", e.getMessage());
        }
    }

}
