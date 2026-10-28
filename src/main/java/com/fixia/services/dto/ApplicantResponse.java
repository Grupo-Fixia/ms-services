package com.fixia.services.dto;

import com.fixia.services.client.CompanyClient;
import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ProviderType;
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
        ProviderType providerType,
        UUID technicianId,
        ApplicationStatus status,
        BigDecimal proposedAmount,
        CurrencyCode currency,
        Instant createdAt,
        Instant updatedAt,
        ProviderProfile provider,
        ProviderProfile executor
) {

    /**
     * Perfil público del prestador que entrega ms-users (GC-425): solo lo necesario para comparar, sin datos de
     * contacto ni ubicación. {@code null} si ms-users ya no lo reconoce como técnico activo.
     */
    public record ProviderProfile(String displayName, String verificationStatus, List<ServiceCategory> categories,
                                  String professionalDescription, Integer yearsOfExperience, String city) {

        /** Una empresa solo expone su nombre comercial (GC-468); el resto del perfil es del técnico ejecutor. */
        static ProviderProfile ofCompany(CompanyClient.CompanyProfile profile) {
            if (profile == null) {
                return null;
            }
            return new ProviderProfile(profile.name(), null, List.of(), null, null, null);
        }

        static ProviderProfile from(TechnicianProfileClient.PublicProfile profile) {
            if (profile == null) {
                return null;
            }
            return new ProviderProfile(profile.displayName(), profile.verificationStatus(),
                    profile.categories() == null ? List.of() : profile.categories(),
                    profile.professionalDescription(), profile.yearsOfExperience(), profile.city());
        }
    }


    /**
     * Para un independiente, {@code provider} es su perfil y no hay {@code executor}. Para una empresa (GC-468),
     * {@code provider} es la empresa y {@code executor} el técnico que propone: dos identidades distintas (RF-026).
     *
     * @param executorProfile perfil del técnico que haría el trabajo ({@code null} si ms-users no lo reconoce)
     * @param companyProfile  perfil de la empresa, solo si se postuló una ({@code null} si no o si ms-users no la
     *                        reconoce)
     */
    public static ApplicantResponse from(ServiceRequestApplication application,
                                         TechnicianProfileClient.PublicProfile executorProfile,
                                         CompanyClient.CompanyProfile companyProfile) {
        boolean company = application.getProviderType() == ProviderType.COMPANY;
        return new ApplicantResponse(
                application.getId(),
                application.getProviderId(),
                application.getProviderType(),
                application.getTechnicianId(),
                application.getStatus(),
                application.getProposedAmount(),
                application.getCurrency(),
                application.getCreatedAt(),
                application.getUpdatedAt(),
                company ? ProviderProfile.ofCompany(companyProfile) : ProviderProfile.from(executorProfile),
                company ? ProviderProfile.from(executorProfile) : null);
    }
}
