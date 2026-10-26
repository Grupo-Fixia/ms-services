package com.fixia.services.client;

import com.fixia.services.config.UsersClientProperties;
import com.fixia.services.domain.ServiceCategory;
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
 * Pide a ms-users el perfil público de los prestadores postulados (GC-425). Los datos del técnico viven en la base de
 * ms-users (Database per Service); este servicio no los guarda ni los copia, solo los consulta para mostrárselos al
 * cliente. Ruta interna {@code POST /internal/technicians/public-profiles}, autenticada con la clave compartida.
 *
 * <p>Consulta por lotes de hasta {@value #MAX_PER_CALL} técnicos, el máximo de ms-users.
 */
@Component
public class TechnicianProfileClient {

    static final int MAX_PER_CALL = 100;

    /**
     * Perfil público del técnico tal como lo entrega ms-users: nombre corto, verificación, especialidades,
     * descripción, experiencia y ciudad. Los campos que el técnico no ha llenado llegan en {@code null}.
     */
    public record PublicProfile(UUID userId, String displayName, String verificationStatus,
                                List<ServiceCategory> categories, String professionalDescription,
                                Integer yearsOfExperience, String city) {
    }

    record ProfilesQuery(List<UUID> userIds) {
    }

    record ProfilesAnswer(List<PublicProfile> profiles) {
    }

    private final RestClient restClient;
    private final UsersClientProperties properties;

    public TechnicianProfileClient(@Qualifier("usersRestClient") RestClient restClient,
                                   UsersClientProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * @return el perfil de cada técnico que ms-users conoce como técnico activo, indexado por su {@code users.id}.
     *         Un técnico que no aparece (por ejemplo, con la cuenta deshabilitada) simplemente no está en el mapa.
     * @throws ProfileLookupException si no se pudo obtener una respuesta válida de ms-users
     */
    public Map<UUID, PublicProfile> findPublicProfiles(Collection<UUID> userIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(userIds));
        Map<UUID, PublicProfile> byUser = new HashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_PER_CALL) {
            List<UUID> batch = ids.subList(from, Math.min(from + MAX_PER_CALL, ids.size()));
            for (PublicProfile profile : fetch(batch)) {
                if (profile == null || profile.userId() == null) {
                    throw new ProfileLookupException("ms-users respondió un perfil público incompleto");
                }
                byUser.put(profile.userId(), profile);
            }
        }
        return byUser;
    }

    private List<PublicProfile> fetch(List<UUID> batch) {
        ProfilesAnswer answer;
        try {
            answer = restClient.post()
                    .uri("/internal/technicians/public-profiles")
                    .header(TechnicianEligibilityClient.API_KEY_HEADER,
                            properties.internalApiKey() == null ? "" : properties.internalApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ProfilesQuery(batch))
                    .retrieve()
                    .body(ProfilesAnswer.class);
        } catch (RestClientException e) {
            throw new ProfileLookupException("ms-users no pudo entregar el perfil de los técnicos", e);
        }
        if (answer == null || answer.profiles() == null) {
            throw new ProfileLookupException("ms-users respondió sin perfiles");
        }
        return answer.profiles();
    }
}
