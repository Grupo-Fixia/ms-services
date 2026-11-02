package com.fixia.services.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MatchingClientProperties.class)
public class MatchingClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MatchingClientConfig.class);

    @Bean
    public RestClient matchingRestClient(MatchingClientProperties properties) {
        if (!properties.hasInternalApiKey()) {
            log.warn("INTERNAL_API_KEY no está configurada: ms-matching-geo rechazará la consulta de técnicos "
                    + "compatibles y el endpoint responderá 503");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
