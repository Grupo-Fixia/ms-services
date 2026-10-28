# Contrato de eventos de negocio de ms-services (GC-436)

Versión 1 del contrato, Sprint 5. Define qué hechos de negocio publica `ms-services` en Kafka, con qué forma, cómo
se versionan y cómo se conservan en el lakehouse sin duplicarse. Lo implementan GC-433 (Outbox en `ms-services`),
GC-434 (relay del Outbox a Kafka) y GC-435 (Capa de Ingesta de `ms-intake-lakehouse`).

Los esquemas están en [`schemas/`](schemas/) (JSON Schema 2020-12) y un flujo completo de ejemplo en
[`examples/flujo-completo/`](examples/flujo-completo/). Los esquemas son la referencia: si este texto y un esquema no
coinciden, manda el esquema.

## 1. Decisiones de partida

- **Eventos completos, no referencias.** Cada evento trae los datos de negocio necesarios del hecho confirmado
  (payload autosuficiente). Un consumidor no consulta la API ni la base de `ms-services` para reconstruirlo, y el
  hecho conserva lo que era aunque la solicitud cambie después. Reemplaza los eventos mínimos `{colección, id}` de
  ADR-024 y el "pull de datos completos" de la Arquitectura de Datos V2 (§1.6.1).
- **"Completo" no es "todo".** Solo datos de negocio necesarios: nunca contraseñas, tokens, dirección exacta,
  fotografías, nombres ni datos de contacto (sección 7).
- **Outbox transaccional.** El cambio de negocio y su evento se guardan en la misma transacción local; un relay los
  publica después del commit (ADR-024). Si la transacción se revierte, no queda evento.
- **Entrega al menos una vez.** No se promete exactly-once entre la base, Kafka y los consumidores: un evento puede
  llegar repetido, siempre con el mismo `eventId`, y los consumidores lo tratan de forma idempotente.
- **Lakehouse de solo lectura en este sprint.** De las cuatro capas de la Arquitectura de Datos V2 (Ingesta,
  Almacenamiento, Análisis y Consumo) se implementa la lectura: la Capa de Ingesta consume los eventos y la Capa de
  Almacenamiento los conserva en **RustFS** (API S3). Análisis, Consumo y el paso de caliente a frío quedan para
  después.
- **No hay otra base que sincronizar.** El único efecto de un evento en este sprint es quedar conservado en el
  lakehouse. Ningún otro servicio mantiene una proyección alimentada por estos eventos.

## 2. Quién publica, quién consume, quién custodia

| Dato o efecto | Custodio (fuente de verdad) | Cómo llega a los demás |
| --- | --- | --- |
| Solicitudes, postulaciones, selección, cotizaciones y contrataciones | `ms-services` (su base PostgreSQL) | Eventos de este contrato |
| Tabla `outbox_events` | `ms-services` (misma base, misma transacción) | La lee solo el relay de `ms-services` |
| Tópico `fixia.services.service-requests` | Infraestructura (DevOps) | `ms-services` produce; `ms-intake-lakehouse` consume |
| Historia de eventos (copia inmutable) | `ms-intake-lakehouse` en RustFS | Lectura futura de las capas de Análisis y Consumo y de Auditoría (CD-09) |
| Perfiles, evaluaciones y reputación | `ms-users` | No viajan en estos eventos (solo `users.id`) |

## 3. Tópico, clave y cabeceras

- **Tópico:** `fixia.services.service-requests`. Uno solo para todos los hechos del flujo, porque todos pertenecen a
  una solicitud de servicio y deben quedar en orden. Con un tópico por tipo de evento, Kafka no garantiza el orden
  entre, por ejemplo, la selección y la cotización de la misma solicitud.
- **Clave del mensaje:** `aggregate.id` (el id de la solicitud). Todos los eventos de una solicitud caen en la misma
  partición y se leen en el orden en que se publicaron.
- **Particiones y retención (propuesta para GC-418):** 3 particiones; la retención actual de QA (24 h) es
  suficiente porque la fuente de reproceso es el Outbox (sección 10).
- **Cabeceras:** `eventId`, `eventType`, `schemaVersion` y `content-type: application/json`. Repiten datos del
  cuerpo para filtrar sin deserializar; si no coinciden con el cuerpo, el mensaje es inválido.
- **Cuerpo:** el sobre de la sección 4 en JSON UTF-8.

## 4. Sobre común (`envelope.v1.schema.json`)

| Campo | Tipo | Regla |
| --- | --- | --- |
| `eventId` | UUID | Se genera al escribir el evento en el Outbox. No cambia en reintentos ni reentregas: es la clave de idempotencia |
| `eventType` | texto | Uno de los tipos del catálogo (sección 5) |
| `schemaVersion` | entero ≥ 1 | Versión del esquema de `data` para ese tipo (sección 9) |
| `occurredAt` | instante UTC | Momento del hecho, con el mismo reloj de la transacción (`2026-10-26T15:00:00Z`) |
| `producer` | `ms-services` | Servicio que publica |
| `aggregate.type` | `ServiceRequest` | Agregado al que pertenece el hecho |
| `aggregate.id` | UUID | Id de la solicitud; es también la clave del mensaje |
| `aggregate.sequence` | entero ≥ 1 | Posición del hecho en la historia de la solicitud (sección 8) |
| `correlationId` | UUID | Id de la solicitud. Otros productores (pagos, evaluaciones) usarán el mismo valor para enlazar sus eventos con este servicio |
| `actor.userId` | UUID o null | `users.id` de quien hizo la operación (el `sub` del token sin `usr_`); null si fue el sistema |
| `actor.role` | `CLIENT`, `PROFESSIONAL`, `COMPANY` o `SYSTEM` | Rol con el que actuó (`COMPANY` desde GC-468) |
| `data` | objeto | Datos del hecho según el esquema de su tipo y versión |

Los importes viajan como `{"amount": "150000.00", "currency": "COP"}`: el valor es **texto** con dos decimales, para
que ningún consumidor lo convierta a punto flotante. Las fechas siempre en UTC con `Z`.

## 5. Catálogo de eventos (versión 1)

Solo hay evento cuando el estado cambia de verdad. Un reintento que no cambia nada (repetir una selección ya hecha,
aceptar una cotización ya aceptada con el contrato ya creado) no publica otro evento.

| Evento | Cuándo (operación de `ms-services`) | Actor | Datos principales |
| --- | --- | --- | --- |
| `solicitud.publicada` | `ServiceRequestService#create` (RF-020) | Cliente | Categoría, descripción, ubicación **aproximada**, momento esperado, número de fotos |
| `postulacion.registrada` | `ApplicationService#apply` (RF-024, GC-423) | Técnico | Postulación, prestador y **precio propuesto** |
| `prestador.seleccionado` | `ServiceRequestService#selectProvider` (RF-026 a RF-028) | Cliente | Postulación y prestador elegidos, su precio, postulaciones rechazadas |
| `cotizacion.enviada` | `QuotationService#send` (RF-031) | Técnico | Precio, alcance, condiciones, fecha acordada, vigencia, cotizaciones que reemplaza o vence y, opcional, el número de versión (`revision`, GC-427) |
| `cotizacion.rechazada` | `QuotationService#reject` (RF-032, RF-033) | Cliente | Cotización y precio rechazados |
| `servicio.contratado` | `QuotationService#accept`, al crear el contrato (RF-034, RF-035, ADR-046) | Cliente | Copia inmutable de lo contratado: precio, alcance, condiciones, fecha acordada y de contratación |
| `servicio.iniciado` | `ContractService#start`, al pasar de `CONTRACTED` a `IN_PROGRESS` (RF-045, GC-437) | Técnico | Contrato, solicitud, cliente y prestador, momento del inicio y estado `IN_PROGRESS` |
| `servicio.finalizado` | `ContractService#finish`, al pasar de `IN_PROGRESS` a `TECHNICIAN_FINISHED` (RF-047, GC-441) | Técnico | Contrato, solicitud, cliente y prestador, momento de la declaración y estado `TECHNICIAN_FINISHED`; sin `evidenceCount` hasta GC-439 |
| `servicio.cierre_objetado` | `ContractService#objectClosure`, al pasar de `TECHNICIAN_FINISHED` a `IN_PROGRESS` (GC-441) | Cliente | Contrato, solicitud, cliente y prestador, momento de la objeción y estado `IN_PROGRESS`; **sin el motivo** |
| `servicio.cerrado` | `ContractService#confirmClosure`, al pasar de `TECHNICIAN_FINISHED` a `CLOSED` (RF-048, GC-441) | Cliente | Contrato, solicitud, cliente y prestador, momento del cierre y estado `CLOSED` |

`servicio.iniciado`, `servicio.finalizado`, `servicio.cierre_objetado` y `servicio.cerrado` se escriben en la misma
transacción que el cambio de estado y su anotación en el historial que ven los usuarios; repetir una operación que ya se
hizo (iniciar un servicio en curso, finalizar uno finalizado, cerrar uno cerrado, repetir la misma objeción) no publica
otro. Un servicio objetado puede volver a finalizarse: la secuencia del servicio lo muestra como un nuevo
`servicio.finalizado` después del `servicio.cierre_objetado`. El motivo de la objeción no viaja en el evento porque puede
traer datos personales.

`servicio.contratado` implica que la cotización quedó aceptada: no hay un evento `cotizacion.aceptada` aparte,
porque ambas cosas ocurren en la misma transacción y el contrato ya trae la cotización.

### Eventos reservados (sin productor todavía)

| Evento | Productor previsto | Depende de |
| --- | --- | --- |
| `solicitud.vencida`, `cotizacion.vencida` | `ms-services` (proceso programado, DEC-S4-11) | Que exista el proceso que vence solicitudes y cotizaciones |
| `solicitud.cancelada` | `ms-services` | Que exista la cancelación en la API |
| `evaluacion.registrada` | `ms-users`, con su propio Outbox y tópico | GC-444 |

Un evento reservado se agrega a este contrato con su esquema antes de publicarse; mientras no tenga esquema, el
consumidor lo trata como tipo desconocido (sección 11).

## 6. Ejemplo

Primer hecho del flujo de ejemplo
([`01-solicitud.publicada.json`](examples/flujo-completo/01-solicitud.publicada.json)):

```json
{
  "eventId": "8c9d0e1f-2a3b-4c4d-9e5f-708192a3b4c1",
  "eventType": "solicitud.publicada",
  "schemaVersion": 1,
  "occurredAt": "2026-10-26T14:00:00Z",
  "producer": "ms-services",
  "aggregate": { "type": "ServiceRequest", "id": "6f1c2a3b-4d5e-4f60-8a71-92b3c4d5e6f7", "sequence": 1 },
  "correlationId": "6f1c2a3b-4d5e-4f60-8a71-92b3c4d5e6f7",
  "actor": { "userId": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "role": "CLIENT" },
  "data": {
    "serviceRequestId": "6f1c2a3b-4d5e-4f60-8a71-92b3c4d5e6f7",
    "clientId": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
    "category": "PLUMBING",
    "description": "Fuga de agua debajo del lavamanos del baño principal",
    "approximateLocation": { "city": "Bogotá", "zone": "Chapinero", "latitude": 4.6533, "longitude": -74.0836 },
    "expectedAt": "2026-10-28T15:00:00Z",
    "publishedAt": "2026-10-26T14:00:00Z",
    "status": "PUBLISHED",
    "photoCount": 2
  }
}
```

El flujo completo tiene trece hechos de la misma solicitud, en orden: publicación, dos postulaciones con precio,
selección, cotización, rechazo, segunda cotización, contratación, inicio del servicio, finalización por el técnico,
objeción del cliente al cierre, nueva finalización y cierre confirmado.

## 7. Privacidad

| Viaja | No viaja | Por qué |
| --- | --- | --- |
| Ids (`users.id`) de cliente y prestador | Nombres, correo, teléfono, documento | El evento identifica sin exponer datos personales; el perfil público se consulta en `ms-users` |
| Ubicación aproximada (ciudad, zona, coordenadas aproximadas) | Dirección exacta y sus coordenadas | RF-021 y CA-19: la dirección exacta solo la recibe el prestador contratado, por la API |
| Número de fotografías | Claves o archivos de las fotografías | Binarios y rutas de almacenamiento no son datos del hecho |
| Precio, alcance y condiciones | Tokens, contraseñas, claves internas | Son el acuerdo comercial (dato restringido, CD-06); los secretos nunca |

Como el evento lleva datos económicos restringidos, el tópico y el lakehouse son de acceso restringido: solo
`ms-services` escribe en el tópico, solo `ms-intake-lakehouse` lo lee, el bucket es privado y ningún componente
escribe el contenido del evento en los logs (solo `eventId`, `eventType` y la secuencia).

## 8. Orden y secuencia

- `aggregate.sequence` numera los hechos de cada solicitud: 1 para la publicación y luego 2, 3, 4… sin huecos ni
  repetidos.
- La asigna `ms-services` dentro de la misma transacción, como el siguiente número de esa solicitud en el Outbox.
  Es seguro porque todas las operaciones que publican ya bloquean la fila de la solicitud (ADR-043), y la
  publicación es la primera. La restricción única `(aggregate_id, aggregate_sequence)` del Outbox lo garantiza
  aunque algo falle.
- Con la clave del mensaje, Kafka entrega los eventos de una solicitud en orden. Si aun así llega uno fuera de
  orden o repetido, la secuencia permite reconstruir la historia: `(aggregate.id, aggregate.sequence)` identifica el
  hecho y `occurredAt` lo ubica en el tiempo.

## 9. Versionado

- **Cambio compatible, misma versión:** agregar un campo opcional a `data`. Los consumidores deben ignorar los campos
  que no conocen (lector tolerante) y el esquema se actualiza en el mismo PR. Ejemplo real próximo: la postulación
  empresarial (GC-468) agregará a `postulacion.registrada` los campos opcionales `providerType` (`INDEPENDENT` o
  `COMPANY`) y `technicianId`; sigue siendo la versión 1. Ya hecho así: `budget` en `solicitud.publicada` (GC-421) y
  `revision` en `cotizacion.enviada` (GC-427).
- **Cambio incompatible, versión nueva:** quitar o renombrar un campo, cambiar su tipo o su significado, o volver
  obligatorio uno opcional. Se crea `<tipo>.v2.schema.json`, `ms-services` publica la versión nueva y el consumidor
  debe aceptar la versión nueva y la anterior durante la transición. Ejemplo: si `proposedPrice` dejara de ser un
  solo valor y pasara a un rango, sería `postulacion.registrada` versión 2.
- **El historial no se reescribe.** Un evento v1 guardado sigue siendo v1 para siempre; quien lo lea usa el esquema de
  su `schemaVersion`.
- **Dos versiones de un mismo servicio.** La historia de una solicitud es la sucesión de sus eventos. En el ejemplo,
  la secuencia 1 (`solicitud.publicada`) guarda la solicitud en `PUBLISHED` y la secuencia 8 (`servicio.contratado`)
  la guarda contratada con Ana por 105000.00 COP, y la secuencia 9 (`servicio.iniciado`) la deja en curso y la
  secuencia 13 (`servicio.cerrado`) la deja cerrada. Todas quedan en el lakehouse: el estado posterior no borra ni
  modifica el hecho anterior.

## 10. Outbox en `ms-services` (para GC-433 y GC-434)

Tabla `outbox_events` en la base de `ms-services`, migración V7 (GC-433). El presupuesto de GC-421 usa la migración V8
(`service_requests.budget_amount` y `budget_currency`); `solicitud.publicada` lo lleva en el campo opcional `budget`.

| Columna | Tipo | Parte | Regla |
| --- | --- | --- | --- |
| `event_id` | UUID PK | Hecho (inmutable) | Igual al `eventId` del sobre |
| `event_type` | VARCHAR(60) | Hecho | |
| `schema_version` | SMALLINT | Hecho | |
| `aggregate_type` | VARCHAR(40) | Hecho | `ServiceRequest` |
| `aggregate_id` | UUID | Hecho | Id de la solicitud |
| `aggregate_sequence` | BIGINT | Hecho | Único junto con `aggregate_id` |
| `topic` | VARCHAR(120) | Hecho | `fixia.services.service-requests` |
| `message_key` | VARCHAR(60) | Hecho | El id de la solicitud |
| `payload` | VARCHAR(16000) | Hecho | El sobre completo en JSON, tal como se publicará. El más grande posible (descripción, alcance y condiciones de 2000 caracteres cada uno) cabe con holgura |
| `occurred_at` | TIMESTAMP WITH TIME ZONE | Hecho | |
| `status` | VARCHAR(20) | Envío (lo actualiza el relay) | `PENDING`, `PUBLISHED` o `FAILED` |
| `attempts` | INTEGER | Envío | Intentos de publicación |
| `next_attempt_at` | TIMESTAMP WITH TIME ZONE | Envío | Cuándo reintentar |
| `last_error` | VARCHAR(500) | Envío | Último error, sin el contenido del evento |
| `published_at` | TIMESTAMP WITH TIME ZONE | Envío | Cuándo Kafka confirmó |

Índices: único `(aggregate_id, aggregate_sequence)` y `(status, next_attempt_at)` para que el relay encuentre lo
pendiente.

**Reglas del Outbox (GC-433):**

1. El evento se escribe en la misma transacción que el cambio de negocio, con el mismo instante (`occurredAt`). Si la
   transacción se revierte, el evento tampoco existe. El reloj del servicio tiene precisión de microsegundos, la misma
   de la base, para que el instante del evento sea idéntico al que queda guardado.
2. El sobre se arma con los datos ya confirmados por el dominio y se valida contra su esquema en las pruebas.
3. Las columnas del hecho no se modifican nunca; el relay solo toca las de envío.

**Reglas del relay (GC-434):**

1. Lee `PENDING` con `next_attempt_at` vencido, en orden de `occurred_at` y `aggregate_sequence`, tomando las filas
   con `FOR UPDATE SKIP LOCKED` para que dos instancias no publiquen la misma.
2. No publica la secuencia *n + 1* de una solicitud mientras la *n* no esté `PUBLISHED`: así el orden se conserva
   también cuando hay reintentos.
3. Publica con la clave y las cabeceras de la sección 3, con el productor idempotente de Kafka y `acks=all`.
4. Marca `PUBLISHED` **solo después** del acuse del broker. Si cae entre el acuse y la marca, el evento se vuelve a
   publicar con el mismo `eventId`: es una reentrega permitida.
5. Si falla, suma un intento y reprograma con espera exponencial (2, 4, 8… segundos, máximo 5 minutos). Tras 10
   intentos pasa a `FAILED`: queda visible en las métricas y se reactiva volviéndolo a `PENDING`.
6. Las filas `PUBLISHED` se conservan al menos 7 días: son la fuente para reprocesar cuando Kafka ya no tiene el
   mensaje (su retención en QA es de 24 h).
7. Métricas mínimas: pendientes, edad del pendiente más antiguo, publicados, reintentos y fallidos.

## 11. Capa de Ingesta del lakehouse, solo lectura (para GC-435)

1. **Consumo:** grupo `ms-intake-lakehouse`, suscrito a `fixia.services.service-requests`, con confirmación manual
   del offset (al menos una vez).
2. **Validación:** JSON válido; el sobre cumple `envelope.v1.schema.json`; `eventType` y `schemaVersion` tienen
   esquema y `data` lo cumple; la clave del mensaje es `aggregate.id` y las cabeceras coinciden con el cuerpo.
3. **Conservación del evento válido** en RustFS, en una clave que depende solo del evento:

   ```text
   events/producer=ms-services/type=<eventType>/v=<schemaVersion>/date=<occurredAt AAAA-MM-DD>/<eventId>.json
   ```

   El objeto es el sobre tal como llegó. Los datos de la ingesta (tópico, partición, offset y hora de recepción) van
   como metadatos del objeto, no dentro del contenido.
4. **Idempotencia:** la primera copia gana. Si el objeto de ese `eventId` ya existe, no se sobrescribe y el mensaje
   se da por procesado. Repetir, reprocesar o reiniciar no crea registros nuevos.
5. **Mensaje inválido o de tipo o versión desconocida:** no se descarta en silencio. Se guarda tal como llegó en
   `quarantine/date=<fecha de recepción>/<tópico>-<partición>-<offset>.json`, con el motivo en los metadatos, y luego
   se confirma el offset. Se puede reprocesar cuando el contrato lo admita.
6. **Offset:** se confirma solo después de que RustFS confirmó la escritura, en `events/` o en `quarantine/`. Si
   RustFS no responde, no se confirma y se reintenta con espera; la partición espera, no salta mensajes.
7. **Tardíos y repetidos:** se guardan igual. La historia es de solo agregar y se ordena por
   `(aggregate.id, aggregate.sequence)`.
8. **Reproceso:** reiniciar el grupo desde el offset más antiguo que Kafka conserve, o volver a `PENDING` filas del
   Outbox para republicarlas. Las dos vías son seguras por la regla 4.
9. **Seguridad:** el bucket es privado; solo el lakehouse escribe; los logs registran `eventId`, tipo, secuencia y
   offset, nunca el contenido.

## 12. Recuperación ante fallos

| Situación | Qué pasa | Cómo se recupera |
| --- | --- | --- |
| La transacción de negocio falla | No hay evento | No hay nada que recuperar |
| Kafka no está disponible | Los eventos quedan `PENDING` en el Outbox | El relay reintenta; al volver Kafka, se publican en orden |
| El relay se cae después del acuse y antes de marcar | El evento se publica dos veces con el mismo `eventId` | El lakehouse guarda una sola copia |
| Un evento agota sus reintentos | Queda `FAILED`, visible en las métricas | Se corrige la causa y se vuelve a `PENDING` |
| El lakehouse está detenido | Los mensajes esperan en Kafka | Al arrancar continúa desde su último offset confirmado |
| El lakehouse estuvo detenido más de 24 h | Kafka ya no tiene esos mensajes | Se republican desde el Outbox (filas conservadas 7 días) |
| RustFS no responde | El consumidor no confirma el offset | Reintenta con espera; no se pierde ni se salta nada |
| Llega un mensaje inválido | Va a `quarantine/` con el motivo | Se revisa y se reprocesa cuando el contrato lo admita |

## 13. Pruebas que debe tener cada parte

- **GC-433:** el evento existe tras el commit y no existe tras un rollback; un evento por operación real; un reintento
  sin cambio no crea otro; secuencias consecutivas con operaciones simultáneas sobre la misma solicitud; cada sobre
  producido cumple su esquema de `docs/events/schemas`.
- **GC-434:** publica en orden por solicitud; con Kafka caído no pierde ni salta eventos; una reentrega conserva el
  `eventId`; pasa a `FAILED` tras los reintentos; dos instancias no publican la misma fila.
- **GC-435:** cada ejemplo de `examples/flujo-completo` se acepta y se guarda una vez; el mismo mensaje dos veces deja
  un solo objeto; un mensaje inválido va a cuarentena y confirma el offset; con RustFS caído no se confirma el offset;
  tras reiniciar no hay duplicados.

## 14. Documentos que cambia esta decisión

- **ADR-024:** pasa de eventos mínimos a eventos completos con payload autosuficiente (GC-416 y GC-453).
- **ADR-042:** proponía no publicar eventos en el MVP; GC-407 y este contrato lo dejan sin efecto (GC-416 y GC-453).
- **Arquitectura de Datos V2:** §1.6.1 (sin pull a la API del dueño), §1.6.2 (RustFS en lugar de MongoDB) y §1.7
  (`OUTBOX_INGESTA` no se implementa en este sprint) (GC-455).
- **DD y SDD:** catálogo de eventos, tópico y Outbox (GC-454 y GC-455).
