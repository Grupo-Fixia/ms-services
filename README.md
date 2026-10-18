# ms-services

Microservicio de **solicitudes de servicio** de Fixia: modelo, estados y (en tareas posteriores) API de
publicación, postulaciones, selección de prestador y contratación.

- Java 21, Spring Boot 3.2.5, Maven
- PostgreSQL 16 (base propia, migraciones con Flyway)
- Estructura en capas Spring, igual que `ms-users`: `config`, `controller`, `service`, `repository`, `domain`,
  `dto`, `exception` (ver SDD V2 §3.1, desviación P-06 pendiente de decisión)

## Pruebas

Requiere Java 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`).

```bash
mvn -B test      # lo que corre el CI
mvn -B verify    # tests + cobertura JaCoCo (mínimo 85 % de líneas)
```

Las pruebas usan H2 en modo PostgreSQL con las mismas migraciones Flyway; no necesitan Docker.

## Modelo (GC-332)

`ServiceRequest` (tabla `service_requests`) y sus fotos (`service_request_photos`).

| Estado | Significado |
|---|---|
| `PUBLISHED` | Publicada por el cliente; visible para prestadores compatibles |
| `PROVIDER_SELECTED` | El cliente eligió un único prestador |
| `CONTRACTED` | Contratación formalizada |
| `CANCELLED` | Cancelada (terminal) |
| `EXPIRED` | Venció sin contratación (terminal) |

```
PUBLISHED         -> PROVIDER_SELECTED | CANCELLED | EXPIRED
PROVIDER_SELECTED -> CONTRACTED | CANCELLED
```

**Privacidad.** Los prestadores solo ven la ubicación aproximada (ciudad, zona y coordenadas redondeadas a dos
decimales). La dirección exacta se guarda aparte y `ServiceRequest#getExactLocation()` solo la entrega cuando
la solicitud está `CONTRACTED`.

### Supuestos pendientes de validar con el equipo

El SRS V4 y el DD V3 no estaban disponibles al implementar; el modelo sale de las historias GC-296, GC-299 y
GC-301.

- No existe reapertura de `PROVIDER_SELECTED` a `PUBLISHED`.
- Nombre `ServiceRequest` (Jira: "solicitud") frente a `SERVICIO` en el DD V3.
- Las categorías se redefinen aquí con los mismos valores que `ms-users`.
- Las fotos guardan la clave del objeto en el almacenamiento, no la URL.
