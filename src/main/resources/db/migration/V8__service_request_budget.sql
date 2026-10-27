-- Presupuesto orientativo del cliente al publicar la solicitud (GC-421): un valor único en COP, solo como guía para el
-- prestador. Es distinto del precio que propone el técnico (applications.proposed_amount), del valor de la cotización
-- y del precio contratado. Las solicitudes anteriores quedan sin presupuesto (NULL): no se inventan importes. Los
-- IS NOT NULL son necesarios: con un valor en NULL la comparación da UNKNOWN y un CHECK deja pasar UNKNOWN.
ALTER TABLE service_requests ADD COLUMN budget_amount NUMERIC(12, 2);
ALTER TABLE service_requests ADD COLUMN budget_currency VARCHAR(3);

ALTER TABLE service_requests
    ADD CONSTRAINT ck_service_requests_budget CHECK (
        (budget_amount IS NULL AND budget_currency IS NULL)
        OR (budget_amount IS NOT NULL AND budget_amount > 0
            AND budget_currency IS NOT NULL AND budget_currency IN ('COP')));
