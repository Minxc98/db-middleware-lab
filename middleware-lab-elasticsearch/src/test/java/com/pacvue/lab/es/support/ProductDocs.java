package com.pacvue.lab.es.support;

import com.pacvue.lab.es.domain.ProductDoc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test data builders. Keep fixtures here so assertions stay about behaviour, not setup. */
public final class ProductDocs {

    private ProductDocs() {
    }

    public static ProductDoc of(String title, String brand) {
        return new ProductDoc(
                UUID.randomUUID().toString(),
                title,
                brand,
                "B0" + Integer.toHexString(title.hashCode()).toUpperCase(),
                new BigDecimal("19.99"),
                Instant.now());
    }

    public static List<ProductDoc> sample() {
        return List.of(
                of("Wireless Bluetooth Headphones", "Acme"),
                of("Bluetooth Speaker Waterproof", "Acme"),
                of("USB C Charging Cable", "Globex"));
    }
}
