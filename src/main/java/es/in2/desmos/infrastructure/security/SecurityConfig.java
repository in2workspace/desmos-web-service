package es.in2.desmos.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import es.in2.desmos.domain.exceptions.InvalidProfileException;
import es.in2.desmos.domain.models.AccessNodeOrganization;
import es.in2.desmos.domain.models.AccessNodeYamlData;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.cache.AccessNodeMemoryStore;
import es.in2.desmos.infrastructure.configs.properties.AccessNodeProperties;
import es.in2.desmos.infrastructure.security.filters.BearerTokenReactiveAuthenticationManager;
import es.in2.desmos.infrastructure.security.filters.ServerHttpBearerAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static es.in2.desmos.domain.utils.ApplicationConstants.YAML_FILE_SUFFIX;

@Slf4j
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtVerifier;
    private final AccessNodeMemoryStore accessNodeMemoryStore;
    private final AccessNodeProperties accessNodeProperties;
    private final ApiConfig apiConfig;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());


    /**
     * For Spring Security webflux, a chain of filters will provide user authentication
     * and authorization; we add custom filters to enable JWT token approach.
     *
     * @param http An initial object to build common filter scenarios.
     *             Customized filters are added here.
     * @return SecurityWebFilterChain A filter chain for web exchanges that will
     * provide security
     **/
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/health").permitAll()
                        .pathMatchers("/api/v1/notifications/broker").permitAll()
                        .pathMatchers("/api/v1/notifications/dlt").permitAll()
                        .pathMatchers("/api/v1/entities/*").authenticated() //replication endpoint
                        .pathMatchers("/api/v1/sync/p2p/*").authenticated() //synchronization endpoint
                        .anyExchange().authenticated()
                )
                .csrf(csrf -> csrf
                        .requireCsrfProtectionMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/**"))
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .disable() // Disable CSRF protection for specific paths
                )
                .addFilterAt(bearerAuthenticationFilter(), SecurityWebFiltersOrder.AUTHENTICATION);
        return http.build();


    }

    /**
     * Use the already implemented logic by AuthenticationWebFilter and set a custom
     * converter that will handle requests containing a Bearer token inside
     * the HTTP Authorization header.
     * Set a dummy authentication manager to this filter, it's not needed because
     * the converter handles this.
     *
     * @return bearerAuthenticationFilter that will authorize requests containing a JWT
     */
    private AuthenticationWebFilter bearerAuthenticationFilter() {
        AuthenticationWebFilter bearerAuthenticationFilter;
        Function<ServerWebExchange, Mono<Authentication>> bearerConverter;
        ReactiveAuthenticationManager authManager;
        authManager = new BearerTokenReactiveAuthenticationManager();
        bearerAuthenticationFilter = new AuthenticationWebFilter(authManager);
        bearerConverter = new ServerHttpBearerAuthenticationConverter(accessNodeMemoryStore,jwtVerifier);
        bearerAuthenticationFilter
                .setAuthenticationConverter(bearerConverter);
        bearerAuthenticationFilter
                .setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/**"));
        return bearerAuthenticationFilter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        //corsConfig.setAllowedOrigins(getCorsUrls());
        corsConfig.setAllowedOrigins(new ArrayList<>(Arrays.asList(
                "/api/v1/sync/p2p/**",
                "/api/v1/entities/**",
                "/api/v1/notifications/broker",
                "/api/v1/notifications/dlt"
        )));
        corsConfig.setMaxAge(8000L);
        corsConfig.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.HEAD.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));
        corsConfig.setMaxAge(1800L);
        corsConfig.addAllowedHeader("*");
        corsConfig.addExposedHeader("*");
        corsConfig.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig); // Apply the configuration to all paths
        return source;
    }

    private List<String> getCorsUrls(){

        log.debug("Retrieving YAML data from the external source repository...");
        // Get the External URL from configuration
        String repoPath = accessNodeProperties.prefixDirectory() + getExternalYamlProfile() + YAML_FILE_SUFFIX;

        log.debug("External URL: {}", repoPath);
        // Retrieve YAML data from the External URL

        apiConfig.webClient().get()
                .uri(repoPath)
                .retrieve()
                .bodyToMono(String.class)
                .handle((yamlContent, sink) -> {
                    AccessNodeYamlData data;
                    try {
                        data = yamlMapper.readValue(yamlContent, AccessNodeYamlData.class);
                    } catch (JsonProcessingException e) {
                        sink.error(new RuntimeException(e));
                        return;
                    }
                    log.debug("AccessNodeYamlData: {}", data);
                    accessNodeMemoryStore.setOrganizations(data);
                }).block();


        List<String> urls = new ArrayList<>();
        AccessNodeYamlData yamlData = accessNodeMemoryStore.getOrganizations();
        if (yamlData == null || yamlData.getOrganizations() == null) {
            log.warn("No organizations data available in AccessNodeMemoryStore.");
            return urls;
        }
        for (AccessNodeOrganization org : yamlData.getOrganizations()) {
            urls.add(org.getUrl());
        }

        return urls;
    }

    private String getExternalYamlProfile() {
        String profile = apiConfig.getCurrentEnvironment();

        if (profile == null) {
            throw new InvalidProfileException("Environment variable SPRING_PROFILES_ACTIVE is not set");
        }

        return switch (profile) {
            case "default", "dev" -> "sbx";
            case "test" -> "dev";
            case "prod" -> "prd";
            default -> throw new InvalidProfileException("Invalid profile: " + profile);
        };
    }

}
