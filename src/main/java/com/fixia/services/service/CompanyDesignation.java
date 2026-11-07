package com.fixia.services.service;

import com.fixia.services.client.CompanyClient;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ProviderType;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.exception.QuotationNotAllowedException;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Quién actúa por una empresa seleccionada (GC-551, {@code ms-users/docs/contracts/empresas.md} §8): el técnico
 * designado en la postulación cotiza y ejecuta, y la empresa sigue siendo la prestadora. Antes de cotizar o contratar se
 * vuelve a preguntar a ms-users si el técnico sigue en la empresa, porque pudo desvincularse después de la postulación.
 */
@Component
public class CompanyDesignation {

    static final String NOT_DESIGNATED = "No eres el técnico designado de esta solicitud";
    static final String LEFT_COMPANY = "El técnico propuesto ya no pertenece a la empresa";

    private final ServiceRequestApplicationRepository applicationRepository;
    private final CompanyClient companyClient;

    public CompanyDesignation(ServiceRequestApplicationRepository applicationRepository,
                              CompanyClient companyClient) {
        this.applicationRepository = applicationRepository;
        this.companyClient = companyClient;
    }

    /** La postulación seleccionada, si la solicitud tiene una y es de una empresa. */
    public Optional<ServiceRequestApplication> selectedCompanyApplication(ServiceRequest request) {
        UUID providerId = request.getSelectedProviderId();
        if (providerId == null) {
            return Optional.empty();
        }
        return applicationRepository.findByServiceRequestIdAndProviderId(request.getId(), providerId)
                .filter(application -> application.getStatus() == ApplicationStatus.SELECTED
                        && application.getProviderType() == ProviderType.COMPANY);
    }

    /**
     * Prestador en cuyo nombre cotiza {@code technicianId}: él mismo si la selección es de un independiente, o la empresa
     * si es su técnico designado y sigue asociado.
     *
     * @throws QuotationNotAllowedException 409 si la selección es de una empresa y quien cotiza es otro técnico de ella
     *                                      ({@value #NOT_DESIGNATED}), o si el designado ya no está en la empresa
     *                                      ({@value #LEFT_COMPANY}). A un técnico ajeno a la empresa no se le dice
     *                                      nada de la selección: sigue el 409 genérico de quien no fue seleccionado
     * @throws com.fixia.services.client.EligibilityCheckException 503 si ms-users no responde
     */
    public UUID providerFor(ServiceRequest request, UUID technicianId) {
        Optional<ServiceRequestApplication> selected = selectedCompanyApplication(request);
        if (selected.isEmpty()) {
            return technicianId;
        }
        ServiceRequestApplication application = selected.get();
        UUID companyId = application.getProviderId();
        if (!technicianId.equals(application.getTechnicianId())) {
            if (companyClient.isTechnicianAssociated(companyId, technicianId)) {
                throw new QuotationNotAllowedException(NOT_DESIGNATED);
            }
            return technicianId; // el envío lo rechaza después como a cualquiera que no fue seleccionado
        }
        requireStillAssociated(companyId, technicianId);
        return companyId;
    }

    /** 409 si el técnico ya no pertenece a la empresa; 503 si ms-users no responde. */
    public void requireStillAssociated(UUID companyId, UUID technicianId) {
        if (!companyClient.isTechnicianAssociated(companyId, technicianId)) {
            throw new QuotationNotAllowedException(LEFT_COMPANY);
        }
    }
}
