package com.fixia.services.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Carga de la clave pública y validación del token (firma, expiración, emisor, audiencia y validadores
 * adicionales). Los tokens se firman con un par generado en la prueba.
 */
class JwtConfigTest {

    private static final String ISSUER = "https://auth.fixia.com";
    private static final String AUDIENCE = "https://api.fixia.com";

    private final JwtConfig config = new JwtConfig();

    private static JwtProperties properties(String publicKey) {
        return new JwtProperties(ISSUER, AUDIENCE, publicKey);
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    // ---------- Clave pública ----------

    @Test
    void cargaLaClavePublicaDesdePem() throws Exception {
        KeyPair pair = newKeyPair();

        RSAPublicKey key = config.jwtPublicKey(
                properties(pem("PUBLIC KEY", pair.getPublic().getEncoded())), new MockEnvironment());

        assertThat(key.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
    }

    @Test
    void aceptaSaltosDeLineaEscritosComoBarraN() throws Exception {
        KeyPair pair = newKeyPair();
        String pem = pem("PUBLIC KEY", pair.getPublic().getEncoded()).replace("\n", "\\n");

        RSAPublicKey key = config.jwtPublicKey(properties(pem), new MockEnvironment());

        assertThat(key.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
    }

    @Test
    void unPemInvalidoFallaSinMostrarElContenidoDeLaClave() {
        String secret = "ESTO-NO-ES-UNA-CLAVE";

        assertThatThrownBy(() -> config.jwtPublicKey(properties(secret), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PEM válido")
                .hasMessageNotContaining(secret);
    }

    @Test
    void fueraDeLosPerfilesLocalYTestSinClaveElArranqueFalla() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> config.jwtPublicKey(properties(""), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("obligatoria");
        assertThatThrownBy(() -> config.jwtPublicKey(properties(null), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enLosPerfilesLocalYTestSinClaveSeUsaUnaEfimera() {
        for (String profile : List.of("local", "test")) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);

            RSAPublicKey key = config.jwtPublicKey(properties(""), environment);

            assertThat(key.getModulus().bitLength()).isEqualTo(2048);
        }
    }

    // ---------- Validación del token ----------

    @Test
    void unTokenVigenteDeMsUsersSeAcepta() throws Exception {
        KeyPair pair = newKeyPair();
        JwtDecoder decoder = decoder(pair);

        Jwt jwt = decoder.decode(token(pair, claims -> { }));

        assertThat(jwt.getSubject()).startsWith("usr_");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("ROLE_CLIENT");
    }

    @Test
    void unTokenExpiradoSeRechaza() throws Exception {
        KeyPair pair = newKeyPair();
        Instant past = Instant.now().minusSeconds(600);

        assertThatThrownBy(() -> decoder(pair).decode(token(pair, claims ->
                claims.issuedAt(past.minusSeconds(60)).expiresAt(past))))
                .hasMessageContaining("expired");
    }

    @Test
    void unTokenFirmadoConOtraClaveSeRechaza() throws Exception {
        KeyPair trusted = newKeyPair();
        KeyPair attacker = newKeyPair();

        assertThatThrownBy(() -> decoder(trusted).decode(token(attacker, claims -> { })))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void unTokenConEmisorOAudienciaDistintosSeRechaza() throws Exception {
        KeyPair pair = newKeyPair();

        assertThatThrownBy(() -> decoder(pair).decode(token(pair, claims -> claims.issuer("https://otro.com"))))
                .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder(pair).decode(token(pair, claims ->
                claims.audience(List.of("https://otra-api.com")))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("Audiencia no válida");
    }

    @Test
    void unTokenSinFirmaOBasuraSeRechaza() throws Exception {
        KeyPair pair = newKeyPair();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String unsigned = encoder.encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + encoder.encodeToString(("{\"sub\":\"usr_x\",\"iss\":\"" + ISSUER + "\"}").getBytes()) + ".";

        assertThatThrownBy(() -> decoder(pair).decode(unsigned)).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder(pair).decode("esto-no-es-un-jwt")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void losValidadoresAdicionalesFormanParteDeLaValidacion() throws Exception {
        KeyPair pair = newKeyPair();
        OAuth2TokenValidator<Jwt> rejectAll = jwt -> OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Token revocado", null));

        assertThatThrownBy(() -> decoder(pair, rejectAll).decode(token(pair, claims -> { })))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("Token revocado");
        assertThat(decoder(pair, jwt -> OAuth2TokenValidatorResult.success()).decode(token(pair, claims -> { })))
                .isNotNull();
    }

    // ---------- Utilidades ----------

    @SafeVarargs
    private static JwtDecoder decoder(KeyPair pair, OAuth2TokenValidator<Jwt>... extra) {
        return JwtConfig.buildDecoder((RSAPublicKey) pair.getPublic(), properties(null), List.of(extra));
    }

    private static String token(KeyPair pair, Consumer<JwtClaimsSet.Builder> customizer) throws Exception {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint()
                .build();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("usr_" + java.util.UUID.randomUUID())
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .id(java.util.UUID.randomUUID().toString())
                .claim("roles", List.of("ROLE_CLIENT"));
        customizer.accept(claims);
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }
}
