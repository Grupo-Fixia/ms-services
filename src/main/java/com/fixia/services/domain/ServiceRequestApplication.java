package com.fixia.services.domain;

import com.fixia.services.exception.InvalidApplicationTransitionException;
import com.fixia.services.exception.ServiceRequestNotAvailableException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Postulación de un técnico a una solicitud de servicio (GC-298): expresa su interés en prestar el servicio, sin
 * asignarle el trabajo (RN-002). Una solicitud puede recibir varias postulaciones (RN-003).
 *
 * <p>Solo se crea sobre una solicitud {@link ServiceRequestStatus#PUBLISHED} cuyo momento esperado aún no ha
 * pasado. La elegibilidad del técnico (categoría, zona y disponibilidad) la decide ms-users antes de crearla.
 * Lleva el precio que propone el técnico (GC-423), que no cambia después: para cambiarlo habría que volver a
 * postularse, y eso lo impide la unicidad por solicitud y técnico.
 * Los cambios de estado pasan por los métodos de dominio, que validan la transición con
 * {@link ApplicationStatus#canTransitionTo(ApplicationStatus)}.
 */
@Entity
@Table(name = "applications")
public class ServiceRequestApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_request_id", nullable = false, updatable = false)
    private UUID serviceRequestId;

    /** {@code users.id} del técnico en ms-users (el {@code sub} del token sin {@code usr_}). Sin FK entre bases. */
    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApplicationStatus status;

    /**
     * Precio que propone el técnico (GC-423). Es distinto del presupuesto del cliente y del valor de la cotización.
     * Las postulaciones anteriores a la migración V6 no lo tienen ({@code null}).
     */
    @Column(name = "proposed_amount", precision = 12, scale = Quotation.AMOUNT_SCALE, updatable = false)
    private BigDecimal proposedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ServiceRequestApplication() {
    }

    private ServiceRequestApplication(UUID serviceRequestId, UUID providerId, BigDecimal proposedAmount,
                                      CurrencyCode currency, Instant now) {
        this.serviceRequestId = serviceRequestId;
        this.providerId = providerId;
        this.proposedAmount = proposedAmount.setScale(Quotation.AMOUNT_SCALE);
        this.currency = currency;
        this.status = ApplicationStatus.APPLIED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Registra la postulación del técnico en estado {@code APPLIED}, con el precio que propone. El precio sigue las
     * mismas reglas que el valor de una cotización: mayor que cero, máximo dos decimales y moneda explícita.
     *
     * @throws ServiceRequestNotAvailableException si la solicitud ya no recibe postulaciones
     * @throws IllegalArgumentException            si falta la solicitud o el técnico, o el precio no es válido
     */
    public static ServiceRequestApplication submit(ServiceRequest request, UUID providerId, BigDecimal proposedAmount,
                                                   CurrencyCode currency, Instant now) {
        require(request != null && request.getId() != null, "La solicitud es obligatoria");
        require(providerId != null, "El técnico es obligatorio");
        require(proposedAmount != null, "El precio propuesto es obligatorio");
        require(proposedAmount.signum() > 0, "El precio propuesto debe ser mayor que cero");
        require(proposedAmount.stripTrailingZeros().scale() <= Quotation.AMOUNT_SCALE,
                "El precio propuesto admite máximo dos decimales");
        require(proposedAmount.compareTo(Quotation.MAX_AMOUNT) <= 0,
                "El precio propuesto no puede superar " + Quotation.MAX_AMOUNT.toPlainString());
        require(currency != null, "La moneda es obligatoria");
        requireOpen(request, now);
        return new ServiceRequestApplication(request.getId(), providerId, proposedAmount, currency, now);
    }

    /**
     * Una solicitud recibe postulaciones solo mientras está publicada y su momento esperado no ha pasado.
     *
     * @throws ServiceRequestNotAvailableException si no es así
     */
    public static void requireOpen(ServiceRequest request, Instant now) {
        if (!isOpen(request, now)) {
            throw new ServiceRequestNotAvailableException();
        }
    }

    /** Publicada y con el momento esperado por delante: la misma regla para postularse y para seleccionar (GC-348). */
    public static boolean isOpen(ServiceRequest request, Instant now) {
        return request.getStatus() == ServiceRequestStatus.PUBLISHED && request.getExpectedAt().isAfter(now);
    }

    /** El cliente eligió a este prestador (GC-348). */
    public void select(Instant now) {
        transitionTo(ApplicationStatus.SELECTED, now);
    }

    /** El cliente eligió a otro prestador o la solicitud dejó de recibir postulaciones (GC-348). */
    public void reject(Instant now) {
        transitionTo(ApplicationStatus.REJECTED, now);
    }

    private void transitionTo(ApplicationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidApplicationTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceRequestId() {
        return serviceRequestId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    /** {@code null} en postulaciones anteriores a GC-423. */
    public BigDecimal getProposedAmount() {
        return proposedAmount;
    }

    /** {@code null} en postulaciones anteriores a GC-423. */
    public CurrencyCode getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "ServiceRequestApplication[id=" + id + ", serviceRequestId=" + serviceRequestId
                + ", status=" + status + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ServiceRequestApplication that && id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return ServiceRequestApplication.class.hashCode();
    }
}
