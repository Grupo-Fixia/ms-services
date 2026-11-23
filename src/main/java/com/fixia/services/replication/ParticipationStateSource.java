package com.fixia.services.replication;

import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.service.ParticipationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Participación en cada solicitud con prestador seleccionado (GC-547): estado, cliente, prestador, tipo de prestador,
 * ejecutor y momento del cierre. Es exactamente lo que respondía la ruta interna de participación (GC-444), que ms-users
 * consultaba antes de aceptar una evaluación; ahora ms-users guarda esta copia y no llama a ms-services.
 *
 * <p>Se calcula con {@link ParticipationService}, la misma lógica de la ruta, para no duplicar reglas. Recorre todas las
 * solicitudes con prestador en cada pasada: sirve para el volumen del proyecto; con muchas solicitudes habría que
 * publicar solo las que cambiaron desde la última pasada.
 */
@Component
public class ParticipationStateSource implements StateSource {

    public static final String STATE_TYPE = "servicio.participacion";
    public static final String TOPIC = "fixia.services.participations";

    private final ServiceRequestRepository requests;
    private final ParticipationService participations;

    public ParticipationStateSource(ServiceRequestRepository requests, ParticipationService participations) {
        this.requests = requests;
        this.participations = participations;
    }

    @Override
    public String stateType() {
        return STATE_TYPE;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String aggregateType() {
        return "ServiceParticipation";
    }

    @Override
    public Map<UUID, Object> currentStates() {
        Map<UUID, Object> states = new LinkedHashMap<>();
        for (ServiceRequest request : requests.findAll()) {
            if (request.getSelectedProviderId() != null) {
                states.put(request.getId(), participations.participation(request.getId()));
            }
        }
        return states;
    }
}
