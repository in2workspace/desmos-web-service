package es.in2.desmos.domain.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.configs.ApiConfig;
import es.in2.desmos.configs.BrokerConfig;
import es.in2.desmos.domain.exceptions.HashLinkException;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockchainTxPayloadFactoryTests {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private BrokerConfig brokerConfig;

    @InjectMocks
    private BlockchainTxPayloadFactory blockchainTxPayloadFactory;

    Map<String, Object> dataMap = new HashMap<>();

    @BeforeEach
    void setUp() {
        dataMap = new HashMap<>();
        dataMap.put("id", "entity123");
        dataMap.put("type", "productOffering");
        dataMap.put("name", "Cloud Services Suite");
        dataMap.put("description", "Example of a Product offering for cloud services suite");
    }

    @Test
    void testBuildBlockchainTxPayload_validData_firstHash_Success() throws Exception {
        //Arrange
        String processId = "processId";
        String previousHash = "5077272d496c8afd1af9d3740f9e5f11837089b5952d577eff4c20509e6e199e";
        when(objectMapper.writeValueAsString(dataMap)).thenReturn("dataMapString");
        when(apiConfig.organizationIdHash()).thenReturn("381d18e478b9ae6e67b1bf48c9f3bcaf246d53c4311bfe81f46e63aa18167c89");
        when(brokerConfig.getEntitiesExternalDomain()).thenReturn("http://localhost:8080/entities");

        // Act
        Mono<BlockchainTxPayload> resultMono = blockchainTxPayloadFactory.buildBlockchainTxPayload(processId, dataMap, previousHash);

        // Assert
        // Check that the previous hash is the same as the hash of the data, because it is the first hash
        StepVerifier.create(resultMono)
                .assertNext(blockchainTxPayload -> Assertions.assertEquals(previousHash, ApplicationUtils.extractHashLinkFromDataLocation(blockchainTxPayload.dataLocation()))).verifyComplete();


    }

    @Test
    void testBuildBlockchainTxPayload_validData_differentHashes_Success() throws Exception {
        // Arrange
        String processId = "processId";
        dataMap.put("description", "Example of a Product offering for cloud services suite");
        String previousHash = "22d0ef4e87a39c52191998f4fbf32ff672f82ed5a2b4c9902371a161402a0faf";
        when(objectMapper.writeValueAsString(dataMap)).thenReturn("dataMapString");
        when(apiConfig.organizationIdHash()).thenReturn("381d18e478b9ae6e67b1bf48c9f3bcaf246d53c4311bfe81f46e63aa18167c89");
        when(brokerConfig.getEntitiesExternalDomain()).thenReturn("http://localhost:8080/entities");

        Mono<BlockchainTxPayload> resultMono = blockchainTxPayloadFactory.buildBlockchainTxPayload(processId, dataMap, previousHash);

        // Assert
        // Check that the previous hash is different from the hash of the data, because it is not the first hash, it is the concatenation of the previous hash and the hash of the data
        StepVerifier.create(resultMono)
                .assertNext(blockchainTxPayload -> {
                    Assertions.assertNotEquals(previousHash, ApplicationUtils.extractHashLinkFromDataLocation(blockchainTxPayload.dataLocation()));
                    try {
                        Assertions.assertEquals(ApplicationUtils.calculateHashLink(previousHash, ApplicationUtils.calculateSHA256("dataMapString")), ApplicationUtils.extractHashLinkFromDataLocation(blockchainTxPayload.dataLocation()));
                    } catch (NoSuchAlgorithmException e) {
                        throw new HashLinkException("Error while calculating hash link on test");
                    }
                }).verifyComplete();

    }

    @Test
    void testBuildBlockchainTxPayload_invalidData_Failure() throws Exception {
        // Arrange
        String processId = "processId";
        String previousHash = "22d0ef4e87a39c52191998f4fbf32ff672f82ed5a2b4c9902371a161402a0faf";
        when(objectMapper.writeValueAsString(dataMap)).thenThrow(new JsonProcessingException("Error") {
        });


        // Act
        Mono<BlockchainTxPayload> resultMono = blockchainTxPayloadFactory.buildBlockchainTxPayload(processId, dataMap, previousHash);

        // Assert
        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable -> throwable instanceof HashLinkException && throwable.getMessage().contains("Error creating blockchain transaction payload"))
                .verify();
    }


    @Test
    void testCalculatePreviousHashIfEmpty_validData_Success() throws Exception {
        // Arrange
        String processId = "processId";
        when(objectMapper.writeValueAsString(dataMap)).thenReturn("dataMapString");

        // Act
        Mono<String> resultMono = blockchainTxPayloadFactory.calculatePreviousHashIfEmpty(processId, dataMap);

        // Assert
        StepVerifier.create(resultMono)
                .assertNext(previousHash -> {
                    try {
                        Assertions.assertEquals(ApplicationUtils.calculateSHA256("dataMapString"), previousHash);
                    } catch (NoSuchAlgorithmException e) {
                        throw new HashLinkException("Error while calculating hash link on test");
                    }
                }).verifyComplete();
    }

    @Test
    void testCalculatePreviousHashIfEmpty_invalidData_Failure() throws JsonProcessingException {
        // Arrange
        String processId = "processId";
        when(objectMapper.writeValueAsString(dataMap)).thenThrow(new JsonProcessingException("Error") {
        });

        // Act
        Mono<String> resultMono = blockchainTxPayloadFactory.calculatePreviousHashIfEmpty(processId, dataMap);

        // Assert
        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable -> throwable instanceof HashLinkException && throwable.getMessage().contains("Error creating previous hash value from notification data"))
                .verify();
    }

}
