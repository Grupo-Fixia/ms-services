-- GC-527: chat real por servicio contratado (historia GC-517, RF-038 a RF-040 y RF-043). Vive en ms-services y no en un
-- microservicio de Mensajería con MongoDB como dicen el DD V4 y el SAD V2: decisión del 2026-11-07 registrada en
-- docs/contracts/mensajeria.md. Cada mensaje pertenece a una contratación y solo lo ven sus dos participantes: el
-- cliente y el técnico que ejecuta (executor_id, GC-551). Es solo de inserción: los mensajes no se editan ni se borran.
CREATE TABLE service_messages (
    id                 UUID                     PRIMARY KEY,
    contract_id        UUID                     NOT NULL REFERENCES service_contracts (id) ON DELETE CASCADE,
    service_request_id UUID                     NOT NULL,
    sender_id          UUID                     NOT NULL,
    sender_role        VARCHAR(20)              NOT NULL,
    recipient_id       UUID                     NOT NULL,
    body               VARCHAR(2000)            NOT NULL,
    client_message_id  UUID,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_service_messages_role CHECK (sender_role IN ('CLIENT', 'PROFESSIONAL')),
    CONSTRAINT ck_service_messages_body CHECK (char_length(trim(body)) > 0),
    CONSTRAINT ck_service_messages_participants CHECK (sender_id <> recipient_id)
);

-- La conversación se lee en orden y por cursor (created_at, id).
CREATE INDEX idx_service_messages_contract ON service_messages (contract_id, created_at, id);

-- Reintentar el mismo envío (mismo client_message_id del mismo remitente) no duplica el mensaje. Los mensajes sin
-- client_message_id no chocan entre sí: NULL nunca es igual a NULL en un índice único.
CREATE UNIQUE INDEX ux_service_messages_client_message
    ON service_messages (contract_id, sender_id, client_message_id);
