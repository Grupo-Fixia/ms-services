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
    public static final String SERVICE_STARTED = "servicio.iniciado";
    public static final String SERVICE_FINISHED = "servicio.finalizado";
    public static final String CLOSURE_OBJECTED = "servicio.cierre_objetado";
    public static final String SERVICE_CLOSED = "servicio.cerrado";

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
                            ServiceRequestStatus status, int photoCount, Money budget) {
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
                         QuotationStatus status, List<ReplacedQuotation> replacedQuotations, Integer revision) {
    }

    record QuotationRejected(UUID quotationId, UUID serviceRequestId, UUID clientId, UUID providerId, Money price,
                             Instant rejectedAt, QuotationStatus status) {
    }

    record ServiceContracted(UUID contractId, UUID serviceRequestId, UUID quotationId, UUID clientId, UUID providerId,
                             Money price, String scope, String conditions, Instant agreedAt, Instant contractedAt,
                             ServiceRequestStatus status) {
    }

    record ServiceStarted(UUID contractId, UUID serviceRequestId, UUID clientId, UUID providerId, Instant startedAt,
                          ServiceRequestStatus status) {
    }

    record ServiceFinished(UUID contractId, UUID serviceRequestId, UUID clientId, UUID providerId, Instant finishedAt,
                           ServiceRequestStatus status) {
    }

    /** Sin el motivo de la objeción: puede traer datos personales y vive solo en el historial de los participantes. */
    record ClosureObjected(UUID contractId, UUID serviceRequestId, UUID clientId, UUID providerId, Instant objectedAt,
                           ServiceRequestStatus status) {
    }

    record ServiceClosed(UUID contractId, UUID serviceRequestId, UUID clientId, UUID providerId, Instant closedAt,
                         ServiceRequestStatus status) {
    }

    /** El cliente publicó la solicitud. Solo ubicación aproximada, el número de fotos y su presupuesto orientativo. */
    public static Event requestPublished(ServiceRequest request) {
        ApproximateLocation location = request.getApproximateLocation();
        return new Event(REQUEST_PUBLISHED, V1, new RequestPublished(request.getId(), request.getClientId(),
                request.getCategory(), request.getDescription(),
                new Location(location.city(), location.zone(), location.latitude(), location.longitude()),
                request.getExpectedAt(), request.getPublishedAt(), request.getStatus(),
                request.getPhotoKeys().size(), Money.of(request.getBudgetAmount(), request.getBudgetCurrency())));
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
                        .toList(),
                quotation.getRevision()));
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

    /** El prestador contratado inició el servicio (GC-437): la solicitud pasó de CONTRACTED a IN_PROGRESS. */
    public static Event serviceStarted(ServiceContract contract, Instant startedAt) {
        return new Event(SERVICE_STARTED, V1, new ServiceStarted(contract.getId(), contract.getServiceRequestId(),
                contract.getClientId(), contract.getProviderId(), startedAt, ServiceRequestStatus.IN_PROGRESS));
    }

    /**
     * El prestador declaró que terminó (GC-441): {@code IN_PROGRESS -> TECHNICIAN_FINISHED}. Sin {@code evidenceCount}:
     * las evidencias llegan con GC-439, que lo agregará como campo opcional.
     */
    public static Event serviceFinished(ServiceContract contract, Instant finishedAt) {
        return new Event(SERVICE_FINISHED, V1, new ServiceFinished(contract.getId(), contract.getServiceRequestId(),
                contract.getClientId(), contract.getProviderId(), finishedAt,
                ServiceRequestStatus.TECHNICIAN_FINISHED));
    }

    /** El cliente objetó el cierre (GC-441): el servicio volvió a {@code IN_PROGRESS}. No lleva el texto del motivo. */
    public static Event closureObjected(ServiceContract contract, Instant objectedAt) {
        return new Event(CLOSURE_OBJECTED, V1, new ClosureObjected(contract.getId(), contract.getServiceRequestId(),
                contract.getClientId(), contract.getProviderId(), objectedAt, ServiceRequestStatus.IN_PROGRESS));
    }

    /** El cliente confirmó el cierre (GC-441): {@code TECHNICIAN_FINISHED -> CLOSED}, definitivo. */
    public static Event serviceClosed(ServiceContract contract, Instant closedAt) {
        return new Event(SERVICE_CLOSED, V1, new ServiceClosed(contract.getId(), contract.getServiceRequestId(),
                contract.getClientId(), contract.getProviderId(), closedAt, ServiceRequestStatus.CLOSED));
    }
}
