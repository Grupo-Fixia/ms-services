-- Replicación de estado entre servicios (GC-547, cero dependencias síncronas).
--
-- state_publications: lo que este servicio ya publicó de cada entidad que otros necesitan (participación en servicios
-- y trabajo de los técnicos de empresa). Un proceso compara cada pocos segundos el estado actual con el hash guardado y
-- escribe en el Outbox solo lo que cambió;
-- cada tanto lo vuelve a publicar todo para que un consumidor nuevo lo encuentre aunque Kafka ya lo haya borrado.
CREATE TABLE state_publications (
    state_type      VARCHAR(60)              NOT NULL,
    state_key       UUID                     NOT NULL,
    hash            VARCHAR(64)              NOT NULL,
    sequence        BIGINT                   NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_state_publications PRIMARY KEY (state_type, state_key)
);

-- replicated_states: copia local del estado que publica ms-users (técnicos y empresas). Se guarda la versión de mayor
-- secuencia de cada clave; ms-services decide con ella la elegibilidad, los perfiles, la pertenencia a empresas y los
-- técnicos compatibles, en lugar de llamar a ms-users o a ms-matching-geo.
CREATE TABLE replicated_states (
    state_type      VARCHAR(60)              NOT NULL,
    state_key       UUID                     NOT NULL,
    sequence        BIGINT                   NOT NULL,
    removed         BOOLEAN                  NOT NULL,
    data            TEXT                     NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_replicated_states PRIMARY KEY (state_type, state_key)
);
