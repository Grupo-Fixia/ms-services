package com.fixia.services.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(UsersClientProperties.class)
public class UsersClientConfig {

    private static final Logger log = LoggerFactory.getLogger(UsersClientConfig.class);

    @Bean
    public RestClient usersRestClient(UsersClientProperties properties) {
        if (!properties.hasInternalApiKey()) {
            log.warn("INTERNAL_API_KEY no está configurada: ms-users rechazará la consulta de revocación y "
                    + "todas las peticiones autenticadas serán rechazadas (401)");
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
