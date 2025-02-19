package es.in2.desmos.domain.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;

import java.util.List;

public record DiscoverySyncResponse(
        @JsonProperty("issuer") @NotBlank @URL String issuer,
        @JsonProperty("external_minimum_viable_entities_for_data_negotiation_list") @NotNull List<MVEntity4DataNegotiation> entities) {
}
