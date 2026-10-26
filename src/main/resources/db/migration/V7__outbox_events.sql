-- Outbox transaccional (GC-433). Cada fila es un hecho de negocio confirmado, escrito en la misma transacción que el
-- cambio que lo produjo; un relay lo publica en Kafka después del commit (GC-434). Contrato en docs/events/README.md.
-- Las columnas del hecho no cambian nunca; el relay solo actualiza las de envío (status en adelante).
-- Sin clave foránea a service_requests: el hecho debe sobrevivir aunque la solicitud cambie o se borre.
CREATE TABLE outbox_events (
    event_id            UUID                     PRIMARY KEY,
    event_type          VARCHAR(60)              NOT NULL,
    schema_version      SMALLINT                 NOT NULL,
    aggregate_type      VARCHAR(40)              NOT NULL,
    aggregate_id        UUID                     NOT NULL,
    aggregate_sequence  BIGINT                   NOT NULL,
    topic               VARCHAR(120)             NOT NULL,
    message_key         VARCHAR(60)              NOT NULL,
    payload             VARCHAR(16000)           NOT NULL,
    occurred_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    attempts            INTEGER                  NOT NULL,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error          VARCHAR(500),
    published_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_outbox_aggregate_sequence UNIQUE (aggregate_id, aggregate_sequence),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_schema_version CHECK (schema_version >= 1),
    CONSTRAINT ck_outbox_sequence CHECK (aggregate_sequence >= 1),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

-- Para que el relay encuentre lo pendiente que ya toca reintentar.
CREATE INDEX ix_outbox_pending ON outbox_events (status, next_attempt_at);
