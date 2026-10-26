package com.fixia.services.service;

import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Datos de los eventos de negocio de la versión 1 del contrato (GC-436, {@code docs/events/schemas}). Cada método arma
 * el {@code data} de un tipo de evento a partir del dominio ya confirmado. Los nombres de los campos son los del
 * esquema; si uno cambia, cambia el contrato.
 *
 * <p>Solo datos de negocio necesarios: nunca la dirección exacta, las claves de las fotografías, nombres, datos de
 * contacto ni secretos (sección 7 del contrato).
 */
public final class BusinessEvents {

    public static final String REQUEST_PUBLISHED = "solicitud.publicada";
    public static final String APPLICATION_REGISTERED = "postulacion.registrada";
    public static final String PROVIDER_SELECTED = "prestador.seleccionado";
    public static final String QUOTATION_SENT = "cotizacion.enviada";
    public static final String QUOTATION_REJECTED = "cotizacion.rechazada";
    public static final String SERVICE_CONTRACTED = "servicio.contratado";

    private static final short V1 = 1;

    private BusinessEvents() {
    }

    /** Un hecho listo para el Outbox: tipo, versión del esquema y datos. */
    public record Event(String type, short schemaVersion, Object data) {
    }

    /** Quién hizo la operación: {@code users.id} y rol, o el sistema. */
    public record Actor(UUID userId, String role) {

        public static Actor client(UUID userId) {
            return new Actor(userId, "CLIENT");
        }

        public static Actor professional(UUID userId) {
            return new Actor(userId, "PROFESSIONAL");
        }
    }

    /** Importe como texto con dos decimales, para que ningún consumidor lo convierta a punto flotante. */
    public record Money(String amount, CurrencyCode currency) {

        static Money of(BigDecimal amount, CurrencyCode currency) {
            if (amount == null || currency == null) {
                return null;
            }
            return new Money(amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), currency);
        }
    }

    record Location(String city, String zone, Double latitude, Double longitude) {
    }

    record RequestPublished(UUID serviceRequestId, UUID clientId, ServiceCategory category, String description,
                            Location approximateLocation, Instant expectedAt, Instant publishedAt,
                            ServiceRequestStatus status, int photoCount) {
    }

    record ApplicationRegistered(UUID applicationId, UUID serviceRequestId, UUID providerId, ApplicationStatus status,
                                 Money proposedPrice, Instant appliedAt) {
    }

    record ProviderSelected(UUID serviceRequestId, UUID clientId, UUID selectedApplicationId, UUID selectedProviderId,
                            Money selectedProposedPrice, List<UUID> rejectedApplicationIds, Instant selectedAt,
                            ServiceRequestStatus status) {
    }

    record ReplacedQuotation(UUID quotationId, QuotationStatus newStatus) {
    }

    record QuotationSent(UUID quotationId, UUID serviceRequestId, UUID clientId, UUID providerId, Money price,
                         String scope, String conditions, Instant agreedAt, Instant validUntil, Instant sentAt,
                         QuotationStatus status, List<ReplacedQuotation> replacedQuotations) {
    }

    record QuotationRejected(UUID quotationId, UUID serviceRequestId, UUID clientId, UUID providerId, Money price,
                             Instant rejectedAt, QuotationStatus status) {
    }

    record ServiceContracted(UUID contractId, UUID serviceRequestId, UUID quotationId, UUID clientId, UUID providerId,
                             Money price, String scope, String conditions, Instant agreedAt, Instant contractedAt,
                             ServiceRequestStatus status) {
    }

    /** El cliente publicó la solicitud. Solo ubicación aproximada y el número de fotos. */
    public static Event requestPublished(ServiceRequest request) {
        ApproximateLocation location = request.getApproximateLocation();
        return new Event(REQUEST_PUBLISHED, V1, new RequestPublished(request.getId(), request.getClientId(),
                request.getCategory(), request.getDescription(),
                new Location(location.city(), location.zone(), location.latitude(), location.longitude()),
                request.getExpectedAt(), request.getPublishedAt(), request.getStatus(),
                request.getPhotoKeys().size()));
    }

    /** Un técnico se postuló con su precio propuesto. */
    public static Event applicationRegistered(ServiceRequestApplication application) {
        return new Event(APPLICATION_REGISTERED, V1, new ApplicationRegistered(application.getId(),
                application.getServiceRequestId(), application.getProviderId(), application.getStatus(),
                Money.of(application.getProposedAmount(), application.getCurrency()), application.getCreatedAt()));
    }

    /** El cliente eligió prestador; las demás postulaciones quedaron rechazadas en la misma transacción. */
    public static Event providerSelected(ServiceRequest request, ServiceRequestApplication chosen,
                                         List<ServiceRequestApplication> rejected, Instant selectedAt) {
        return new Event(PROVIDER_SELECTED, V1, new ProviderSelected(request.getId(), request.getClientId(),
                chosen.getId(), chosen.getProviderId(), Money.of(chosen.getProposedAmount(), chosen.getCurrency()),
                rejected.stream().map(ServiceRequestApplication::getId).toList(), selectedAt, request.getStatus()));
    }

    /** El prestador envió una cotización; {@code replaced} son las enviadas que quedaron reemplazadas o vencidas. */
    public static Event quotationSent(Quotation quotation, UUID clientId, List<Quotation> replaced) {
        return new Event(QUOTATION_SENT, V1, new QuotationSent(quotation.getId(), quotation.getServiceRequestId(),
                clientId, quotation.getProviderId(), Money.of(quotation.getAmount(), quotation.getCurrency()),
                quotation.getScope(), quotation.getConditions(), quotation.getAgreedAt(), quotation.getValidUntil(),
                quotation.getCreatedAt(), quotation.getStatus(),
                replaced.stream().map(previous -> new ReplacedQuotation(previous.getId(), previous.getStatus()))
                        .toList()));
    }

    /** El cliente rechazó la cotización. */
    public static Event quotationRejected(Quotation quotation, UUID clientId) {
        return new Event(QUOTATION_REJECTED, V1, new QuotationRejected(quotation.getId(),
                quotation.getServiceRequestId(), clientId, quotation.getProviderId(),
                Money.of(quotation.getAmount(), quotation.getCurrency()), quotation.getRespondedAt(),
                quotation.getStatus()));
    }

    /** Se formalizó la contratación: copia inmutable de lo acordado (implica la cotización aceptada). */
    public static Event serviceContracted(ServiceContract contract) {
        return new Event(SERVICE_CONTRACTED, V1, new ServiceContracted(contract.getId(),
                contract.getServiceRequestId(), contract.getQuotationId(), contract.getClientId(),
                contract.getProviderId(), Money.of(contract.getAmount(), contract.getCurrency()), contract.getScope(),
                contract.getConditions(), contract.getAgreedAt(), contract.getContractedAt(),
                ServiceRequestStatus.CONTRACTED));
    }
}
