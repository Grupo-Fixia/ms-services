package com.fixia.services.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La migración V11 numera las cotizaciones que ya existían (GC-427). Se migra una base propia hasta V10, se insertan
 * cotizaciones como las habría dejado el sistema antes de la columna, se migra a V11 y se comprueba la numeración: por
 * antigüedad dentro de cada solicitud y, a igual instante, por id. El resto de las pruebas arrancan con todas las
 * migraciones aplicadas y por eso no pueden cubrir este caso.
 */
class QuotationRevisionMigrationTest {

    /** Una base en memoria propia por prueba: cada una migra desde cero y el orden de ejecución no las mezcla. */
    private static String url(String name) {
        return "jdbc:h2:mem:quotation-revision-" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    }

    private static final String REQUEST_A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String REQUEST_B = "aaaaaaaa-0000-4000-8000-000000000002";
    private static final String PROVIDER = "22222222-2222-4222-8222-222222222222";

    private static Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target(target).load();
    }

    private static void insertRequest(JdbcTemplate jdbc, String id) {
        jdbc.update("INSERT INTO service_requests (id, client_id, category, description, city, zone, expected_at, "
                + "status, selected_provider_id, published_at, created_at, updated_at, version) VALUES (?, "
                + "'11111111-1111-4111-8111-111111111111', 'PLUMBING', 'Fuga', 'Bogotá', 'Chapinero', "
                + "TIMESTAMP WITH TIME ZONE '2030-01-10 10:00:00+00', 'PROVIDER_SELECTED', ?, "
                + "TIMESTAMP WITH TIME ZONE '2030-01-01 10:00:00+00', TIMESTAMP WITH TIME ZONE '2030-01-01 10:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2030-01-01 10:00:00+00', 0)", id, PROVIDER);
    }

    /** Cotización como la dejaba el sistema antes de V11: sin la columna de versión. */
    private static void insertQuotation(JdbcTemplate jdbc, String id, String requestId, String status,
                                        String createdAt) {
        jdbc.update("INSERT INTO quotations (id, service_request_id, provider_id, amount, currency, scope, conditions, "
                + "agreed_at, valid_until, status, responded_at, created_at, updated_at, version) VALUES (?, ?, ?, "
                + "100000, 'COP', 'Alcance', 'Condiciones', TIMESTAMP WITH TIME ZONE '2030-01-09 10:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2030-01-05 10:00:00+00', ?, NULL, TIMESTAMP WITH TIME ZONE '" + createdAt
                + "', TIMESTAMP WITH TIME ZONE '" + createdAt + "', 0)", id, requestId, PROVIDER, status);
    }

    @Test
    void numeraLasCotizacionesExistentesPorAntiguedadPorSolicitudYDesempataPorId() {
        String url = url("renumbering");
        flyway(url, "10").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        insertRequest(jdbc, REQUEST_A);
        insertRequest(jdbc, REQUEST_B);
        // Solicitud A, insertadas fuera de orden: la más nueva primero y dos con el mismo instante.
        insertQuotation(jdbc, "cccccccc-0000-4000-8000-000000000003", REQUEST_A, "SENT", "2030-01-03 10:00:00+00");
        insertQuotation(jdbc, "cccccccc-0000-4000-8000-000000000002", REQUEST_A, "SUPERSEDED", "2030-01-02 10:00:00+00");
        insertQuotation(jdbc, "cccccccc-0000-4000-8000-000000000001", REQUEST_A, "SUPERSEDED", "2030-01-02 10:00:00+00");
        // Solicitud B: una sola, que debe empezar en 1 aunque A ya tenga tres.
        insertQuotation(jdbc, "cccccccc-0000-4000-8000-0000000000b1", REQUEST_B, "SENT", "2030-01-04 10:00:00+00");

        flyway(url, "11").migrate();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, revision FROM quotations ORDER BY service_request_id, revision");
        assertThat(rows).extracting(row -> row.get("id") + "=" + row.get("revision")).containsExactly(
                "cccccccc-0000-4000-8000-000000000001=1",
                "cccccccc-0000-4000-8000-000000000002=2",
                "cccccccc-0000-4000-8000-000000000003=3",
                "cccccccc-0000-4000-8000-0000000000b1=1");
    }

    @Test
    void trasMigrarLaVersionEsObligatoriaUnicaPorSolicitudYMayorQueCero() {
        String url = url("constraints");
        flyway(url, "11").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        insertRequest(jdbc, REQUEST_A);

        String insert = "INSERT INTO quotations (id, service_request_id, provider_id, amount, currency, scope, "
                + "conditions, agreed_at, valid_until, status, created_at, updated_at, version, revision) VALUES "
                + "(?, ?, ?, 100000, 'COP', 'a', 'b', TIMESTAMP WITH TIME ZONE '2030-01-09 10:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2030-01-05 10:00:00+00', 'SENT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?)";

        assertThat(jdbc.update(insert, "cccccccc-0000-4000-8000-0000000000c1", REQUEST_A, PROVIDER, 1)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(insert, "cccccccc-0000-4000-8000-0000000000c2", REQUEST_A, PROVIDER, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "cccccccc-0000-4000-8000-0000000000c3", REQUEST_A, PROVIDER, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "cccccccc-0000-4000-8000-0000000000c4", REQUEST_A, PROVIDER, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
