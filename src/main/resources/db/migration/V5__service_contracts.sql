-- Contratación formalizada (GC-359, RF-035, CA-03): nace cuando el cliente acepta una cotización vigente y guarda una
-- copia de lo acordado, que no cambia aunque después se envíen otras cotizaciones. El pago del servicio ocurre fuera de
-- Fixia (RN-005, RN-006): no hay columnas de saldo, retención ni pago. El estado del servicio es el de la solicitud
-- (service_requests.status = CONTRACTED), que sigue siendo la única fuente de verdad.
CREATE TABLE service_contracts (
    id                  UUID                     PRIMARY KEY,
    service_request_id  UUID                     NOT NULL,
    quotation_id        UUID                     NOT NULL,
    client_id           UUID                     NOT NULL,
    provider_id         UUID                     NOT NULL,
    amount              NUMERIC(12, 2)           NOT NULL,
    currency            VARCHAR(3)               NOT NULL,
    scope               VARCHAR(2000)            NOT NULL,
    conditions          VARCHAR(2000)            NOT NULL,
    agreed_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    contracted_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_service_contracts_request UNIQUE (service_request_id),
    CONSTRAINT uk_service_contracts_quotation UNIQUE (quotation_id),
    CONSTRAINT fk_service_contracts_request FOREIGN KEY (service_request_id)
        REFERENCES service_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_service_contracts_quotation FOREIGN KEY (quotation_id)
        REFERENCES quotations (id) ON DELETE CASCADE,
    CONSTRAINT ck_service_contracts_amount CHECK (amount > 0),
    CONSTRAINT ck_service_contracts_currency CHECK (currency IN ('COP'))
);
CREATE INDEX ix_service_contracts_provider ON service_contracts (provider_id, contracted_at);
