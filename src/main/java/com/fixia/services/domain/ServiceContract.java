package com.fixia.services.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Contratación formalizada de una solicitud (GC-359, RF-035, CA-03): nace en la misma transacción en que el cliente
 * acepta una cotización vigente y conserva una copia de las condiciones acordadas (valor, moneda, alcance,
 * condiciones y fecha del servicio). Es inmutable: ninguna cotización posterior la cambia.
 *
 * <p>El pago del servicio ocurre fuera de Fixia (RN-005, RN-006, CA-04): aquí no hay saldo, retención ni pago. El estado
 * del servicio es el de la solicitud ({@link ServiceRequestStatus#CONTRACTED}), no se duplica.
 */
@Entity
@Table(name = "service_contracts")
public class ServiceContract {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_request_id", nullable = false, updatable = false)
    private UUID serviceRequestId;

    @Column(name = "quotation_id", nullable = false, updatable = false)
    private UUID quotationId;

    /** {@code users.id} del cliente en ms-users. Sin FK entre bases. */
    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    /** {@code users.id} del prestador en ms-users. Sin FK entre bases. */
    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = Quotation.AMOUNT_SCALE, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "scope", nullable = false, length = Quotation.MAX_TEXT_LENGTH, updatable = false)
    private String scope;

    @Column(name = "conditions", nullable = false, length = Quotation.MAX_TEXT_LENGTH, updatable = false)
    private String conditions;

    /** Fecha acordada para ejecutar el servicio, tal como la aceptó el cliente. */
    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    @Column(name = "contracted_at", nullable = false, updatable = false)
    private Instant contractedAt;

    protected ServiceContract() {
    }

    private ServiceContract(ServiceRequest request, Quotation quotation, Instant now) {
        this.serviceRequestId = request.getId();
        this.quotationId = quotation.getId();
        this.clientId = request.getClientId();
        this.providerId = quotation.getProviderId();
        this.amount = quotation.getAmount();
        this.currency = quotation.getCurrency();
        this.scope = quotation.getScope();
        this.conditions = quotation.getConditions();
        this.agreedAt = quotation.getAgreedAt();
        this.contractedAt = now;
    }

    /**
     * Formaliza la contratación de la solicitud con la cotización aceptada. Se llama con la solicitud ya en
     * {@code CONTRACTED} y la cotización ya en {@code ACCEPTED}, dentro de la misma transacción.
     *
     * @throws IllegalArgumentException si la cotización no está aceptada, no es de esa solicitud, no es del prestador
     *                                  seleccionado, o la solicitud no está contratada
     */
    public static ServiceContract formalize(ServiceRequest request, Quotation quotation, Instant now) {
        require(request != null && request.getId() != null, "La solicitud es obligatoria");
        require(quotation != null && quotation.getId() != null, "La cotización es obligatoria");
        require(now != null, "El momento de la contratación es obligatorio");
        require(request.getStatus() == ServiceRequestStatus.CONTRACTED,
                "Solo se formaliza la contratación de una solicitud contratada");
        require(quotation.getStatus() == QuotationStatus.ACCEPTED,
                "Solo se formaliza la contratación con una cotización aceptada");
        require(request.getId().equals(quotation.getServiceRequestId()),
                "La cotización no es de esta solicitud");
        require(quotation.getProviderId().equals(request.getSelectedProviderId()),
                "La cotización no es del prestador seleccionado");
        return new ServiceContract(request, quotation, now);
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

    public UUID getQuotationId() {
        return quotationId;
    }

    public UUID getClientId() {
        return clientId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public String getScope() {
        return scope;
    }

    public String getConditions() {
        return conditions;
    }

    public Instant getAgreedAt() {
        return agreedAt;
    }

    public Instant getContractedAt() {
        return contractedAt;
    }

    @Override
    public String toString() {
        return "ServiceContract[id=" + id + ", serviceRequestId=" + serviceRequestId + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ServiceContract that && id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return ServiceContract.class.hashCode();
    }
}
