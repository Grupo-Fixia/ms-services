CREATE TABLE service_requests (
    id                    UUID                     PRIMARY KEY,
    client_id             UUID                     NOT NULL,
    category              VARCHAR(30)              NOT NULL,
    description           VARCHAR(2000)            NOT NULL,
    city                  VARCHAR(100)             NOT NULL,
    zone                  VARCHAR(100)             NOT NULL,
    approx_latitude       DOUBLE PRECISION,
    approx_longitude      DOUBLE PRECISION,
    exact_address         VARCHAR(255),
    exact_latitude        DOUBLE PRECISION,
    exact_longitude       DOUBLE PRECISION,
    expected_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    selected_provider_id  UUID,
    published_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    version               BIGINT                   NOT NULL,
    CONSTRAINT ck_service_requests_category CHECK (category IN
        ('PLUMBING', 'ELECTRICAL', 'MAINTENANCE', 'LOCKSMITHING', 'PAINTING', 'CARPENTRY')),
    CONSTRAINT ck_service_requests_status CHECK (status IN
        ('PUBLISHED', 'PROVIDER_SELECTED', 'CONTRACTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_service_requests_selected_provider CHECK
        (status NOT IN ('PROVIDER_SELECTED', 'CONTRACTED') OR selected_provider_id IS NOT NULL)
);

CREATE INDEX ix_service_requests_client ON service_requests (client_id);
CREATE INDEX ix_service_requests_status_category ON service_requests (status, category);

CREATE TABLE service_request_photos (
    id                  UUID                     PRIMARY KEY,
    service_request_id  UUID                     NOT NULL,
    storage_key         VARCHAR(255)             NOT NULL,
    sort_order          INTEGER                  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_service_request_photos_order UNIQUE (service_request_id, sort_order),
    CONSTRAINT fk_service_request_photos_request FOREIGN KEY (service_request_id)
        REFERENCES service_requests (id) ON DELETE CASCADE
);
