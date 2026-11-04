-- Metadatos de evidencias de servicios (GC-439, RF-047, RD-11).
-- El archivo binario vive en el bucket privado 'evidences' de RustFS (S3), nunca en la base de datos ni con URLs
-- públicas permanentes. Esta tabla guarda los metadatos y la prueba de integridad (SHA-256) tras confirmarse la subida.
CREATE TABLE service_evidences (
    id UUID PRIMARY KEY,
    service_request_id UUID NOT NULL REFERENCES service_requests(id),
    contract_id UUID NOT NULL REFERENCES service_contracts(id),
    uploaded_by UUID NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_service_evidences_key UNIQUE (object_key),
    CONSTRAINT chk_evidence_status CHECK (status IN ('PENDING_UPLOAD', 'AVAILABLE', 'FAILED'))
);

CREATE INDEX ix_service_evidences_contract ON service_evidences(contract_id);
CREATE INDEX ix_service_evidences_request ON service_evidences(service_request_id);

