# ms-services

Microservicio de **solicitudes de servicio** de Fixia: modelo y estados (GC-332), API para publicar y consultar
solicitudes (GC-333) y, en tareas posteriores, postulaciones, selección de prestador y contratación.

- Java 21, Spring Boot 3.2.5, Maven
- PostgreSQL 16 (base propia, migraciones con Flyway)
- Estructura en capas Spring, igual que `ms-users`: `config`, `controller`, `service`, `repository`, `domain`,
  `dto`, `exception`, `client` (ver SDD V2 §3.1, desviación P-06 pendiente de decisión)

## Configuración

| Variable | Descripción | Por defecto |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Conexión a PostgreSQL | `localhost:5432/ms-services` |
| `JWT_PUBLIC_KEY` | Clave pública RSA de ms-users en PEM (X.509), para verificar tokens | **obligatoria** fuera de `local` y `test` |
| `JWT_ISSUER` / `JWT_AUDIENCE` | Claims `iss` y `aud` esperados | `https://auth.fixia.com` / `https://api.fixia.com` |
| `USERS_BASE_URL` | URL interna de ms-users | `http://ms-users:8080` |
| `INTERNAL_API_KEY` | Clave compartida con ms-users para su ruta interna de revocación (el mismo valor en ambos) | vacía: toda petición autenticada se rechaza |
| `USERS_CONNECT_TIMEOUT` / `USERS_READ_TIMEOUT` | Tiempos de espera hacia ms-users | `1s` / `2s` |

Sin `JWT_PUBLIC_KEY` el servicio **no arranca** (salvo en `local` y `test`, que usan una clave efímera). Las claves
y la clave interna nunca se guardan en el repositorio.

## Seguridad

- Los tokens los emite ms-users (RS256); este servicio solo los **verifica** con la clave pública.
- La lista de tokens revocados vive en la base de ms-users (Database per Service). En **cada** petición
  autenticada se consulta `GET /internal/tokens/{jti}/status` de ms-users con la cabecera `X-Internal-Api-Key`.
  Un cierre de sesión surte efecto de inmediato (sin caché).
- **Falla cerrado**: si ms-users no responde (caído, lento o con error), la petición se rechaza con 401. Es una
  dependencia consciente: sin ms-users, las rutas autenticadas de este servicio no están disponibles.
- Roles: las rutas `/api/services/requests/**` exigen `ROLE_CLIENT`; un técnico recibe 403.
- Todas las respuestas 401 son idénticas y no indican el motivo.

## API (GC-333)

Contrato completo en [`docs/openapi.yaml`](docs/openapi.yaml).

| Método y ruta | Descripción |
|---|---|
| `POST /api/services/requests` | Publica una solicitud en estado `PUBLISHED` (201 + `Location`) |
| `GET /api/services/requests` | Mis solicitudes, la más reciente primero (sin paginación por ahora) |
| `GET /api/services/requests/{id}` | Una de mis solicitudes; 404 si no existe o es de otro cliente |

El cliente sale siempre del token, nunca del cuerpo ni de la ruta. La dirección exacta se guarda pero no se
devuelve hasta que la solicitud está `CONTRACTED`.

## Postulaciones (GC-342)

| Método y ruta | Descripción |
|---|---|
| `POST /api/services/applications` | El técnico se postula a una solicitud (201 + `Location`) |
| `GET /api/services/applications` | Mis postulaciones, la más reciente primero |
| `GET /api/services/applications/{id}` | Una de mis postulaciones; 404 si no existe o es de otro técnico |

- Solo `ROLE_PROFESSIONAL`; el técnico sale del token. Tabla `applications` (migración V3), con
  estados `APPLIED -> SELECTED | REJECTED`.
- La solicitud debe estar `PUBLISHED` y con el momento esperado por delante (si no, 409).
- **Elegibilidad:** ms-users decide si el técnico puede atender la solicitud (categoría, zona de atención y
  disponibilidad) en `POST /internal/technicians/{userId}/eligibility`, con la misma `INTERNAL_API_KEY` de la
  revocación. Si no es elegible, 422 con `reasons`; si ms-users no responde, 503 y no se registra nada.
  La consulta acepta varias solicitudes, para que las reglas de compatibilidad (GC-337) la reutilicen.
- La consulta a ms-users va fuera de la transacción; después se bloquea la solicitud y se vuelve a comprobar que
  siga abierta, para que una postulación no se cruce con la selección del prestador (GC-348).
- **Sin duplicados (GC-343):** un técnico se postula una sola vez a cada solicitud. Se comprueba antes de consultar a
  ms-users y otra vez con la solicitud bloqueada; además, la base lo impide con la restricción única
  `(service_request_id, provider_id)` (migración V4). La respuesta es 409 "Postulación duplicada" con el
  `applicationId` existente.
- La verificación del técnico (`VALID`) todavía no se exige: no existe el flujo de verificación (pendiente de RN-001).

## Postulantes de una solicitud (GC-347)

| Método y ruta | Descripción |
|---|---|
| `GET /api/services/requests/{id}/applications` | Prestadores postulados a una solicitud mía y el estado de cada postulación |

- Solo `ROLE_CLIENT`. La solicitud debe ser del cliente del token: si no existe, es de otro cliente o el `id` no es
  un UUID, la respuesta es la misma (404 "No existe la solicitud indicada").
- Lista en orden de llegada (la primera postulación primero); sin postulantes, `200` con `[]`. Sin paginación por
  ahora. Se puede consultar en cualquier estado de la solicitud, para ver también a quién se eligió.
- Cada elemento trae `id`, `providerId`, `status` (`APPLIED`, `SELECTED` o `REJECTED`), `createdAt` y `updatedAt`.
  No incluye datos de la solicitud ni ubicación. El perfil público del prestador se consulta aparte (GC-350).

## Selección de prestador (GC-348)

| Método y ruta | Descripción |
|---|---|
| `POST /api/services/requests/{id}/applications/{applicationId}/selection` | El cliente elige al prestador de su solicitud (200 con la solicitud en `PROVIDER_SELECTED`) |

- Solo `ROLE_CLIENT`, y la solicitud debe ser suya. Una solicitud ajena, inexistente o con `id` inválido da la misma 404
  que el resto de rutas; una postulación que no es de esa solicitud, no existe o no es un UUID da 404 "Postulación
  no encontrada".
- **Todo o nada.** En una sola transacción y con la solicitud bloqueada (`FOR UPDATE`): la solicitud pasa a
  `PROVIDER_SELECTED` con `selectedProviderId`, la postulación elegida a `SELECTED` y las demás a `REJECTED`.
- **Un solo prestador.** La solicitud debe seguir `PUBLISHED` y con el momento esperado por delante; si ya tiene
  prestador, se canceló o su momento esperado pasó, la respuesta es 409 "Solicitud no disponible". Dos selecciones
  simultáneas dejan una sola: la otra espera el bloqueo y recibe el 409. El mismo bloqueo evita que una postulación
  nueva (GC-342) se cruce con la selección. El bloqueo solo se toma sobre solicitudes del propio cliente.
- **Reintento seguro.** Repetir la selección sobre la postulación que ya quedó `SELECTED` devuelve 200 con el estado
  actual y no cambia nada (útil si se pierde la respuesta); elegir otra postulación da 409.
- **Pendiente:** ningún proceso mueve todavía las solicitudes con el momento esperado vencido a `EXPIRED`; hasta
  entonces siguen `PUBLISHED` y solo se les rechaza la selección y la postulación.
- La decisión queda persistida y se ve en `GET /api/services/requests/{id}/applications` (GC-347). La dirección exacta
  sigue sin entregarse hasta que la solicitud esté `CONTRACTED`.

## Pruebas

Requiere Java 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`).

```bash
mvn -B test      # lo que corre el CI
mvn -B verify    # tests + cobertura JaCoCo (mínimo 85 % de líneas)
```

Las pruebas usan H2 en modo PostgreSQL con las mismas migraciones Flyway; no necesitan Docker ni ms-users (la
consulta de revocación se simula).

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

## Cotización (GC-353)

`Quotation` (tabla `quotations`, migración V2): la cotización del prestador seleccionado para una solicitud, con valor,
moneda, alcance, condiciones, fecha acordada y vigencia (RF-031). El pago del servicio ocurre fuera de Fixia.

| Estado | Nombre (`statusName`) | Significado |
|---|---|---|
| `SENT` | Enviada | Enviada por el prestador seleccionado; espera respuesta del cliente |
| `ACCEPTED` | Aceptada | Aceptada por el cliente (terminal) |
| `REJECTED` | Rechazada | Rechazada por el cliente (terminal) |
| `EXPIRED` | Vencida | Pasó su vigencia sin respuesta (terminal) |
| `SUPERSEDED` | Reemplazada | Reemplazada por una cotización más reciente del mismo prestador (terminal) |

Como en ms-users (`ServiceCategory`: `PLUMBING` → "Plomería"), el código del estado va en inglés en la API y en la
base, y el nombre en español es solo para mostrar: viaja en `statusName` (GC-354).

```
SENT -> ACCEPTED | REJECTED | EXPIRED | SUPERSEDED
```

- Solo la crea el prestador seleccionado (`provider_id` = `selected_provider_id`, el `users.id` del técnico en
  ms-users) de una solicitud en `PROVIDER_SELECTED`.
- Una cotización enviada **no se edita**: para cambiar condiciones se envía otra y la anterior queda `SUPERSEDED`.
  Así ninguna cotización modificada queda vigente sin una nueva aceptación (RF-033).
- Una cotización vencida (`valid_until` ya pasó) no se puede aceptar aunque todavía figure `SENT`.
- Valor `NUMERIC(12,2)` mayor que cero y moneda explícita (RF-073); por ahora solo `COP`.
- La base valida valor, moneda, estado, que la vigencia no pase de la fecha acordada y que una cotización aceptada o
  rechazada tenga fecha de respuesta. Borrar la solicitud borra sus cotizaciones.
- A lo sumo una cotización `SENT` y ninguna nueva si ya hay una `ACCEPTED` por solicitud: lo controla la API
  (GC-354) con la solicitud bloqueada, porque H2 no admite índices únicos parciales.

## API de cotizaciones (GC-354)

| Método y ruta | Rol | Descripción |
|---|---|---|
| `POST /api/services/quotations` | `ROLE_PROFESSIONAL` | El prestador seleccionado crea y envía una cotización (201, `SENT`) |
| `GET /api/services/quotations` | `ROLE_PROFESSIONAL` | Mis cotizaciones, la más reciente primero; filtro opcional `?serviceRequestId=` |
| `GET /api/services/quotations/{quotationId}` | `ROLE_PROFESSIONAL` | Una de mis cotizaciones; 404 si no existe o es de otro prestador |
| `GET /api/services/requests/{id}/quotations` | `ROLE_CLIENT` | Cotizaciones recibidas en mi solicitud, la más reciente primero |

- Cuerpo del `POST`: `serviceRequestId`, `amount` (mayor que cero, dos decimales), `currency` (`COP`), `scope`,
  `conditions` (hasta 2000 caracteres), `agreedAt` y `validUntil` (ISO-8601). El prestador sale del token.
- **Selección vigente.** Solo cotiza el prestador seleccionado (GC-348) de una solicitud en `PROVIDER_SELECTED`. Si no
  es él, o la solicitud ya no está en ese estado, responde 409 "Cotización no permitida" con el mismo mensaje, para no
  revelar a quién se eligió. Si la solicitud ya tiene una cotización `ACCEPTED`, también 409.
- **Fechas.** `agreedAt` y `validUntil` deben ser posteriores al envío y la vigencia no puede pasar de la fecha
  acordada (400 "Datos inválidos").
- **Nueva versión.** Enviar otra cotización deja la `SENT` anterior en `SUPERSEDED` (o `EXPIRED` si ya venció), en la
  misma transacción y con la solicitud bloqueada (`FOR UPDATE`): siempre queda a lo sumo una esperando respuesta y
  dos envíos simultáneos no dejan dos vigentes.
- **Vencimiento.** La respuesta trae `expired: true` cuando la cotización sigue `SENT` pero su vigencia ya pasó; no se
  puede aceptar. Ningún proceso la pasa todavía a `EXPIRED` por sí solo.
- Aceptar o rechazar es GC-358. El evento `cotizacion.enviada` (ADR-024, outbox) queda pendiente: el servicio todavía
  no tiene tabla OUTBOX ni publicador.
