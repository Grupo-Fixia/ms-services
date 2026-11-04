package com.fixia.services.domain;

import com.fixia.services.exception.InvalidStatusTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Solicitud de servicio publicada por un cliente (GC-296). Es la raíz del flujo
 * solicitud → postulaciones → selección de prestador → cotización → contratación.
 *
 * <p>Privacidad: la ubicación aproximada es la única visible mientras la solicitud no esté
 * {@link ServiceRequestStatus#CONTRACTED}; la dirección exacta se guarda aparte y {@link #getExactLocation()}
 * no la entrega antes de la contratación.
 *
 * <p>Los cambios de estado pasan siempre por los métodos de dominio, que validan la transición con
 * {@link ServiceRequestStatus#canTransitionTo(ServiceRequestStatus)}.
 */
@Entity
@Table(name = "service_requests")
public class ServiceRequest {

    public static final int MAX_PHOTOS = 5;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    public static final int MAX_PLACE_LENGTH = 100;
    public static final int MAX_ADDRESS_LENGTH = 255;
    public static final int MAX_STORAGE_KEY_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Identificador del cliente en ms-users. Sin FK: cada microservicio tiene su propia base. */
    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ServiceCategory category;

    @Column(name = "description", nullable = false, length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(name = "city", nullable = false, length = MAX_PLACE_LENGTH)
    private String city;

    @Column(name = "zone", nullable = false, length = MAX_PLACE_LENGTH)
    private String zone;

    @Column(name = "approx_latitude")
    private Double approxLatitude;

    @Column(name = "approx_longitude")
    private Double approxLongitude;

    @Column(name = "exact_address", length = MAX_ADDRESS_LENGTH)
    private String exactAddress;

    @Column(name = "exact_latitude")
    private Double exactLatitude;

    @Column(name = "exact_longitude")
    private Double exactLongitude;

    @Column(name = "expected_at", nullable = false)
    private Instant expectedAt;

    /**
     * Presupuesto orientativo del cliente (GC-421): un valor único que solo guía al prestador. No es un pago ni un
     * límite, y es distinto del precio propuesto, de la cotización y del precio contratado. Las solicitudes anteriores
     * a la migración V8 no lo tienen ({@code null}); las nuevas siempre lo traen.
     */
    @Column(name = "budget_amount", precision = 12, scale = Quotation.AMOUNT_SCALE, updatable = false)
    private BigDecimal budgetAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_currency", length = 3, updatable = false)
    private CurrencyCode budgetCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ServiceRequestStatus status;

    /** Prestador elegido por el cliente; solo existe desde {@code PROVIDER_SELECTED}. */
    @Column(name = "selected_provider_id")
    private UUID selectedProviderId;

    @OneToMany(mappedBy = "serviceRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ServiceRequestPhoto> photos = new ArrayList<>();

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ServiceRequest() {
    }

    private ServiceRequest(UUID clientId, ServiceCategory category, String description,
                           ApproximateLocation approximate, ExactLocation exact, Instant expectedAt,
                           BigDecimal budgetAmount, CurrencyCode budgetCurrency, Instant now) {
        this.clientId = clientId;
        this.category = category;
        this.description = description.strip();
        this.city = approximate.city().strip();
        this.zone = approximate.zone().strip();
        this.approxLatitude = roundApproximate(approximate.latitude());
        this.approxLongitude = roundApproximate(approximate.longitude());
        if (exact != null) {
            this.exactAddress = exact.address().strip();
            this.exactLatitude = exact.latitude();
            this.exactLongitude = exact.longitude();
        }
        this.expectedAt = expectedAt;
        this.budgetAmount = budgetAmount.setScale(Quotation.AMOUNT_SCALE);
        this.budgetCurrency = budgetCurrency;
        this.status = ServiceRequestStatus.PUBLISHED;
        this.publishedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Publica una solicitud nueva en estado {@code PUBLISHED}.
     *
     * @param exact          dirección exacta del cliente; opcional, y protegida hasta la contratación
     * @param budgetAmount   presupuesto orientativo (GC-421): obligatorio, mayor que cero, máximo dos decimales y
     *                       {@link Quotation#MAX_AMOUNT} como tope
     * @param budgetCurrency moneda del presupuesto, obligatoria
     * @param photoKeys      claves de almacenamiento de las fotos adjuntas (máximo {@value #MAX_PHOTOS}), en orden
     * @throws IllegalArgumentException si falta un dato obligatorio o alguno no cumple las reglas
     */
    public static ServiceRequest publish(UUID clientId, ServiceCategory category, String description,
                                         ApproximateLocation approximate, ExactLocation exact,
                                         Instant expectedAt, BigDecimal budgetAmount, CurrencyCode budgetCurrency,
                                         List<String> photoKeys, Instant now) {
        require(clientId != null, "El cliente es obligatorio");
        require(category != null, "La categoría es obligatoria");
        requireText(description, "La descripción es obligatoria", MAX_DESCRIPTION_LENGTH);
        require(approximate != null, "La ubicación aproximada es obligatoria");
        requireText(approximate.city(), "La ciudad es obligatoria", MAX_PLACE_LENGTH);
        requireText(approximate.zone(), "La zona es obligatoria", MAX_PLACE_LENGTH);
        requireCoordinates(approximate.latitude(), approximate.longitude(), "aproximada");
        if (exact != null) {
            requireText(exact.address(), "La dirección exacta no puede estar vacía", MAX_ADDRESS_LENGTH);
            requireCoordinates(exact.latitude(), exact.longitude(), "exacta");
        }
        require(expectedAt != null, "El momento esperado es obligatorio");
        require(expectedAt.isAfter(now), "El momento esperado debe ser posterior a la publicación");
        require(budgetAmount != null, "El presupuesto es obligatorio");
        require(budgetAmount.signum() > 0, "El presupuesto debe ser mayor que cero");
        require(budgetAmount.stripTrailingZeros().scale() <= Quotation.AMOUNT_SCALE,
                "El presupuesto admite máximo dos decimales");
        require(budgetAmount.compareTo(Quotation.MAX_AMOUNT) <= 0,
                "El presupuesto no puede superar " + Quotation.MAX_AMOUNT.toPlainString());
        require(budgetCurrency != null, "La moneda del presupuesto es obligatoria");
        List<String> keys = photoKeys == null ? List.of() : photoKeys;
        require(keys.size() <= MAX_PHOTOS, "Se permiten máximo " + MAX_PHOTOS + " fotografías");
        keys.forEach(key -> requireText(key, "La clave de la fotografía es obligatoria", MAX_STORAGE_KEY_LENGTH));

        ServiceRequest request = new ServiceRequest(clientId, category, description, approximate, exact,
                expectedAt, budgetAmount, budgetCurrency, now);
        for (int index = 0; index < keys.size(); index++) {
            request.photos.add(new ServiceRequestPhoto(request, keys.get(index).strip(), index, now));
        }
        return request;
    }

    /** El cliente elige un único prestador; cierra la selección frente a los demás postulantes. */
    public void selectProvider(UUID providerId, Instant now) {
        require(providerId != null, "El prestador es obligatorio");
        transitionTo(ServiceRequestStatus.PROVIDER_SELECTED, now);
        this.selectedProviderId = providerId;
    }

    /** Formaliza la contratación; desde aquí la dirección exacta puede entregarse. */
    public void contract(Instant now) {
        transitionTo(ServiceRequestStatus.CONTRACTED, now);
    }

    /** El prestador contratado inicia el servicio (GC-437). Solo se puede desde {@code CONTRACTED}. */
    public void start(Instant now) {
        transitionTo(ServiceRequestStatus.IN_PROGRESS, now);
    }

    /** El técnico contratado declara que terminó (GC-441). Solo desde {@code IN_PROGRESS}; todavía no es el cierre. */
    public void finish(Instant now) {
        transitionTo(ServiceRequestStatus.TECHNICIAN_FINISHED, now);
    }

    /** El cliente confirma el cierre (GC-441). Solo desde {@code TECHNICIAN_FINISHED}; es definitivo. */
    public void close(Instant now) {
        transitionTo(ServiceRequestStatus.CLOSED, now);
    }

    /**
     * El cliente objeta el cierre (GC-441): el servicio vuelve a {@code IN_PROGRESS} en vez de cerrarse. Solo desde
     * {@code TECHNICIAN_FINISHED}: un servicio cerrado no se reabre.
     */
    public void objectClosure(Instant now) {
        transitionTo(ServiceRequestStatus.IN_PROGRESS, now);
    }

    public void cancel(Instant now) {
        transitionTo(ServiceRequestStatus.CANCELLED, now);
    }

    public void expire(Instant now) {
        transitionTo(ServiceRequestStatus.EXPIRED, now);
    }

    public boolean isExactLocationVisible() {
        return status.exposesExactLocation();
    }

    /**
     * Dirección exacta; vacía mientras la solicitud no esté contratada (ni en curso), o si el cliente no la indicó.
     */
    public Optional<ExactLocation> getExactLocation() {
        if (!isExactLocationVisible() || exactAddress == null) {
            return Optional.empty();
        }
        return Optional.of(new ExactLocation(exactAddress, exactLatitude, exactLongitude));
    }

    private void transitionTo(ServiceRequestStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static Double roundApproximate(Double coordinate) {
        return coordinate == null ? null : Math.round(coordinate * 100.0) / 100.0;
    }

    private static void requireCoordinates(Double latitude, Double longitude, String kind) {
        require((latitude == null) == (longitude == null),
                "Las coordenadas " + kind + " deben indicarse completas (latitud y longitud)");
        if (latitude != null) {
            require(latitude >= -90 && latitude <= 90, "La latitud " + kind + " debe estar entre -90 y 90");
            require(longitude >= -180 && longitude <= 180,
                    "La longitud " + kind + " debe estar entre -180 y 180");
        }
    }

    private static void requireText(String value, String message, int maxLength) {
        require(value != null && !value.isBlank(), message);
        require(value.strip().length() <= maxLength, message + " y no puede superar " + maxLength + " caracteres");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getClientId() {
        return clientId;
    }

    public ServiceCategory getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public ApproximateLocation getApproximateLocation() {
        return new ApproximateLocation(city, zone, approxLatitude, approxLongitude);
    }

    public Instant getExpectedAt() {
        return expectedAt;
    }

    /** Presupuesto orientativo (GC-421); {@code null} en las solicitudes anteriores a él. */
    public BigDecimal getBudgetAmount() {
        return budgetAmount;
    }

    public CurrencyCode getBudgetCurrency() {
        return budgetCurrency;
    }

    public ServiceRequestStatus getStatus() {
        return status;
    }

    public UUID getSelectedProviderId() {
        return selectedProviderId;
    }

    public List<String> getPhotoKeys() {
        return photos.stream().map(ServiceRequestPhoto::getStorageKey).toList();
    }

    public Instant getPublishedAt() {
        return publishedAt;
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

    /** No incluye descripción ni direcciones: la solicitud puede contener datos personales. */
    @Override
    public String toString() {
        return "ServiceRequest[id=" + id + ", category=" + category + ", status=" + status + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ServiceRequest that && id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return ServiceRequest.class.hashCode();
    }
}
