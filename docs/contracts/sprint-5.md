# Contratos iniciales del Sprint 5 (GC-454)

Versión 0.1 · 2026-10-26 · historia GC-417. Contratos de datos, APIs y eventos para que backend, frontend, QA y
DevSecOps trabajen en paralelo sobre el SRS V6. Son **iniciales**: cada tarea los implementa y, si algo cambia, lo
actualiza aquí en su mismo PR. La consolidación final contra el código integrado es GC-455 (DD y SDD).

Cada punto lleva su estado:

- ✅ **Implementado**: ya está en `develop` y desplegado en QA.
- 🟡 **Propuesto**: contrato a seguir; lo confirma o ajusta la tarea que lo implementa.
- ❓ **Por decidir**: falta una decisión; se indica quién la toma. Mientras tanto se usa la propuesta.

Fuentes: SRS V6 (§6.10, §9.5, §10.7, §11.4; RF-017 a RF-056, RF-082 a RF-084, RN-001 a RN-018, RD-08 a RD-11,
CA-20 a CA-34), DD V4, SDD V3, ADR Sprint 4, contrato de eventos [`docs/events`](../events/README.md) (GC-436) y las
tareas del Sprint 5 en Jira.

---

## 1. Convenciones comunes (✅ ya vigentes)

| Tema | Regla |
| --- | --- |
| Identidad | El actor sale siempre del token: `sub` = `usr_<users.id>` y `roles` = `ROLE_CLIENT`, `ROLE_PROFESSIONAL`, `ROLE_ADMIN` (y `ROLE_COMPANY`, sección 8). Nunca se confía en un id de usuario del cuerpo |
| Referencias entre servicios | Siempre `users.id` de ms-users (UUID), sin clave foránea entre bases (Database per Service) |
| Rutas por rol en ms-services | `/api/services/requests/**` cliente; `/applications/**`, `/opportunities/**`, `/quotations/**`, `/contracts/**` técnico. Las rutas internas van bajo `/internal/**` con `X-Internal-Api-Key` y no pasan por el gateway |
| Dinero en la API | Dos campos planos: el importe (`proposedAmount`, `amount`, `budgetAmount`…) como número con hasta 2 decimales, mayor que 0 y máximo 9999999999.99, y su moneda (`currency`, solo `COP`) |
| Dinero en eventos | Texto decimal con 2 decimales (`"150000.00"`), ver GC-436 |
| Fechas | ISO-8601 en UTC con `Z`. El reloj del servicio tiene precisión de microsegundos, igual que la base |
| Errores | ProblemDetail (RFC 7807) con `title` y `detail` en español. 400 validación (`errors[]` con `field` y `message`), 401 sin sesión, 403 rol incorrecto, 404 no existe **o no es del actor** (misma respuesta), 409 estado o duplicado, 422 regla de negocio no cumplida (con `reasons` cuando aplica), 503 dependencia caída (falla cerrado) |
| Datos antiguos | Un campo nuevo es `null` en los registros anteriores a él; nunca se inventan valores (RD-08) |
| Idempotencia | Repetir una operación que ya se hizo responde el estado actual sin crear nada nuevo ni otro evento; una segunda petición *distinta* sobre lo mismo es 409 |
| Eventos | Cada cambio real de estado escribe su evento en el Outbox de su servicio, en la misma transacción (GC-433) |

## 2. Qué cambia frente al DD V4

| Dato o pieza | Servicio | Estado | Tarea |
| --- | --- | --- | --- |
| Precio propuesto en `applications` (`proposed_amount`, `currency`) | ms-services | ✅ V6 | GC-423 |
| Perfil público del técnico (ruta interna) y `provider` en postulantes | ms-users / ms-services | ✅ | GC-425 |
| Outbox `outbox_events` y 6 eventos | ms-services | ✅ V7 | GC-433, GC-436 |
| Presupuesto orientativo en `service_requests` | ms-services | ✅ V8 | GC-421 |
| Tipo de prestador y técnico propuesto en `applications` | ms-services | 🟡 | GC-468 |
| Revisión visible de la cotización | ms-services | 🟡 | GC-427 |
| Estado `IN_PROGRESS`, inicio y tabla de transiciones | ms-services | ✅ V9 | GC-437 |
| Finalización, cierre y objeción (`TECHNICIAN_FINISHED`, `CLOSED`) | ms-services | ✅ V10 | GC-441 |
| Evidencias (metadatos en base, archivo en RustFS) | ms-services | 🟡 | GC-439 |
| Identidad de empresa y asociación empresa–técnico | ms-users | 🟡 | GC-466 |
| Evaluaciones bidireccionales y su Outbox | ms-users | 🟡 | GC-443, GC-444 |
| Reputación calculada de las evaluaciones | ms-users | 🟡 | GC-446 |
| Historial propio | ms-services | 🟡 | GC-448 |

## 3. Los cuatro valores económicos (RN-004, RD-08)

Son cuatro datos distintos, en lugares distintos, y ninguno reemplaza a otro:

| Concepto | Dónde vive | Quién lo fija | Estado |
| --- | --- | --- | --- |
| **Presupuesto orientativo** | `service_requests.budget_amount` + `budget_currency` | Cliente al publicar | ✅ GC-421 |
| **Precio propuesto** | `applications.proposed_amount` + `currency` | Prestador al postularse | ✅ GC-423 |
| **Valor de la cotización** | `quotations.amount` + `currency` (una fila por versión) | Prestador seleccionado | ✅ GC-354 |
| **Precio contratado** | `service_contracts.amount` + `currency` (copia de la cotización aceptada) | Nace al aceptar | ✅ GC-359 |

### 3.1 Presupuesto orientativo (✅ GC-421 backend / 🟡 GC-422 Flutter)

- `POST /api/services/requests` agrega `"budgetAmount": 200000` y `"budgetCurrency": "COP"`. **Obligatorios en
  solicitudes nuevas** (RF-020 dice "indicando un presupuesto"), con las mismas reglas de importe que el precio.
- Respuesta de la solicitud del cliente y de las oportunidades del técnico: los mismos dos campos; `null` en
  solicitudes anteriores.
- Migración V8: `budget_amount NUMERIC(12,2)` y `budget_currency VARCHAR(3)`, ambos nulos o ambos presentes, > 0 y
  `COP` (mismo patrón que `ck_applications_proposed_price`, con los `IS NOT NULL`).
- Evento: `solicitud.publicada` gana `budget` (`{amount, currency}` como el resto del dinero en eventos) como campo
  **opcional**; sigue siendo versión 1.
- ✅ **Valor único, no rango** (SRS §11.4 lo deja al desarrollo): decidido por GC-421 (Tomás R.) y GC-422 (Andrés),
  igual que el precio propuesto que ya implementó Andrés en Flutter. Es obligatorio, mayor que cero, en COP y solo
  orientativo: no es un pago, un límite ni el precio de la postulación, la cotización o el contrato.
- Implementado: la respuesta devuelve el importe con dos decimales (`200000.00`); la entrada acepta `200000` o
  `200000.5`. Una moneda distinta de `COP` o un cuerpo sin importe o sin moneda es 400. Ver el ejemplo en el README.

### 3.2 Cotización versionada (✅ base / 🟡 ajuste GC-427)

- ✅ Ya funciona: cada envío crea una cotización nueva; la `SENT` anterior pasa a `SUPERSEDED` (o `EXPIRED`) en la
  misma transacción, y solo una `SENT` vigente se puede aceptar. Eso es la versión (RF-031, RF-033).
- 🟡 Se propone exponer `revision` (1, 2, 3… por solicitud) en la respuesta de la cotización y en `cotizacion.enviada`,
  para que el frontend muestre "versión 2" sin calcularla. Columna `revision INTEGER` asignada con la solicitud
  bloqueada; `null` no aplica porque se puede calcular para las existentes en la migración.
- ❓ Contraoferta estructurada del cliente: **fuera** salvo decisión de GC-427 con el PO. El chat prototipo no
  confirma precios (RN-004, CA-25).

## 4. Postulación y selección (✅ independiente / 🟡 empresa)

Ver la sección 8 para la empresa. Para el técnico independiente ya está implementado:

- `POST /api/services/applications` `{serviceRequestId, proposedAmount, currency}` (201). Repostularse con otro precio:
  409 "Postulación duplicada".
- `GET /api/services/requests/{id}/applications` (cliente dueño): por postulante `id`, `providerId`, `status`,
  `proposedAmount`, `currency`, `provider` (perfil público de GC-425) y fechas.
- `POST /api/services/requests/{id}/applications/{applicationId}/selection`: elige uno; las demás pasan a `REJECTED`.
  Seleccionar **no** crea cotización ni contrato (RF-028).

## 5. Ciclo del servicio (✅ inicio GC-437 / ✅ finalización y cierre GC-441)

### 5.1 Máquina de estados de `service_requests.status`

```text
PUBLISHED ──selección──▶ PROVIDER_SELECTED ──aceptar cotización──▶ CONTRACTED
    │                           │                                      │
    ├─(vence) EXPIRED           └─(cancelación, futura) CANCELLED       │ técnico inicia
                                                                       ▼
                                                                  IN_PROGRESS
                                                                       │ técnico declara fin
                                                                       ▼
                                                             TECHNICIAN_FINISHED
                                                                │            │
                                          cliente confirma ◀────┘            └────▶ cliente objeta (vuelve)
                                                │                                       │
                                                ▼                                       ▼
                                              CLOSED                              IN_PROGRESS
```

| Transición | Actor | Ruta (🟡) | Evento (🟡) | Reglas |
| --- | --- | --- | --- | --- |
| CONTRACTED → IN_PROGRESS ✅ | Técnico contratado | `POST /api/services/contracts/{contractId}/start` | `servicio.iniciado` ✅ | Solo el `provider_id` del contrato. Repetir sobre `IN_PROGRESS` responde 200 sin cambio ni evento; desde otro estado, 409 |
| IN_PROGRESS → TECHNICIAN_FINISHED ✅ | Técnico contratado | `POST /api/services/contracts/{contractId}/finish` | `servicio.finalizado` ✅ | No cierra el servicio (RF-047). Evidencias opcionales (no se piden). Repetir sobre `TECHNICIAN_FINISHED` responde 200 sin cambio ni evento; desde otro estado, 409 |
| TECHNICIAN_FINISHED → CLOSED ✅ | Cliente dueño | `POST /api/services/requests/{id}/closure` | `servicio.cerrado` ✅ | Registra quién y cuándo (RF-048). Confirmar un servicio ya `CLOSED` responde 200 sin duplicar; un servicio `CLOSED` no se reabre: objetar, finalizar o iniciar dan 409. Desde otro estado, 409 |
| TECHNICIAN_FINISHED → IN_PROGRESS ✅ | Cliente dueño | `POST /api/services/requests/{id}/closure-objection` con `{reason}` | `servicio.cierre_objetado` ✅ | `reason` obligatorio, máximo 500. Repetir la misma objeción sobre el servicio ya devuelto a `IN_PROGRESS` responde 200 sin duplicar; otro motivo, o desde otro estado, 409 |

- Las rutas reutilizan los prefijos que cada rol ya tiene: el técnico trabaja sobre su contrato (`/contracts`, como en
  GC-359) y el cliente sobre su solicitud (`/requests`). No hace falta tocar la seguridad por rol.
- Todas toman la solicitud con bloqueo pesimista (ADR-043), validan el estado de origen y escriben transición y evento en
  la misma transacción.
- Sin cierre automático por silencio (GC-441).
- ✅ **Objeción del cliente** (decidido por GC-441, Tomás R., y confirmado por el PO, Andrés): la inconformidad no se
  convierte en cierre. El servicio vuelve a `IN_PROGRESS` con el motivo registrado en el historial, que ven el cliente y el
  técnico; el evento `servicio.cierre_objetado` no lleva el texto. Las disputas formales (RF-049, RF-050) siguen fuera del
  sprint. Después de una objeción el técnico puede declarar finalizado otra vez y el cliente cerrar.
- Las tres rutas devuelven la misma respuesta que `GET /requests/{id}/contract` (contratación con `serviceStatus` y
  `timeline`), igual que `start`.

### 5.2 Historial de transiciones (✅ GC-437, migración V9)

Tabla `service_request_transitions` en ms-services:

| Columna | Tipo | Regla |
| --- | --- | --- |
| `id` | UUID PK | |
| `service_request_id` | UUID FK → `service_requests` | |
| `from_status`, `to_status` | VARCHAR(30) | Estados de la sección 5.1. `from_status` es nulo en la publicación (no tiene origen). Sin CHECK sobre los valores: los guarda el enum del dominio |
| `actor_id` | UUID | `users.id`; nulo si fue el sistema |
| `actor_role` | VARCHAR(20) | `CLIENT`, `PROFESSIONAL` o `SYSTEM` |
| `reason` | VARCHAR(500) | Solo en la objeción |
| `occurred_at` | TIMESTAMP WITH TIME ZONE | |

Se registran también las transiciones ya existentes (publicación, selección, contratación) desde que existe la tabla;
las anteriores a ella no se reconstruyen (✅ implementado: un servicio antiguo trae `timeline` vacío o parcial). El
Outbox no reemplaza este historial: el Outbox es técnico y este es el que ven los usuarios. La anotación se hace con el
mismo instante y en la misma transacción que el cambio de estado y el evento.

### 5.3 Consulta del estado (✅ GC-437)

- Técnico: `GET /api/services/contracts/{contractId}` agrega `serviceStatus` y `timeline` (lista de transiciones, del más
  antiguo al más reciente, con `fromStatus`, `toStatus`, `actorId`, `actorRole`, `reason` y `occurredAt`).
- Cliente: `GET /api/services/requests/{id}/contract` agrega lo mismo, igual que el técnico.
- `POST /contracts/{contractId}/start` devuelve la misma respuesta. El listado `GET /contracts` trae `serviceStatus` pero no
  `timeline`.
- `serviceRequest.exactLocation` sigue llegando solo al prestador contratado (ADR-009) y al cliente dueño, también con el
  servicio en curso (`ServiceRequestStatus#exposesExactLocation()`; GC-441 agrega ahí las etapas siguientes).

## 6. Evidencias (🟡 GC-439, GC-440; almacenamiento GC-456)

- **Dónde:** archivo en un bucket **privado** de RustFS (`evidences`, lo crea GC-456); metadatos en la tabla
  `service_evidences` de ms-services. Nunca binarios en la base ni URLs públicas permanentes (RD-11).
- **Tabla:** `id`, `service_request_id` (FK), `uploaded_by`, `object_key`, `content_type`, `size_bytes`, `sha256`,
  `status` (`PENDING_UPLOAD` o `AVAILABLE`), `created_at`, `confirmed_at`.
- **Subida:** `POST /api/services/contracts/{contractId}/evidences` (multipart, campo `file`, opcional `description`).
  ms-services valida tipo y tamaño, calcula el SHA-256, escribe el objeto y solo entonces confirma la fila
  `AVAILABLE` y el evento `evidencia.registrada`. Si falla el almacenamiento, no queda una evidencia válida (CA-28).
- **Consulta:** lista de metadatos en `GET /api/services/contracts/{contractId}/evidences` (técnico) y
  `GET /api/services/requests/{id}/evidences` (cliente); contenido en `.../evidences/{evidenceId}/content`, que
  ms-services transmite tras validar el permiso.
- **Permisos:** sube el técnico contratado en `IN_PROGRESS` o `TECHNICIAN_FINISHED`; consultan los dos participantes.
  Un tercero recibe 404.
- ❓ **Límites** (propuesta): JPEG, PNG, WebP y PDF; máximo 10 MB por archivo y 10 por servicio. Confirman GC-439,
  GC-440 (Andrés) y GC-456 (Juan Pablo).

## 7. Evaluaciones y reputación (🟡 GC-443, GC-444, GC-446)

`ms-users` es la fuente de verdad de las evaluaciones (decisión del equipo, SRS V6 §6.10). GC-443 (spike de Juan
Pablo) confirma o ajusta lo siguiente.

### 7.1 Evaluación (ms-users)

- **Tabla `service_evaluations`:** `id`, `service_request_id`, `evaluator_id`, `evaluated_id`, `direction`
  (`CLIENT_TO_PROVIDER` o `PROVIDER_TO_CLIENT`), `rating` (SMALLINT 1–5), `comment` (VARCHAR(500), opcional),
  `created_at`. Única por `(service_request_id, direction)` (RN-012).
- **Crear:** `POST /api/users/evaluations` `{serviceRequestId, rating, comment}`. La dirección y el evaluado se deducen
  del token y de la participación; el cuerpo no los trae.
- **Verificar el cierre sin leer la base ajena:** ms-users consulta a ms-services
  `GET /internal/services/{serviceRequestId}/participation` (nueva, con `X-Internal-Api-Key`) → `{status, clientId,
  providerId, closedAt}`. ms-services necesita su propio filtro de clave interna (el patrón de ms-users).
- **Respuestas:** 201 creada; repetir la misma evaluación responde la existente; una segunda distinta, 409; servicio no
  `CLOSED` o actor que no participó, 422; ms-services caído, 503.
- **Consultar:** `GET /api/users/evaluations?serviceRequestId=…` solo para los participantes.
- **Evento:** `evaluacion.registrada` desde el Outbox **propio de ms-users**, en el tópico `fixia.users.evaluations`,
  clave y `correlationId` = `serviceRequestId`, mismo sobre que GC-436 con `producer: "ms-users"`.
- ❓ Corregir o borrar una evaluación: fuera del sprint salvo decisión de GC-443.
- Empresas: el MVP empresarial termina en la selección (ALC-08), así que no hay evaluación de empresas.

### 7.2 Reputación (🟡 GC-446, la muestra GC-447)

- Se **calcula al consultar** con las evaluaciones `CLIENT_TO_PROVIDER` del técnico (promedio y conteo). Sin tabla de
  proyección ni consumidor: no hay nada que duplicar y siempre coincide con la fuente (CA-30).
- Llega al frontend dentro del perfil público que ya existe: `provider.reputation = {"average": 4.5, "count": 12}`
  en los postulantes (GC-425), y `null` si el técnico no tiene evaluaciones. Promedio redondeado a un decimal.
- Si la carga crece, se pasa a una proyección alimentada por `evaluacion.registrada`, idempotente por `eventId`.

## 8. Empresa (🟡 GC-466 en ms-users, GC-468 en ms-services)

Alcance del sprint (SRS V6 ALC-08): la empresa se identifica, consulta sus técnicos, se postula con un técnico propuesto
y el cliente la puede seleccionar. **Cotización, contratación, ejecución y evaluación empresariales no entran.**

### 8.1 Identidad y asociación (ms-users, GC-466)

- Rol nuevo `COMPANY` en el enum `Role`; el token lleva `ROLE_COMPANY`. La cuenta de la empresa es la del
  administrador: su `users.id` es el id de la empresa en todo el sistema.
- `company_profiles`: `user_id` (PK → users), `business_name`, `tax_id` (NIT), `created_at`.
- `company_technicians`: `company_user_id`, `technician_user_id`, `status` (`ACTIVE`), `created_at`; único por par.
  Las asociaciones de QA se siembran con datos controlados; no hay endpoint para vincular por id (RF-017).
- `GET /api/users/companies/me/technicians` (`ROLE_COMPANY`): técnicos asociados activos con el mismo perfil público
  de GC-425. Lista vacía si no hay.
- Interna para ms-services: `GET /internal/companies/{companyUserId}/technicians/{technicianUserId}` →
  `{associated, active}`.
- ❓ ¿Un técnico puede estar asociado a varias empresas, o postularse por su cuenta además de por la empresa? Se propone
  **una empresa por técnico** y permitirle seguir postulándose como independiente. Decide el PO con GC-466.

### 8.2 Postulación y selección empresarial (ms-services, GC-468)

- `applications` agrega `provider_type` (`INDEPENDENT` por defecto en las existentes, o `COMPANY`) y
  `technician_id` (obligatorio si es `COMPANY`, nulo si es independiente). La unicidad
  `(service_request_id, provider_id)` ya garantiza una postulación por empresa y solicitud.
- `POST /api/services/applications` acepta también `ROLE_COMPANY` con `{serviceRequestId, technicianId,
  proposedAmount, currency}`. ms-services valida con ms-users que el técnico esté asociado y activo, y su elegibilidad
  con la ruta que ya existe (la del técnico propuesto). Respuestas: 422 si no está asociado o no es elegible; 409 si
  la empresa ya se postuló (aunque cambie técnico o precio); 503 si ms-users no responde.
- La postulación y los postulantes devuelven `providerType` y `technicianId`; en los postulantes, `provider` es el perfil
  de la empresa (`displayName` = razón social) y `executor` el perfil público del técnico propuesto. Para eso la ruta
  interna de perfiles públicos de ms-users (GC-425) acepta también ids de empresa y responde su razón social.
- La selección usa la **misma ruta** que un independiente. `selected_provider_id` guarda la empresa; el técnico
  ejecutor queda en la postulación `SELECTED` (no se duplica en `service_requests`).
- Después de seleccionar una empresa, cotizar no está disponible (GC-468 paso 9): las rutas de cotización siguen siendo
  solo de `ROLE_PROFESSIONAL`, así que la empresa recibe 403, y el técnico propuesto recibe el 409 que ya existe ("No
  tienes una selección vigente"), porque el prestador seleccionado es la empresa y no él.
- Eventos: `postulacion.registrada` y `prestador.seleccionado` ganan los opcionales `providerType` y `technicianId` /
  `selectedTechnicianId` (siguen en versión 1).

## 9. Historial propio (🟡 GC-448, GC-449)

- `GET /api/services/history?cursor=…&limit=20` para cliente y técnico: los servicios donde participó, del más reciente
  al más antiguo, con paginación por cursor (como ADR-044).
- Por elemento: `serviceRequestId`, `role` (cómo participó), `counterpartId`, `category`, `status`, `budgetAmount`,
  `contractedAmount` (si hay contrato), `currency`, fechas clave y `lastTransitionAt`.
- Las evaluaciones no se mezclan en ms-services: el frontend pide a ms-users
  `GET /api/users/evaluations/mine?serviceRequestIds=…`. Así ninguna base lee a la otra y el historial no depende de que
  ms-users esté arriba.
- Nunca se expone el lakehouse ni Kafka como consulta de usuario.

## 10. Eventos nuevos (🟡, se agregan al contrato de GC-436 con su esquema al implementarse)

| Evento | Productor | Datos principales |
| --- | --- | --- |
| `servicio.iniciado` ✅ | ms-services (GC-437) | `contractId`, `serviceRequestId`, `clientId`, `providerId`, `startedAt`, `status` (`IN_PROGRESS`); esquema y ejemplo en `docs/events` |
| `servicio.finalizado` ✅ | ms-services (GC-441) | `contractId`, `serviceRequestId`, `clientId`, `providerId`, `finishedAt`, `status` (`TECHNICIAN_FINISHED`). Sin `evidenceCount` por ahora: GC-439 lo agrega como campo opcional sin cambiar de versión |
| `servicio.cierre_objetado` ✅ | ms-services (GC-441) | `contractId`, `serviceRequestId`, `clientId`, `providerId`, `objectedAt`, `status` (`IN_PROGRESS`); sin el texto del motivo |
| `servicio.cerrado` ✅ | ms-services (GC-441) | `contractId`, `serviceRequestId`, `clientId`, `providerId`, `closedAt`, `status` (`CLOSED`) |
| `evidencia.registrada` | ms-services (GC-439) | `evidenceId`, `serviceRequestId`, `uploadedBy`, `contentType`, `sizeBytes`, `sha256` (sin el archivo ni la clave del objeto) |
| `evaluacion.registrada` | ms-users (GC-444) | `evaluationId`, `serviceRequestId`, `direction`, `evaluatorId`, `evaluatedId`, `rating` (sin el comentario) |

Todos usan el sobre, el tópico y las reglas de idempotencia de GC-436. Los de ms-services van en
`fixia.services.service-requests` con la misma secuencia por solicitud; el de ms-users va en su propio tópico.

## 11. Migraciones

| Repositorio | Siguiente | Para |
| --- | --- | --- |
| ms-services | V11 | V8 es el presupuesto (GC-421), V9 el inicio y el historial (GC-437) y V10 la finalización y el cierre (GC-441), ya tomadas |
| ms-services | luego | Tipo de prestador (GC-468), evidencias (GC-439), revisión de la cotización (GC-427) |
| ms-users | V9 en adelante | V8 es la empresa (GC-466), ya tomada; evaluaciones y su Outbox (GC-444) |

**Regla:** el número lo toma quien **fusiona primero**. Antes de fusionar un PR con migración, se actualiza la rama
con `develop` y se renumera si hace falta. Si una migración llega con un número menor que otra ya aplicada en QA,
Flyway no arranca.

## 12. Decisiones pendientes

| Tema | Propuesta | Quién decide |
| --- | --- | --- |
| ~~Presupuesto: valor o rango~~ | **Decidido:** valor único obligatorio en COP (GC-421, GC-422) | — |
| Número de versión visible de la cotización | Sí, `revision` | GC-427 (Tomás R.) |
| Contraoferta del cliente | Fuera del sprint | GC-427 con el PO |
| ~~Objeción al cierre~~ | **Decidido:** vuelve a `IN_PROGRESS` con motivo (GC-441, confirmado por el PO) | — |
| Límites de evidencias | JPEG, PNG, WebP, PDF; 10 MB; 10 por servicio | GC-439, GC-440, GC-456 |
| Corregir evaluaciones | No en el sprint | GC-443 (Juan Pablo) |
| Técnico en varias empresas o también independiente | Una empresa; puede seguir como independiente | PO con GC-466 |
| Reputación: calcular al leer o proyección | Calcular al leer | GC-446 (Tomás O.) |

Se toman en la primera semana del sprint; quien decide actualiza este documento.
