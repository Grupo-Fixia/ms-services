package com.fixia.services.domain;

import com.fixia.services.exception.InvalidQuotationTransitionException;
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
 * Cotización del prestador seleccionado para una solicitud (GC-300, RF-031): valor, alcance, condiciones y fecha
 * acordada, con su vigencia. Formaliza lo acordado por chat (RN-004); el pago del servicio ocurre fuera de Fixia.
 *
 * <p>Solo la puede enviar el prestador seleccionado de una solicitud en {@link ServiceRequestStatus#PROVIDER_SELECTED}.
 * Una vez enviada no se edita: los cambios de estado pasan por los métodos de dominio, que validan la transición con
 * {@link QuotationStatus#canTransitionTo(QuotationStatus)}.
 */
@Entity
@Table(name = "quotations")
public class Quotation {

    public static final int MAX_TEXT_LENGTH = 2000;
    public static final int AMOUNT_SCALE = 2;
    /** NUMERIC(12,2): hasta 9.999.999.999,99. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_request_id", nullable = false, updatable = false)
    private UUID serviceRequestId;

    /** {@code users.id} del prestador en ms-users (el {@code sub} del token sin {@code usr_}). Sin FK entre bases. */
    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = AMOUNT_SCALE, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "scope", nullable = false, length = MAX_TEXT_LENGTH, updatable = false)
    private String scope;

    @Column(name = "conditions", nullable = false, length = MAX_TEXT_LENGTH, updatable = false)
    private String conditions;

    /** Fecha acordada para ejecutar el servicio. */
    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    /** Hasta cuándo puede aceptarse; desde ese momento la cotización está vencida. */
    @Column(name = "valid_until", nullable = false, updatable = false)
    private Instant validUntil;

    /**
     * Número de versión dentro de la solicitud (GC-427): 1 es la primera cotización, 2 el primer ajuste, y así. Se asigna
     * al enviar, con la solicitud bloqueada, y nunca cambia. No confundir con {@link #version}, el candado optimista.
     */
    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuotationStatus status;

    /** Momento en que el cliente aceptó o rechazó; null mientras no responda. */
    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Quotation() {
    }

    private Quotation(ServiceRequest request, UUID providerId, BigDecimal amount, CurrencyCode currency,
                      String scope, String conditions, Instant agreedAt, Instant validUntil, int revision,
                      Instant now) {
        this.serviceRequestId = request.getId();
        this.revision = revision;
        this.providerId = providerId;
        this.amount = amount.setScale(AMOUNT_SCALE);
        this.currency = currency;
        this.scope = scope.strip();
        this.conditions = conditions.strip();
        this.agreedAt = agreedAt;
        this.validUntil = validUntil;
        this.status = QuotationStatus.SENT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Crea y envía una cotización en estado {@code SENT}.
     *
     * @param request    solicitud cotizada; debe estar en {@code PROVIDER_SELECTED}
     * @param providerId prestador que cotiza; debe ser el seleccionado de la solicitud
     * @param agreedAt   fecha acordada para el servicio; posterior al envío
     * @param validUntil vigencia de la cotización; posterior al envío y no posterior a la fecha acordada
     * @param revision   número de versión en la solicitud (GC-427), 1 o más; lo asigna el servicio con la solicitud
     *                   bloqueada
     * @throws IllegalArgumentException si falta un dato, alguno no cumple las reglas o quien cotiza no es el
     *                                  prestador seleccionado de una solicitud con selección vigente
     */
    public static Quotation send(ServiceRequest request, UUID providerId, BigDecimal amount, CurrencyCode currency,
                                 String scope, String conditions, Instant agreedAt, Instant validUntil, int revision,
                                 Instant now) {
        require(request != null && request.getId() != null, "La solicitud es obligatoria");
        require(providerId != null, "El prestador es obligatorio");
        require(request.getStatus() == ServiceRequestStatus.PROVIDER_SELECTED,
                "Solo se puede cotizar una solicitud con prestador seleccionado");
        require(providerId.equals(request.getSelectedProviderId()),
                "Solo el prestador seleccionado puede cotizar esta solicitud");
        require(amount != null, "El valor es obligatorio");
        require(amount.signum() > 0, "El valor debe ser mayor que cero");
        require(amount.stripTrailingZeros().scale() <= AMOUNT_SCALE, "El valor admite máximo dos decimales");
        require(amount.compareTo(MAX_AMOUNT) <= 0, "El valor no puede superar " + MAX_AMOUNT.toPlainString());
        require(currency != null, "La moneda es obligatoria");
        requireText(scope, "El alcance es obligatorio");
        requireText(conditions, "Las condiciones son obligatorias");
        require(agreedAt != null, "La fecha acordada es obligatoria");
        require(agreedAt.isAfter(now), "La fecha acordada debe ser posterior al envío");
        require(validUntil != null, "La vigencia es obligatoria");
        require(validUntil.isAfter(now), "La vigencia debe ser posterior al envío");
        require(!validUntil.isAfter(agreedAt), "La vigencia no puede ser posterior a la fecha acordada");
        require(revision >= 1, "La versión de la cotización debe ser mayor que cero");
        return new Quotation(request, providerId, amount, currency, scope, conditions, agreedAt, validUntil, revision,
                now);
    }

    /** Si la cotización sigue esperando respuesta pero ya pasó su vigencia. */
    public boolean isExpiredAt(Instant now) {
        return status == QuotationStatus.SENT && !now.isBefore(validUntil);
    }

    /** El cliente acepta. Una cotización vencida no se puede aceptar aunque todavía figure como enviada (RF-033). */
    public void accept(Instant now) {
        if (isExpiredAt(now)) {
            throw new InvalidQuotationTransitionException(status, QuotationStatus.ACCEPTED,
                    "La cotización está vencida y no se puede aceptar");
        }
        transitionTo(QuotationStatus.ACCEPTED, now);
        this.respondedAt = now;
    }

    /** El cliente rechaza. Como al aceptar, solo se responde una cotización vigente: una vencida ya no espera respuesta. */
    public void reject(Instant now) {
        if (isExpiredAt(now)) {
            throw new InvalidQuotationTransitionException(status, QuotationStatus.REJECTED,
                    "La cotización está vencida y no se puede rechazar");
        }
        transitionTo(QuotationStatus.REJECTED, now);
        this.respondedAt = now;
    }

    /** Marca como vencida una cotización enviada cuya vigencia ya pasó. */
    public void expire(Instant now) {
        if (status == QuotationStatus.SENT && now.isBefore(validUntil)) {
            throw new InvalidQuotationTransitionException(status, QuotationStatus.EXPIRED,
                    "La cotización todavía está vigente");
        }
        transitionTo(QuotationStatus.EXPIRED, now);
    }

    /** La reemplaza una cotización más reciente del mismo prestador. */
    public void supersede(Instant now) {
        transitionTo(QuotationStatus.SUPERSEDED, now);
    }

    private void transitionTo(QuotationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidQuotationTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static void requireText(String value, String message) {
        require(value != null && !value.isBlank(), message);
        require(value.strip().length() <= MAX_TEXT_LENGTH,
                message + " y no puede superar " + MAX_TEXT_LENGTH + " caracteres");
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

    /** Número de versión visible dentro de la solicitud (1, 2, 3...), distinto del candado optimista {@link #getVersion()}. */
    public int getRevision() {
        return revision;
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

    public Instant getValidUntil() {
        return validUntil;
    }

    public QuotationStatus getStatus() {
        return status;
    }

    public Instant getRespondedAt() {
        return respondedAt;
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

    /** No incluye valor, alcance ni condiciones: son datos restringidos (CD-06). */
    @Override
    public String toString() {
        return "Quotation[id=" + id + ", serviceRequestId=" + serviceRequestId + ", status=" + status + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Quotation that && id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Quotation.class.hashCode();
    }
}
