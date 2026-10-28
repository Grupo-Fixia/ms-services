package com.fixia.services.client;

import com.fixia.services.config.UsersClientProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consultas a ms-users sobre empresas (GC-468). Las empresas y sus técnicos asociados viven en la base de ms-users
 * (GC-466); este servicio no los copia, solo pregunta por las rutas internas, con la clave compartida.
 *
 * <p>La empresa siempre se identifica por la cuenta de su administrador ({@code users.id}, el {@code sub} del token con
 * el que se postuló).
 */
@Component
public class CompanyClient {

    static final int MAX_PER_CALL = 100;

    record AssociationQuery(UUID companyAdminUserId, UUID technicianUserId) {
    }

    record AssociationAnswer(Boolean associated) {
    }

    record ProfilesQuery(List<UUID> companyAdminUserIds) {
    }

    /** Nombre comercial de la empresa; nunca su NIT ni los datos de la cuenta. */
    public record CompanyProfile(UUID companyAdminUserId, String name) {
    }

    record ProfilesAnswer(List<CompanyProfile> profiles) {
    }

    private final RestClient restClient;
    private final UsersClientProperties properties;

    public CompanyClient(@Qualifier("usersRestClient") RestClient restClient, UsersClientProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Si el técnico pertenece a la empresa de esa cuenta. ms-users responde "no" sin distinguir el motivo (cuenta
     * inexistente, deshabilitada, de otro rol o técnico de otra empresa).
     *
     * @throws EligibilityCheckException si no se pudo obtener una respuesta válida: sin ella no se postula (falla
     *                                   cerrado, igual que la elegibilidad)
     */
    public boolean isTechnicianAssociated(UUID companyAdminUserId, UUID technicianUserId) {
        AssociationAnswer answer;
        try {
            answer = restClient.post()
                    .uri("/internal/companies/technician-association")
                    .header(TechnicianEligibilityClient.API_KEY_HEADER, apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AssociationQuery(companyAdminUserId, technicianUserId))
                    .retrieve()
                    .body(AssociationAnswer.class);
        } catch (RestClientException e) {
            throw new EligibilityCheckException("ms-users no pudo verificar la asociación del técnico", e);
        }
        if (answer == null || answer.associated() == null) {
            throw new EligibilityCheckException("ms-users respondió sin la asociación del técnico");
        }
        return answer.associated();
    }

    /**
     * @return el perfil de cada empresa activa, indexado por la cuenta de su administrador; las que ms-users ya no
     *         reconoce simplemente no están
     * @throws ProfileLookupException si no se pudo obtener una respuesta válida de ms-users
     */
    public Map<UUID, CompanyProfile> findPublicProfiles(Collection<UUID> companyAdminUserIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(companyAdminUserIds));
        Map<UUID, CompanyProfile> byCompany = new HashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_PER_CALL) {
            for (CompanyProfile profile : fetch(ids.subList(from, Math.min(from + MAX_PER_CALL, ids.size())))) {
                if (profile == null || profile.companyAdminUserId() == null) {
                    throw new ProfileLookupException("ms-users respondió un perfil de empresa incompleto");
                }
                byCompany.put(profile.companyAdminUserId(), profile);
            }
        }
        return byCompany;
    }

    private List<CompanyProfile> fetch(List<UUID> batch) {
        ProfilesAnswer answer;
        try {
            answer = restClient.post()
                    .uri("/internal/companies/public-profiles")
                    .header(TechnicianEligibilityClient.API_KEY_HEADER, apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ProfilesQuery(batch))
                    .retrieve()
                    .body(ProfilesAnswer.class);
        } catch (RestClientException e) {
            throw new ProfileLookupException("ms-users no pudo entregar el perfil de las empresas", e);
        }
        if (answer == null || answer.profiles() == null) {
            throw new ProfileLookupException("ms-users respondió sin perfiles de empresa");
        }
        return answer.profiles();
    }

    private String apiKey() {
        return properties.internalApiKey() == null ? "" : properties.internalApiKey();
    }
}
