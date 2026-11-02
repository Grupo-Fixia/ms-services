package com.fixia.services.client;

import com.fixia.services.config.MatchingClientProperties;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.exception.InvalidMatchingQueryException;
import com.fixia.services.exception.MatchingLookupException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pide a ms-matching-geo los técnicos compatibles con una solicitud (GC-474): los que ofrecen su categoría, atienden su
 * zona y tienen una franja que incluya su momento esperado. Ruta interna {@code POST /internal/matching/technicians},
 * autenticada con la clave compartida; no sale por el gateway.
 *
 * <p>Solo viajan los datos aproximados de la solicitud (categoría, ciudad, coordenadas redondeadas a ~1 km y momento
 * esperado): ni la dirección exacta ni nada del cliente. Falla cerrado: sin una respuesta válida de matching se lanza
 * {@link MatchingLookupException} y nunca se devuelve una lista vacía.
 */
@Component
public class MatchingClient {

    static final String PATH = "/internal/matching/technicians";

    /** Un técnico compatible tal como lo entrega matching: sin círculo de zona, distancia ni datos de contacto. */
    public record CompatibleTechnician(UUID userId, String displayName, List<String> categories, String city) {
    }

    /** Una página; puede venir corta, o vacía, con {@code nextCursor} no nulo (quedan técnicos por revisar). */
    public record CompatibleTechnicians(List<CompatibleTechnician> items, String nextCursor) {
    }

    record Answer(List<CompatibleTechnician> items, String nextCursor) {
    }

    private final RestClient restClient;
    private final MatchingClientProperties properties;

    public MatchingClient(@Qualifier("matchingRestClient") RestClient restClient,
                          MatchingClientProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * @param limit  técnicos por página, de 1 a 50
     * @param cursor el {@code nextCursor} de la página anterior, o {@code null} para empezar
     * @throws InvalidMatchingQueryException si matching rechaza el cursor
     * @throws MatchingLookupException        si no se pudo obtener una respuesta válida
     */
    public CompatibleTechnicians findCompatible(ServiceRequest request, int limit, String cursor) {
        Answer answer;
        try {
            answer = restClient.post()
                    .uri(PATH)
                    .header(TechnicianEligibilityClient.API_KEY_HEADER,
                            properties.internalApiKey() == null ? "" : properties.internalApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(request, limit, cursor))
                    .retrieve()
                    .body(Answer.class);
        } catch (HttpClientErrorException e) {
            // La consulta sale de una solicitud ya validada: un 400 con cursor solo puede ser un cursor ajeno.
            if (e.getStatusCode().value() == 400 && cursor != null) {
                throw InvalidMatchingQueryException.invalidCursor();
            }
            throw new MatchingLookupException("ms-matching-geo respondió " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new MatchingLookupException("ms-matching-geo no pudo entregar los técnicos compatibles", e);
        }
        return validated(answer);
    }

    /** Solo lo aproximado; los nulos se omiten (matching exige latitud y longitud juntas o ninguna). */
    private static Map<String, Object> body(ServiceRequest request, int limit, String cursor) {
        ApproximateLocation location = request.getApproximateLocation();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", request.getCategory().name());
        body.put("city", location.city());
        if (location.latitude() != null && location.longitude() != null) {
            body.put("latitude", location.latitude());
            body.put("longitude", location.longitude());
        }
        body.put("expectedAt", request.getExpectedAt().toString());
        body.put("limit", limit);
        if (cursor != null) {
            body.put("cursor", cursor);
        }
        return body;
    }

    private static CompatibleTechnicians validated(Answer answer) {
        if (answer == null || answer.items() == null) {
            throw new MatchingLookupException("ms-matching-geo respondió sin técnicos");
        }
        List<CompatibleTechnician> items = answer.items().stream().map(item -> {
            if (item == null || item.userId() == null) {
                throw new MatchingLookupException("ms-matching-geo respondió un técnico incompleto");
            }
            return new CompatibleTechnician(item.userId(), item.displayName(),
                    item.categories() == null ? List.of() : List.copyOf(item.categories()), item.city());
        }).toList();
        return new CompatibleTechnicians(items, answer.nextCursor());
    }
}
