# Spike GC-547: desacoplar ms-users y ms-services con eventos

Historia GC-416 («Actualizar arquitectura y decisiones del incremento»). La tarea pide, para cada llamada síncrona
concreta: el dato que pide, quién es su fuente de verdad, quién lo consume, cómo queda la seguridad y si conviene pasarla
a eventos, contrastando el costo de la consistencia eventual. Debe entregar una decisión documentada, con evidencia en el
código, riesgos, un contrato de evento sugerido y el criterio para crear la implementación. La propia tarea advierte:
**no se trata de suprimir todas las llamadas.**

Revisado sobre `develop` de `ms-services` (`f89119f`), `ms-users` (`4eed0d4`), `ms-payments` y `ms-matching-geo` al
2026-11-07.

## 1. Resumen

- Entre `ms-users` y `ms-services` hay **siete rutas internas en uso**: seis de `ms-services` hacia `ms-users` y una al
  revés. Esa última cierra un **ciclo**: cada uno necesita al otro.
- **Hallazgo principal:** `ms-services` llama a `ms-users` **en cada petición autenticada**, para saber si el token fue
  revocado, y si no obtiene respuesta rechaza la petición (401). Hoy, si `ms-users` cae, **`ms-services` deja de
  funcionar por completo**, y lo mismo pasa con `ms-payments` y `ms-matching-geo`, que hacen la misma consulta. Pasar a
  eventos cualquier otra llamada **no mejora la disponibilidad** mientras esta siga igual.
- **Decisión:**
  1. **Fase 1 (crear tarea ya):** la participación que pide `ms-users` para evaluar se reemplaza por una proyección
     alimentada con `servicio.cerrado`. Rompe el ciclo con poco costo y sin riesgo de seguridad.
  2. **Fase 2 (crear tarea si el PO acepta el riesgo de §5.1):** revocación de tokens con una lista local alimentada por
     eventos, con respaldo síncrono si la lista se atrasa. Es lo único que desacopla la disponibilidad.
  3. **Fase 3 (cuando existan GC-523 y GC-524):** la pertenencia de técnicos a empresas, como proyección en
     `ms-services` con los eventos que ya fija el contrato de empresas (§6.3).
  4. **Se mantienen síncronas:** la elegibilidad, los perfiles públicos y la comprobación de trabajo pendiente al
     desvincularse. El motivo de cada una está en §3.

## 2. Inventario (evidencia en el código)

| # | Quién llama | Ruta interna | Para qué | Dónde se llama | Cuántas veces |
| --- | --- | --- | --- | --- | --- |
| L1 | ms-services → ms-users | `GET /internal/tokens/{jti}/status` | ¿Está revocado el token? | `config/TokenRevocationValidator.java:51` | **En cada petición autenticada** |
| L2 | ms-services → ms-users | `POST /internal/technicians/{id}/eligibility` | Verificación, categoría, zona y horario del técnico frente a la solicitud | `ApplicationService.java:108` (postular); `OpportunityService.java:83` y `:109` (oportunidades, lotes de 100) | 1 por postulación. Las oportunidades de una empresa hacen **hasta técnicos × 5** por página (`MAX_SCAN` 500) |
| L3 | ms-services → ms-users | `POST /internal/technicians/public-profiles` | Nombre visible, verificación, categorías, experiencia, ciudad y reputación | `ApplicantService.java:46` | 1 por consulta de postulantes |
| L4 | ms-services → ms-users | `POST /internal/companies/public-profiles` | Nombre comercial y reputación de la empresa | `ApplicantService.java:53` | 1 por consulta de postulantes con empresas |
| L5 | ms-services → ms-users | `POST /internal/companies/technician-association` | ¿El técnico pertenece a la empresa? | `ApplicationService.java:104`, `ServiceRequestService.java:153`, `CompanyDesignation.java:64` y `:75` | 1 al postular, al seleccionar, al cotizar y al aceptar (GC-551) |
| L6 | ms-services → ms-users | `POST /internal/companies/technicians` | Técnicos activos de la empresa | `OpportunityService.java:102` | 1 por página de oportunidades de empresa |
| L7 | **ms-users → ms-services** | `GET /internal/services/{id}/participation` | ¿Está cerrado el servicio, quién es el cliente, quién ejecutó y cuándo se cerró? | `ms-users` `EvaluationService.java:78` | 1 por evaluación |

Fuera de este par, pero con el mismo efecto sobre la disponibilidad:

- `ms-payments` (`Security/TokenRevocation.cs:47`) y `ms-matching-geo` (`token_revocation.py:25`) también consultan L1 en
  cada petición.
- `ms-matching-geo` usa `POST /internal/technicians/search` de `ms-users`.

**Planeado, aún sin código** (contrato de empresas, GC-516 §6): `ms-users` preguntará a `ms-services` por el trabajo
pendiente del técnico, y a `ms-payments` por pagos retenidos, cuando el técnico quiera desvincularse.

**Lo que ya existe para trabajar con eventos:**

- Outbox y relay en los dos servicios. `ms-services` publica en `fixia.services.service-requests`, con clave = solicitud.
  `ms-users` publica `evaluacion.registrada` en `fixia.users.evaluations`.
- `ms-services` ya **consume** Kafka de forma idempotente: `PaymentEventsListener` y la tabla `processed_events`.
  `ms-users` todavía no consume nada, pero tiene `spring-kafka`.

## 3. Decisión llamada por llamada

| # | Dato y fuente de verdad | ¿A eventos? | Por qué |
| --- | --- | --- | --- |
| **L7** | Cierre del servicio y sus participantes, en `ms-services` | **Sí. Fase 1** | Lo que pide ya viaja en `servicio.cerrado`, salvo el ejecutor (§4). La evaluación tiene 2 días de plazo, así que unos segundos de retraso no importan. Es la **única llamada de `ms-users` hacia `ms-services`**: al quitarla se rompe el ciclo. Sin riesgo de seguridad: solo cambia de dónde se lee un hecho ya ocurrido |
| **L1** | Revocación de sesiones, en `ms-users` | **Sí, con condiciones. Fase 2** | Es la que ata la disponibilidad de los cuatro servicios a `ms-users`. Con eventos, un token revocado podría servir **unos segundos más** en los otros servicios (§5.1). Requiere aceptarlo de forma explícita: el ADR-039 eligió fallar cerrado |
| **L5, L6** | Pertenencia de técnicos a empresas, en `ms-users` | **Sí. Fase 3** | Los eventos `empresa.tecnico_vinculado` y `empresa.tecnico_desvinculado` ya están decididos (contrato de empresas, §6.3 y P4). Hoy no hay forma de vincular ni desvincular salvo el seed, así que no hay qué emitir hasta GC-523 y GC-524. Con una proyección local, las oportunidades de empresa dejan de llamar a L6. **Al aceptar** una cotización conviene **seguir confirmando con L5**: ahí se compromete dinero, y una proyección atrasada no debe dejar contratar a un técnico que ya se fue |
| **L2** | Reglas de elegibilidad (verificación, categoría, zona y horario), en `ms-users` | **No** | Para moverla habría que copiar perfil, especialidades, círculo de cobertura y franjas, **y duplicar las reglas** (ADR-041). El dueño natural de «quién puede atender» es `ms-matching-geo` (ADR-020, CQRS con PostGIS), que hoy está simulado. Si se rehace, debe alimentarse con eventos de `ms-users` en `ms-matching-geo`, no en `ms-services` |
| **L3, L4** | Perfil público y reputación, en `ms-users` | **No, por ahora** | El dato cambia por muchos caminos en `ms-users`: perfil, especialidades, zona, verificación, cuenta deshabilitada, cada evaluación y cada vínculo con empresa. Se necesitarían varios eventos para una pantalla de solo lectura. Si `ms-users` cae, hoy `ms-services` ya está caído por L1; L3 y L4 solo aportan disponibilidad después de la fase 2. Revisar entonces |
| Plan GC-516 §6 | Trabajo pendiente y pagos retenidos del técnico | **No, mantener síncrona** | Desvincularse es raro y lo pide el propio usuario. Necesita una respuesta **exacta** («no tienes nada pendiente») y falla cerrado (503). Reconstruir el trabajo pendiente en `ms-users` exigiría consumir casi todos los eventos del ciclo del servicio. Como la llamada ocurre solo en ese caso, el ciclo que reintroduce no afecta a la operación diaria |

## 4. Contrato sugerido para la fase 1 (L7)

### 4.1 Evento: `servicio.cerrado` v1, con dos campos opcionales más

Hoy lleva `contractId`, `serviceRequestId`, `clientId`, `providerId`, `closedAt` y `status`. Se agregan dos campos
**opcionales**; es un cambio compatible y sigue siendo v1 (`docs/events/README.md` §9):

| Campo | Valor |
| --- | --- |
| `executorId` | El técnico que ejecutó (GC-551). Es igual a `providerId` si el prestador es independiente. GC-551 lo agregó a otros cuatro eventos, pero **no a este** |
| `providerType` | `INDEPENDENT` o `COMPANY` |

El esquema `servicio.cerrado.v1.schema.json` se actualiza en el mismo PR. El lakehouse debe copiarlo, igual que con
GC-551.

### 4.2 Consumidor en `ms-users`

- **Grupo** `ms-users-participations`, tópico `fixia.services.service-requests`. Lee **solo** `servicio.cerrado` e ignora
  los demás tipos (lector tolerante).
- **Idempotente:** tabla `processed_events (event_id PK)` en la misma transacción que la proyección, igual que
  `PaymentEventsListener` en `ms-services`. Una reentrega no cambia nada.
- **Proyección** `closed_services`: `service_request_id` (PK), `client_id`, `provider_id`, `executor_id`,
  `provider_type`, `closed_at`, `received_at`.
- **Orden:** la clave del tópico es la solicitud y `servicio.cerrado` es el último hecho del servicio, así que no hay
  carreras entre eventos de una misma solicitud.

### 4.3 Uso en la evaluación

1. `EvaluationService` busca primero en `closed_services`.
2. **Si no está** (el evento aún no llegó, o el servicio se cerró antes del consumidor) usa L7 como respaldo, igual que
   hoy. El respaldo se quita cuando `closed_services` se haya rellenado con el histórico: reprocesando el tópico desde
   el principio (el Outbox conserva al menos 7 días) o con una carga única por L7.
3. Si el evento llega pero `executorId` falta (eventos viejos), se usa `providerId`. Si `providerType` es `COMPANY` y
   no hay ejecutor, se rechaza como hoy (`COMPANY_SERVICE_NOT_EVALUABLE`).

**Resultado:** `ms-users` deja de llamar a `ms-services` en la operación diaria, y el ciclo de dependencias desaparece.

## 5. Riesgos

### 5.1 Fase 2: revocación por eventos (L1)

- **El riesgo:** entre el cierre de sesión y la llegada del evento `sesion.revocada` (`jti`, `expiresAt`) a cada
  servicio, el token sigue funcionando allí. Con Kafka en QA son, típicamente, **menos de un segundo a pocos segundos**.
  El token vence en **15 min** de todos modos.
- **Mitigaciones:**
  1. Cada servicio guarda la lista localmente hasta `expiresAt` y la limpia después.
  2. **Guardia de retraso:** si el consumidor de un servicio va atrasado más de N segundos (por ejemplo 30), ese servicio
     vuelve a consultar L1 de forma síncrona hasta ponerse al día.
  3. Al arrancar, el servicio lee el tópico desde lo necesario para cubrir los últimos 15 min antes de aceptar tráfico.
- **Alternativa más barata, que no desacopla:** guardar en caché unos segundos la respuesta «no revocado» de L1. Reduce
  las llamadas, pero si `ms-users` cae, la caché se vence y vuelve el 401. **No resuelve el problema; solo lo esconde.**
- **Decide el PO:** aceptar o no una ventana de revocación de segundos. Si no se acepta, L1 queda síncrona y la fase 2 no
  se hace.

### 5.2 Comunes a todas las fases

| Riesgo | Mitigación |
| --- | --- |
| Evento perdido o consumidor caído | Outbox con reintentos (ya existe); el consumidor confirma el offset después de escribir; respaldo síncrono en la fase 1 |
| Reentrega duplicada | `processed_events` por `eventId` |
| Esquema que cambia | Solo campos opcionales en v1; un cambio incompatible es v2 (README de eventos §9) |
| Proyección vacía al desplegar | Reprocesar el tópico desde el inicio o hacer una carga única antes de quitar el respaldo |
| El lakehouse rechaza el esquema nuevo | Copiar los esquemas en el mismo sprint (pendiente también desde GC-551) |
| Más piezas que operar | Un grupo de consumidores más por fase; medir el retraso en Grafana (GC-458) |

## 6. Criterio para crear las tareas de implementación

| Fase | Se crea la tarea cuando… | Tamaño | Dónde |
| --- | --- | --- | --- |
| **1. Participación por eventos** | **Ya**: no depende de nadie y rompe el ciclo | ~1 día | `ms-services` (2 campos en `servicio.cerrado` y su esquema) + `ms-users` (consumidor, `processed_events`, `closed_services`, respaldo L7, pruebas) |
| **2. Revocación por eventos** | El PO acepta por escrito la ventana de §5.1 | ~2 días | `ms-users` (evento `sesion.revocada` en el Outbox) + consumidor y lista local en `ms-services`, `ms-payments` y `ms-matching-geo`, con la guardia de retraso |
| **3. Pertenencia a empresas** | GC-523 y GC-524 están en Qa y publican los dos eventos | ~1 día | `ms-services` (consumidor y proyección `company_members`; L6 y las lecturas de L5 pasan a la proyección; **al aceptar se sigue usando L5**) |

Fuera del alcance de este spike: rehacer `ms-matching-geo` como dueño de la elegibilidad (L2), que es una decisión de
producto (búsqueda geográfica real o simulada).

## 7. Documentos a actualizar con la decisión

- **ADR-041 (elegibilidad síncrona):** sigue vigente para L2; dejar anotado por qué no se movió.
- **ADR-039 (revocación que falla cerrado):** se reemplaza en parte **solo si** se aprueba la fase 2.
- **ADR nuevo «Participación por eventos»** cuando se implemente la fase 1.
- **SDD y DD:** diagrama de dependencias entre servicios. Hoy hay un ciclo `ms-users ⇄ ms-services`; después de la
  fase 1 queda en un solo sentido.

## 8. Primera implementación (2026-11-22, reemplazada por §9)

Se implementaron las fases 1 a 3 tal como se propusieron, con la revocación por eventos solo como respaldo y la
pertenencia a empresas con una foto inicial por L6. Quitaba el ciclo y la caída en cadena, pero dejaba llamadas
síncronas (L2, L3, L4, L5 al aceptar, la desvinculación de GC-524 y la búsqueda de ms-matching-geo). El PO pidió llegar
a **cero dependencias síncronas**, así que esa versión se reemplazó por la de §9 en una sola rama por repositorio.

## 9. Cero dependencias síncronas (2026-11-22)

Rama `feature/GC-547-cero-dependencias` en `ms-users`, `ms-services`, `ms-payments` y `ms-matching-geo`. **Ningún
servicio llama a otro por HTTP**: se comprobó buscando clientes HTTP y rutas `/internal/**` llamadas en los cuatro
repositorios (no queda ninguna). Las rutas internas siguen expuestas, sin uso, para no romper versiones viejas durante el
despliegue; se pueden quitar después.

### 9.1 Cómo funciona: estado replicado

Cada servicio dueño de un dato lo **publica completo** (no solo el cambio) en un tópico propio; quien lo necesita guarda
una copia local y decide con ella.

| Estado (`eventType`) | Tópico (clave) | Lo publica | Lo copia | Reemplaza a |
| --- | --- | --- | --- | --- |
| `tecnico.estado` | `fixia.users.technician-states` (técnico) | ms-users | ms-services, ms-matching-geo | L2 elegibilidad, L3 perfiles, L5/L6 pertenencia, búsqueda de matching |
| `empresa.estado` | `fixia.users.company-states` (cuenta de la empresa) | ms-users | ms-services | L4 perfiles de empresa, empresa activa |
| `servicio.participacion` | `fixia.services.participations` (solicitud) | ms-services | ms-users | L7 participación |
| `ejecutor.carga` | `fixia.services.executor-workloads` (técnico) | ms-services | ms-users | trabajo pendiente al desvincularse (GC-524) |
| `pago.retencion` | `fixia.payments.held-services` (solicitud) | ms-payments | ms-users | `/internal/payments/held` (GC-524) |
| `sesion.revocada` | `fixia.users.sessions` (`jti`) | ms-users (Outbox, al cerrar sesión) | ms-services, ms-payments, ms-matching-geo | L1 revocación |

- **Publicador** (`replication.StatePublisher` en Java, `HeldServicesStatePublisher` en .NET): cada 3 s calcula el
  estado de cada entidad, lo compara con el hash de lo último publicado y escribe en el Outbox **solo lo que cambió**;
  cada 6 h lo vuelve a publicar todo, para que un consumidor nuevo lo encuentre pese a la retención de 24 h de QA. El
  primer arranque publica todo: eso reemplaza a la "foto" por REST y cubre los datos del seed, que nunca emitieron
  eventos. La secuencia es el instante en milisegundos; una entidad que desaparece se publica con `removed=true`.
- **Consumidor** (`replication.StateEventsListener` + `replicated_states` en Java; réplica en memoria en
  ms-matching-geo): guarda la versión de mayor secuencia de cada clave; una repetida o atrasada no cambia nada.
- **Reglas portadas:** ms-services evalúa la elegibilidad con las reglas de ms-users (categoría, ciudad sin tildes ni
  mayúsculas, círculo, franja en hora de Colombia). La de verificación (RN-001) la resuelve ms-users y viaja ya decidida
  en `verificationRequirementMet`. Si una regla cambia en ms-users, hay que cambiarla en `UserReplica` (ms-services) y en
  `replication.py` (ms-matching-geo).
- **Sin datos sensibles:** los estados no llevan correo, teléfono, documento ni NIT (lo comprueban las pruebas). El de
  técnicos sí lleva el círculo de la zona, como la ruta interna de búsqueda; solo viaja por tópicos internos y nunca se
  devuelve a un cliente.

### 9.2 Revocación sin ms-users

La lista local de `jti` revocados es la **única** fuente. Un token revocado sirve en los demás servicios el tiempo que
tarda `sesion.revocada` en llegar: en la prueba de punta a punta fue **0,57 s** (Outbox de ms-users cada 0,5 s). Con el
Outbox por defecto (2 s) la ventana es de unos 2 a 3 s; se acorta con `FIXIA_OUTBOX_RELAY_DELAY_MS`. Si la lista no está
al día (el consumidor no confirmó con el broker en los últimos 5 s, `FIXIA_REVOCATIONS_MAX_STALENESS`), **se responde
401**: falla cerrado, ahora atado a Kafka y no a ms-users.

Defecto encontrado y corregido en la prueba real: en ms-services, el contenedor de Spring Kafka solo avisa la inactividad
al volver de `poll()`, que esperaba hasta 5 s; la lista se confirmaba cada ~5 s y vencía. Ahora el `poll()` espera
`idle-interval/2` y se confirma cada ~1 s.

### 9.3 Consistencia eventual aceptada y compensación

Las copias van unos segundos atrás. Lo más delicado es la empresa: un técnico podría desvincularse (ms-users mira su
copia del trabajo) mientras el cliente acepta una cotización suya (ms-services mira su copia de la pertenencia). Al llegar
el estado del técnico sin la empresa, `CompanyDepartureCompensation` **vence sus cotizaciones enviadas** en nombre de esa
empresa y **registra como conflicto** cualquier contratación activa con él como ejecutor, para que el administrador la
resuelva (reembolso o reasignación).

Otros efectos de unos segundos: un técnico que cambia zona o franjas puede verse con las anteriores; un servicio recién
cerrado puede no ser evaluable aún (422); tras desplegar, hasta que la réplica cargue, la búsqueda de ms-matching-geo
responde 502 y la elegibilidad dice `TECHNICIAN_NOT_FOUND`.

### 9.4 Evidencia

- **Suites completas en verde:** ms-users y ms-services (`mvn clean verify`, con cobertura), ms-payments (221, más una
  prueba contra Kafka real) y ms-matching-geo (105).
- **Prueba de punta a punta** (ms-users y ms-services de la rama, Kafka local, bases temporales que se borraron al final):
  registro de técnico y cliente; el estado del técnico llegó a ms-services en 3 s; ms-services encontró al técnico
  compatible sin llamar a nadie; la participación llegó a ms-users en 1 s; tras el cierre de sesión ms-services rechazó el
  token en 0,57 s; **con ms-users apagado, ms-services siguió autenticando y respondiendo con su réplica (200)** y siguió
  rechazando el token revocado.

### 9.5 Despliegue (para el DevOps)

1. Crear los tópicos `fixia.users.technician-states`, `fixia.users.company-states`, `fixia.users.sessions`,
   `fixia.services.participations`, `fixia.services.executor-workloads` y `fixia.payments.held-services`. De preferencia
   con `cleanup.policy=compact` (los cinco de estado); si no, basta la republicación cada 6 h.
2. Orden sugerido: ms-users, ms-payments, ms-services y ms-matching-geo. Las rutas internas siguen vivas mientras tanto.
3. ms-matching-geo necesita `confluent-kafka==2.6.1` y `KAFKA_BOOTSTRAP_SERVERS` o `KAFKA_HOST`. ms-services deja de usar
   `USERS_BASE_URL` y `MATCHING_BASE_URL`; ms-payments, `USERS_BASE_URL`.
4. Migraciones nuevas: `V14__state_replication` (ms-users) y `V19__state_replication` (ms-services). ms-payments no
   tiene migración: guarda los hashes en memoria.
5. Lakehouse: copiar `servicio.cerrado.v1` (dos campos opcionales) y el sobre de ms-users (roles `COMPANY` y `ADMIN`,
   agregado `Session`). Los tópicos de estado no son hechos de negocio: no hace falta suscribirlo.

### 9.6 ADR propuesto (para GC-453)

**ADR-0xx — Cero dependencias síncronas entre microservicios (GC-547).** *Contexto:* §1; el PO pidió que ningún servicio
dependa de otro en línea. *Decisión:* estado replicado por Kafka (tabla de §9.1) y revocación por lista local que falla
cerrada si no está al día. *Consecuencias:* la caída de un servicio no afecta a los demás; consistencia eventual de
segundos con compensación para empresas; reglas de elegibilidad duplicadas en ms-services y ms-matching-geo (se reemplaza
ADR-041); ADR-039 pasa a "falla cerrado si la lista no está al día"; más tópicos que operar.
