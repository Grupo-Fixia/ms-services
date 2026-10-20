package com.fixia.services.client;

import com.fixia.services.config.UsersClientProperties;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pregunta a ms-users si un técnico es elegible para una o varias solicitudes. Los datos del técnico (categorías,
 * zona de atención y disponibilidad) viven en la base de ms-users (Database per Service), que es quien evalúa las
 * reglas; este servicio solo envía lo que sabe de cada solicitud. Ruta interna
 * {@code POST /internal/technicians/{userId}/eligibility}, autenticada con la clave compartida.
 *
 * <p>Acepta varias solicitudes en una sola llamada (hasta 100, el máximo de ms-users): la usa el listado de
 * oportunidades compatibles ({@code OpportunityService}, GC-337), que consulta por lotes de ese tamaño.
 */
@Component
public class TechnicianEligibilityClient {

    static final String API_KEY_HEADER = "X-Internal-Api-Key";

    /** Lo que ms-users necesita de cada solicitud. {@code expectedAt} viaja como texto ISO-8601. */
    record RequestData(UUID requestId, ServiceCategory category, String city, Double latitude, Double longitude,
                       String expectedAt) {
    }

    record EligibilityQuery(List<RequestData> requests) {
    }

    /** Resultado por solicitud. {@code reasons} son códigos estables como {@code CATEGORY}, {@code ZONE}. */
    public record Result(UUID requestId, Boolean eligible, List<String> reasons) {
    }

    record EligibilityAnswer(List<Result> results) {
    }

    private final RestClient restClient;
    private final UsersClientProperties properties;

    public TechnicianEligibilityClient(@Qualifier("usersRestClient") RestClient restClient,
                                       UsersClientProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * @return el resultado de elegibilidad del técnico para la solicitud
     * @throws EligibilityCheckException si no se pudo obtener una respuesta válida de ms-users
     */
    public Result check(UUID technicianUserId, ServiceRequest request) {
        return checkAll(technicianUserId, List.of(request)).get(request.getId());
    }

    /**
     * @return un resultado por cada solicitud, indexado por su id
     * @throws EligibilityCheckException si no se pudo obtener una respuesta válida y completa de ms-users
     */
    public Map<UUID, Result> checkAll(UUID technicianUserId, List<ServiceRequest> requests) {
        EligibilityQuery query = new EligibilityQuery(requests.stream().map(TechnicianEligibilityClient::data).toList());
        EligibilityAnswer answer;
        try {
            answer = restClient.post()
                    .uri("/internal/technicians/{userId}/eligibility", technicianUserId)
                    .header(API_KEY_HEADER, properties.internalApiKey() == null ? "" : properties.internalApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(query)
                    .retrieve()
                    .body(EligibilityAnswer.class);
        } catch (RestClientException e) {
            throw new EligibilityCheckException("ms-users no pudo evaluar la elegibilidad del técnico", e);
        }
        if (answer == null || answer.results() == null) {
            throw new EligibilityCheckException("ms-users respondió sin resultados de elegibilidad");
        }
        Map<UUID, Result> byRequest = new HashMap<>();
        for (Result result : answer.results()) {
            if (result == null || result.requestId() == null || result.eligible() == null) {
                throw new EligibilityCheckException("ms-users respondió un resultado de elegibilidad incompleto");
            }
            byRequest.put(result.requestId(), result);
        }
        for (ServiceRequest request : requests) {
            if (!byRequest.containsKey(request.getId())) {
                throw new EligibilityCheckException("ms-users no evaluó todas las solicitudes consultadas");
            }
        }
        return byRequest;
    }

    private static RequestData data(ServiceRequest request) {
        ApproximateLocation location = request.getApproximateLocation();
        return new RequestData(request.getId(), request.getCategory(), location.city(), location.latitude(),
                location.longitude(), request.getExpectedAt().toString());
    }
}
