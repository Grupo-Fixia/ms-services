ALTER TABLE service_request_applications
    ADD CONSTRAINT uk_service_request_applications UNIQUE (service_request_id, provider_id);
