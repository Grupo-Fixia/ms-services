package com.fixia.services.service;

import com.fixia.services.client.MatchingClient;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.exception.ServiceRequestNotAvailableException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Técnicos compatibles con una solicitud propia (GC-474). La solicitud sale de esta base y los técnicos, de
 * ms-matching-geo, que a su vez los consulta a ms-users (Database per Service): aquí no se copia ni se guarda ningún dato
 * del técnico.
 *
 * <p>No es transaccional a propósito: la consulta a matching va después de leer la solicitud, para no retener una
 * conexión de la base mientras se espera a otro servicio (como en {@link ApplicantService}).
 */
@Service
public class CompatibleTechnicianService {

    private final ServiceRequestService serviceRequestService;
    private final MatchingClient matchingClient;
    private final Clock clock;

    public CompatibleTechnicianService(ServiceRequestService serviceRequestService, MatchingClient matchingClient,
                                       Clock clock) {
        this.serviceRequestService = serviceRequestService;
        this.matchingClient = matchingClient;
        this.clock = clock;
    }

    /**
     * Solo para el cliente dueño (si la solicitud no existe o es de otro, la respuesta es la misma) y mientras la
     * solicitud sigue abierta: publicada y con el momento esperado por delante, la misma regla para postularse y
     * seleccionar. Sugerir técnicos para una solicitud que ya no recibe postulaciones no tendría sentido.
     *
     * @throws com.fixia.services.exception.ServiceRequestNotFoundException si la solicitud no es del cliente
     * @throws ServiceRequestNotAvailableException                          si la solicitud ya no está abierta (409)
     * @throws com.fixia.services.exception.MatchingLookupException         si matching no entrega una respuesta válida (503)
     * @throws com.fixia.services.exception.InvalidMatchingQueryException   si el cursor no es válido (400)
     */
    public MatchingClient.CompatibleTechnicians find(UUID clientId, UUID requestId, int limit, String cursor) {
        ServiceRequest request = serviceRequestService.get(clientId, requestId);
        if (!ServiceRequestApplication.isOpen(request, clock.instant())) {
            throw new ServiceRequestNotAvailableException(
                    "La solicitud ya no está publicada o su momento esperado ya pasó: no hay técnicos que sugerir");
        }
        return matchingClient.findCompatible(request, limit, cursor);
    }
}
