CREATE TABLE service_request_applications (
    id                  UUID                     PRIMARY KEY,
    service_request_id  UUID                     NOT NULL,
    provider_id         UUID                     NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL,
    CONSTRAINT fk_service_request_applications_request FOREIGN KEY (service_request_id)
        REFERENCES service_requests (id) ON DELETE CASCADE,
    CONSTRAINT ck_service_request_applications_status CHECK (status IN ('APPLIED', 'SELECTED', 'REJECTED'))
);

CREATE INDEX ix_service_request_applications_provider ON service_request_applications (provider_id, created_at);
CREATE INDEX ix_service_request_applications_request ON service_request_applications (service_request_id);
