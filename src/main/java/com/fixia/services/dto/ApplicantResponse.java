package com.fixia.services.dto;

import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequestApplication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Postulante a una solicitud tal como lo ve el cliente dueño: el prestador (solo su id; el perfil público se consulta
 * aparte) y el estado de su postulación. No incluye datos de la solicitud ni ubicación alguna.
 */
public record ApplicantResponse(
        UUID id,
        UUID providerId,
        ApplicationStatus status,
        BigDecimal proposedAmount,
        CurrencyCode currency,
        Instant createdAt,
        Instant updatedAt,
        ProviderProfile provider
) {

    /**
     * Perfil público del prestador que entrega ms-users (GC-425): solo lo necesario para comparar, sin datos de
     * contacto ni ubicación. {@code null} si ms-users ya no lo reconoce como técnico activo.
     */
    public record ProviderProfile(String displayName, String verificationStatus, List<ServiceCategory> categories,
                                  String professionalDescription, Integer yearsOfExperience, String city) {

        static ProviderProfile from(TechnicianProfileClient.PublicProfile profile) {
            if (profile == null) {
                return null;
            }
            return new ProviderProfile(profile.displayName(), profile.verificationStatus(),
                    profile.categories() == null ? List.of() : profile.categories(),
                    profile.professionalDescription(), profile.yearsOfExperience(), profile.city());
        }
    }


    public static ApplicantResponse from(ServiceRequestApplication application,
                                         TechnicianProfileClient.PublicProfile profile) {
        return new ApplicantResponse(
                application.getId(),
                application.getProviderId(),
                application.getStatus(),
                application.getProposedAmount(),
                application.getCurrency(),
                application.getCreatedAt(),
                application.getUpdatedAt(),
                ProviderProfile.from(profile));
    }
}
