package com.fixia.services.config;

import com.fixia.services.revocation.RevocationList;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorrido completo de una petición autenticada: decoder real (firma RS256 con la clave pública configurada)
 * más la lista local de revocados (GC-547), que aquí se sustituye por un doble. Comprueba que un token revocado y una
 * lista que no está al día cierran el acceso (fail-closed), sin consultar a ms-users.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RevocationPipelineTest {

    private static final KeyPair TRUSTED = newKeyPair();
    private static final String LIST = "/api/services/requests";

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(TRUSTED.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        registry.add("fixia.jwt.public-key", () -> pem);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RevocationList revocationList;

    @Test
    void unTokenVigenteYNoRevocadoPasaYSeConsultaSuJti() throws Exception {
        String jti = UUID.randomUUID().toString();
        when(revocationList.isFresh()).thenReturn(true);

        list(token(TRUSTED, "ROLE_CLIENT", jti, Instant.now().plusSeconds(600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(revocationList).contains(jti);
    }

    @Test
    void unTokenRevocadoPorLogoutSeRechazaConElMismo401() throws Exception {
        when(revocationList.isFresh()).thenReturn(true);
        when(revocationList.contains(anyString())).thenReturn(true);

        expectUnauthorized(list(token(TRUSTED, "ROLE_CLIENT", UUID.randomUUID().toString(),
                Instant.now().plusSeconds(600))));
    }

    @Test
    void siLaListaNoEstaAlDiaLaPeticionSeRechazaFailClosed() throws Exception {
        when(revocationList.isFresh()).thenReturn(false);

        expectUnauthorized(list(token(TRUSTED, "ROLE_CLIENT", UUID.randomUUID().toString(),
                Instant.now().plusSeconds(600))));
    }

    @Test
    void unTokenVencidoSeRechazaSinConsultarLaLista() throws Exception {
        Instant past = Instant.now().minusSeconds(600);

        expectUnauthorized(list(token(TRUSTED, "ROLE_CLIENT", UUID.randomUUID().toString(), past)));

        verifyNoInteractions(revocationList);
    }

    @Test
    void unTokenFirmadoPorOtraClaveSeRechazaSinConsultarLaLista() throws Exception {
        expectUnauthorized(list(token(newKeyPair(), "ROLE_CLIENT", UUID.randomUUID().toString(),
                Instant.now().plusSeconds(600))));

        verifyNoInteractions(revocationList);
    }

    @Test
    void sinTokenNoSeConsultaLaLista() throws Exception {
        expectUnauthorized(mockMvc.perform(get(LIST)));

        verifyNoInteractions(revocationList);
    }

    @Test
    void unTecnicoConTokenValidoRecibe403() throws Exception {
        when(revocationList.isFresh()).thenReturn(true);

        list(token(TRUSTED, "ROLE_PROFESSIONAL", UUID.randomUUID().toString(), Instant.now().plusSeconds(600)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
    }

    @Test
    void elHealthCheckNoExigeTokenNiConsultaLaLista() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        verifyNoInteractions(revocationList);
    }

    // ---------- Utilidades ----------

    private ResultActions list(String token) throws Exception {
        return mockMvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.detail").value("Se requiere autenticación válida"));
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(KeyPair signer, String role, String jti, Instant expiresAt) throws Exception {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) signer.getPublic())
                .privateKey((RSAPrivateKey) signer.getPrivate())
                .keyIDFromThumbprint()
                .build();
        Instant issuedAt = expiresAt.minusSeconds(900).truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://auth.fixia.com")
                .subject("usr_" + UUID.randomUUID())
                .audience(List.of("https://api.fixia.com"))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt.truncatedTo(ChronoUnit.SECONDS))
                .id(jti)
                .claim("roles", List.of(role))
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
