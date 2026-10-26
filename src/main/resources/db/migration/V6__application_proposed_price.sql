-- Precio que propone el técnico al postularse (GC-423). Es distinto del presupuesto del cliente y del valor de la
-- cotización. Las postulaciones anteriores quedan sin precio (NULL): no se inventan importes. Los IS NOT NULL
-- son necesarios: con un valor en NULL la comparación da UNKNOWN y un CHECK deja pasar UNKNOWN.
ALTER TABLE applications ADD COLUMN proposed_amount NUMERIC(12, 2);
ALTER TABLE applications ADD COLUMN currency VARCHAR(3);

ALTER TABLE applications
    ADD CONSTRAINT ck_applications_proposed_price CHECK (
        (proposed_amount IS NULL AND currency IS NULL)
        OR (proposed_amount IS NOT NULL AND proposed_amount > 0
            AND currency IS NOT NULL AND currency IN ('COP')));
