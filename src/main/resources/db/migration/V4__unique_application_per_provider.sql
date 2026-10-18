ALTER TABLE applications
    ADD CONSTRAINT uk_applications UNIQUE (service_request_id, provider_id);
