package com.fixia.services.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        String endpoint = properties.endpoint() != null && !properties.endpoint().isBlank()
                ? properties.endpoint() : "http://rustfs:9000";
        String region = properties.region() != null && !properties.region().isBlank()
                ? properties.region() : "us-east-1";
        String accessKey = properties.accessKey() != null ? properties.accessKey() : "rustfsadmin";
        String secretKey = properties.secretKey() != null ? properties.secretKey() : "rustfsadmin";

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                // Una subida mantiene bloqueada la solicitud: un almacenamiento caído debe fallar rápido (503).
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .apiCallTimeout(Duration.ofSeconds(20))
                        .retryPolicy(RetryPolicy.builder().numRetries(1).build())
                        .build())
                .build();
    }
}

