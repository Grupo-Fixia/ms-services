CREATE TABLE quotations (
    id                  UUID                     PRIMARY KEY,
    service_request_id  UUID                     NOT NULL,
    provider_id         UUID                     NOT NULL,
    amount              NUMERIC(12, 2)           NOT NULL,
    currency            VARCHAR(3)               NOT NULL,
    scope               VARCHAR(2000)            NOT NULL,
    conditions          VARCHAR(2000)            NOT NULL,
    agreed_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until         TIMESTAMP WITH TIME ZONE NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    responded_at        TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL,
    CONSTRAINT fk_quotations_request FOREIGN KEY (service_request_id)
        REFERENCES service_requests (id) ON DELETE CASCADE,
    CONSTRAINT ck_quotations_amount CHECK (amount > 0),
    CONSTRAINT ck_quotations_currency CHECK (currency IN ('COP')),
    CONSTRAINT ck_quotations_status CHECK (status IN
        ('SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT ck_quotations_validity CHECK (valid_until <= agreed_at),
    CONSTRAINT ck_quotations_responded CHECK
        (status NOT IN ('ACCEPTED', 'REJECTED') OR responded_at IS NOT NULL)
);

CREATE INDEX ix_quotations_request ON quotations (service_request_id, created_at);
