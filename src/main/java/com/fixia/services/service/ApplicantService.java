package com.fixia.services.service;

import com.fixia.services.client.CompanyClient;
import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ProviderType;
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
    private final CompanyClient companyClient;

    public ApplicantService(ServiceRequestService serviceRequestService, TechnicianProfileClient profileClient,
                            CompanyClient companyClient) {
        this.serviceRequestService = serviceRequestService;
        this.profileClient = profileClient;
        this.companyClient = companyClient;
    }

    /**
     * Mismas reglas que {@link ServiceRequestService#listApplicants}: solo el cliente dueño, en orden de llegada. Sin
     * postulantes no se consulta a ms-users, y sin empresas postuladas no se piden perfiles de empresa. Un prestador
     * que ms-users ya no reconoce como técnico activo sale sin perfil ({@code null}) en lugar de uno inventado.
     *
     * @throws com.fixia.services.client.ProfileLookupException si ms-users no entrega los perfiles (503)
     */
    public List<ApplicantView> list(UUID clientId, UUID requestId) {
        List<ServiceRequestApplication> applications = serviceRequestService.listApplicants(clientId, requestId);
        if (applications.isEmpty()) {
            return List.of();
        }
        Map<UUID, TechnicianProfileClient.PublicProfile> technicians = profileClient.findPublicProfiles(
                applications.stream().map(ServiceRequestApplication::getExecutorId).toList());
        List<UUID> companyIds = applications.stream()
                .filter(application -> application.getProviderType() == ProviderType.COMPANY)
                .map(ServiceRequestApplication::getProviderId)
                .toList();
        Map<UUID, CompanyClient.CompanyProfile> companies =
                companyIds.isEmpty() ? Map.of() : companyClient.findPublicProfiles(companyIds);
        return applications.stream()
                .map(application -> new ApplicantView(application, technicians.get(application.getExecutorId()),
                        companies.get(application.getProviderId())))
                .toList();
    }

    /**
     * Una postulación con el perfil público del técnico que haría el trabajo y, si se postuló una empresa (GC-468), el
     * de la empresa. Cada perfil es {@code null} si ms-users ya no lo reconoce.
     */
    public record ApplicantView(ServiceRequestApplication application,
                                TechnicianProfileClient.PublicProfile executor,
                                CompanyClient.CompanyProfile company) {
    }
}
