-- Pago virtual retenido de cada contratación (GC-490). ms-services no guarda saldo ni mueve dinero: solo recuerda lo que
-- ms-payments le confirmó por los eventos pago.procesado (saldo retenido) y pago.fallido (no se pudo retener), para
-- permitir o impedir el inicio del servicio. Es una tabla aparte de service_contracts, que sigue siendo inmutable.
--
-- No hay fila mientras ms-payments no ha confirmado nada: eso es el estado PENDING y tampoco autoriza el inicio. No se
-- guarda el motivo del fallo (trae cifras del saldo del cliente). last_sequence es la posición del hecho en la historia
-- del pago (aggregate.sequence del sobre): un evento con una secuencia menor o igual llegó tarde y se ignora.
CREATE TABLE contract_payments (
    contract_id         UUID                     PRIMARY KEY,
    service_request_id  UUID                     NOT NULL,
    payment_id          UUID                     NOT NULL,
    status              VARCHAR(10)              NOT NULL,
    last_event_id       UUID                     NOT NULL,
    last_sequence       BIGINT                   NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_contract_payments_contract FOREIGN KEY (contract_id)
        REFERENCES service_contracts (id) ON DELETE CASCADE,
    CONSTRAINT ck_contract_payments_status CHECK (status IN ('HELD', 'FAILED')),
    CONSTRAINT ck_contract_payments_sequence CHECK (last_sequence >= 1)
);

-- Eventos ya aplicados, para que una reentrega (Kafka entrega al menos una vez) no se procese dos veces. La clave es el
-- eventId del sobre, que no cambia entre reintentos del productor.
CREATE TABLE processed_events (
    event_id        UUID                     PRIMARY KEY,
    event_type      VARCHAR(60)              NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
