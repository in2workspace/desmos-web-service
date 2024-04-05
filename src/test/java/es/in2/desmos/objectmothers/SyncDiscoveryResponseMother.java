package es.in2.desmos.objectmothers;

import es.in2.desmos.domain.models.ProductOffering;
import es.in2.desmos.domain.models.SyncDiscoveryResponse;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class SyncDiscoveryResponseMother {
    private SyncDiscoveryResponseMother() {
    }

    public static @NotNull SyncDiscoveryResponse simpleSyncDiscoveryResponse() {
        String issuer = "https://my-domain.org";
        List<ProductOffering> productOfferingList = new ArrayList<ProductOffering>();
        productOfferingList.add(new ProductOffering("urn:productOffering:d86735a6-0faa-463d-a872-00b97affa1cb", "1.2", "2024-04-01T12:00:00Z"));
        productOfferingList.add(new ProductOffering("urn:productOffering:ed9c56c8-a5ab-42cc-bc62-0fca69a30c87", "1.5", "2024-09-01T12:00:00Z"));
        return new SyncDiscoveryResponse(issuer, productOfferingList);
    }
}
