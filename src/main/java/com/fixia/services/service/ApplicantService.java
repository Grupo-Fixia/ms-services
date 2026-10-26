package com.fixia.services.service;

import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ServiceRequestApplication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Postulantes de una solicitud con el perfil público de cada prestador (GC-425), para que el cliente compare antes de
 * seleccionar. Las postulaciones salen de esta base y el perfil, de ms-users.
 *
 * <p>No es transaccional a propósito: la consulta a ms-users va después de leer las postulaciones, para no retener
 * una conexión de la base mientras se espera a otro servicio (como en la postulación, GC-342).
 */
@Service
public class ApplicantService {

    private final ServiceRequestService serviceRequestService;
    private final TechnicianProfileClient profileClient;

    public ApplicantService(ServiceRequestService serviceRequestService, TechnicianProfileClient profileClient) {
        this.serviceRequestService = serviceRequestService;
        this.profileClient = profileClient;
    }

    /**
     * Mismas reglas que {@link ServiceRequestService#listApplicants}: solo el cliente dueño, en orden de llegada. Sin
     * postulantes no se consulta a ms-users. Un prestador que ms-users ya no reconoce como técnico activo sale sin
     * perfil ({@code null}) en lugar de uno inventado.
     *
     * @throws com.fixia.services.client.ProfileLookupException si ms-users no entrega los perfiles (503)
     */
    public List<ApplicantView> list(UUID clientId, UUID requestId) {
        List<ServiceRequestApplication> applications = serviceRequestService.listApplicants(clientId, requestId);
        if (applications.isEmpty()) {
            return List.of();
        }
        Map<UUID, TechnicianProfileClient.PublicProfile> profiles = profileClient.findPublicProfiles(
                applications.stream().map(ServiceRequestApplication::getProviderId).toList());
        return applications.stream()
                .map(application -> new ApplicantView(application, profiles.get(application.getProviderId())))
                .toList();
    }

    /** Una postulación con el perfil público de su prestador ({@code null} si ms-users no lo reconoce). */
    public record ApplicantView(ServiceRequestApplication application, TechnicianProfileClient.PublicProfile profile) {
    }
}
