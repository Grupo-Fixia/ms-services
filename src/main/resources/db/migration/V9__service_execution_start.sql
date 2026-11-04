-- Inicio de la ejecución del servicio (GC-437): la solicitud puede pasar de CONTRACTED a IN_PROGRESS y cada cambio de
-- estado queda en un historial que ven el cliente y el prestador. Las demás etapas (TECHNICIAN_FINISHED, CLOSED) las
-- agrega GC-441 con su propia migración.
--
-- PostgreSQL no tiene ALTER CONSTRAINT para un CHECK: se elimina y se vuelve a crear, sin IF EXISTS ni NOT VALID para
-- que H2 (modo PostgreSQL, el de las pruebas) lo ejecute igual.
ALTER TABLE service_requests DROP CONSTRAINT ck_service_requests_status;
ALTER TABLE service_requests
    ADD CONSTRAINT ck_service_requests_status CHECK (status IN
        ('PUBLISHED', 'PROVIDER_SELECTED', 'CONTRACTED', 'IN_PROGRESS', 'CANCELLED', 'EXPIRED'));

ALTER TABLE service_requests DROP CONSTRAINT ck_service_requests_selected_provider;
ALTER TABLE service_requests
    ADD CONSTRAINT ck_service_requests_selected_provider CHECK
        (status NOT IN ('PROVIDER_SELECTED', 'CONTRACTED', 'IN_PROGRESS') OR selected_provider_id IS NOT NULL);

-- Historial de transiciones de la solicitud: es el que ven los usuarios, distinto del Outbox, que es técnico. Solo se
-- anexan filas. from_status es nulo en la publicación (no tiene estado de origen). Las transiciones anteriores a esta
-- migración no se reconstruyen. No hay CHECK sobre los valores de estado: el enum del dominio ya los guarda, y así las
-- etapas siguientes no tienen que tocar esta tabla.
CREATE TABLE service_request_transitions (
    id                  UUID                     PRIMARY KEY,
    service_request_id  UUID                     NOT NULL,
    from_status         VARCHAR(30),
    to_status           VARCHAR(30)              NOT NULL,
    actor_id            UUID,
    actor_role          VARCHAR(20)              NOT NULL,
    reason              VARCHAR(500),
    occurred_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_service_request_transitions_actor_role CHECK (actor_role IN ('CLIENT', 'PROFESSIONAL', 'SYSTEM')),
    CONSTRAINT ck_service_request_transitions_actor CHECK (actor_id IS NOT NULL OR actor_role = 'SYSTEM'),
    CONSTRAINT fk_service_request_transitions_request FOREIGN KEY (service_request_id)
        REFERENCES service_requests (id) ON DELETE CASCADE
);

CREATE INDEX ix_service_request_transitions_request ON service_request_transitions (service_request_id, occurred_at);
