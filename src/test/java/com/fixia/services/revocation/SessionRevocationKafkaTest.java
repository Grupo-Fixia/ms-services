package com.fixia.services.revocation;

import com.fixia.services.config.TokenRevocationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Revocación por eventos (GC-547) de punta a punta contra un broker Kafka en memoria: lo que publica ms-users en
 * {@code fixia.users.sessions} llega a la lista local, el consumidor confirma con el broker que está al día, y el token
 * revocado se rechaza mientras uno vigente sigue sirviendo, sin consultar a ms-users.
 */
@SpringBootTest(properties = {
        "fixia.revocations.consumer.enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 2, topics = "fixia.users.sessions")
@ActiveProfiles("test")
@DirtiesContext
class SessionRevocationKafkaTest {

    private static final String TOPIC = "fixia.users.sessions";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private RevocationList list;

    @Autowired
    private TokenRevocationValidator validator;

    private static String revoked(String jti, Instant expiresAt) {
        return """
                {"eventId":"%s","eventType":"sesion.revocada","schemaVersion":1,"producer":"ms-users",
                 "aggregate":{"type":"Session","id":"%s","sequence":1},
                 "data":{"jti":"%s","expiresAt":"%s","revokedAt":"%s"}}
                """.formatted(UUID.randomUUID(), jti, jti, expiresAt, Instant.now());
    }

    private static Jwt jwt(String jti) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", "usr_1").jti(jti)
                .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(600)).build();
    }

    @Test
    void laListaAlDiaRechazaElTokenRevocadoYAceptaUnoVigente() throws Exception {
        String jti = UUID.randomUUID().toString();
        String vigente = UUID.randomUUID().toString();

        kafka.send(TOPIC, jti, "{no es json").get();
        kafka.send(TOPIC, jti, revoked(jti, Instant.now().plusSeconds(900))).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(list.contains(jti)).isTrue();
            assertThat(list.isFresh()).isTrue();
        });
        assertThat(validator.validate(jwt(jti)).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(vigente)).hasErrors()).isFalse();
    }
}
