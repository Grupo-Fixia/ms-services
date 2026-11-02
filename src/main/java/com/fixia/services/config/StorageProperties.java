package com.fixia.services.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fixia.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String bucketEvidences,
        String accessKey,
        String secretKey
) {
}

