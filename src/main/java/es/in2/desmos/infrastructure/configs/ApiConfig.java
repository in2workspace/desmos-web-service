package es.in2.desmos.infrastructure.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.exceptions.HashCreationException;
import es.in2.desmos.infrastructure.configs.properties.OpenApiProperties;
import es.in2.desmos.infrastructure.configs.properties.OperatorProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.NoSuchAlgorithmException;
import java.util.List;

import static es.in2.desmos.domain.utils.ApplicationUtils.calculateSHA256;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ApiConfig {

    private final OpenApiProperties openApiProperties;
    private final OperatorProperties operatorProperties;
    private final Environment environment;

    @Bean
    public String organizationIdHash() {
        try {
            return calculateSHA256(operatorProperties.organizationIdentifier());
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new HashCreationException("Error creating organizationIdentifier hash: " + e.getMessage());
        }
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .contact(new Contact()
                                .email(openApiProperties.info().contact().email())
                                .name(openApiProperties.info().contact().name())
                                .url(openApiProperties.info().contact().url()))
                        .license(new License()
                                .name(openApiProperties.info().license().name())
                                .url(openApiProperties.info().license().url())))
                .servers(List.of(new Server()
                        .url(openApiProperties.server().url())
                        .description(openApiProperties.server().description())));
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }

    @Bean
    public String getCurrentEnvironment() {
        List<String> profiles = List.of(environment.getActiveProfiles());
        if (profiles.isEmpty()) {
            log.debug(environment.getDefaultProfiles()[0]);
            return environment.getDefaultProfiles()[0];
        } else {
            log.debug(environment.getActiveProfiles()[0]);
            return profiles.get(0);
        }
    }

    @Bean
    public String getOperatorExternalDomain(){
        return operatorProperties.externalDomain();
    }

}