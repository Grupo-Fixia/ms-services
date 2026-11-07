# Contrato del chat por servicio (GC-527)

Tarea GC-527 de la historia GC-517 («Intercambiar mensajes reales entre cliente y técnico de un servicio», F5.1).
Reemplaza el chat simulado de GC-406 (ALC-07) por mensajes reales y persistentes. Lo consume GC-528 (Flutter) y lo valida
GC-529.

Fuentes: SRS V6 (RF-038 a RF-040, RF-043, RN-018), DD V4 (CD-05, colección `Messages`), SAD V2 (componente de
Mensajería, ESC-03), contrato de empresas `ms-users/docs/contracts/empresas.md` §8 (GC-551).

## 1. Decisiones

| # | Decisión | Por qué |
| --- | --- | --- |
| D1 | **El chat vive en `ms-services`, en PostgreSQL** (tabla `service_messages`, migración V18), y no en un microservicio de Mensajería con MongoDB | Decisión de Tomás O. (2026-11-07). Al cierre del proyecto, un servicio nuevo necesitaba repositorio, despliegue, secretos y un usuario de MongoDB que dependían del DevOps. La conversación pertenece a la contratación, que ya está en `ms-services`, y así los permisos se comprueban sin llamar a otro servicio. **Se aparta del DD V4 (CD-05) y del SAD V2**: ver §7 |
| D2 | **Participan solo el cliente y el técnico que ejecuta** (`executor_id` del contrato, GC-551). En un servicio de empresa, la empresa **no lee ni escribe** | Decisión de Tomás O. (2026-11-07). RF-040: el contenido es solo de sus participantes. El técnico designado es quien negocia y ejecuta (GC-551) |
| D3 | La conversación **existe desde que se contrata**; se escribe en `CONTRACTED`, `IN_PROGRESS` y `TECHNICIAN_FINISHED`, y **solo se lee** después (`CLOSED`) | La historia pide aislamiento por contrato. Lo acordado antes de contratar se formaliza en la cotización (RN-004) |
| D4 | Los mensajes son **inmutables** y **no viajan en eventos** | RF-043 (se conservan). El texto puede traer datos personales: no va al Outbox, al lakehouse ni a los logs |
| D5 | **Casi en tiempo real con long polling** (`wait`): sin mensajes nuevos, la lectura espera hasta 25 s y responde apenas llega uno. Se prefirió a SSE y WebSocket | Peticiones `GET` normales: el token va en la cabecera (en Flutter web, `EventSource` no lo permite), se valida en cada petición (el token dura 15 min y se revoca al cerrar sesión), quedan bajo los 30 s de Spring MVC y los 100 s de Cloudflare, y un despliegue solo hace fallar una petición. Sin ruta nueva ni cambios en el gateway |
| D6 | Reintentar un envío con el mismo `clientMessageId` **no duplica** | Un doble toque o un reintento por red no debe mostrar el mensaje dos veces |

## 2. Rutas

Dos puertas a la misma conversación; quien consulta sale siempre del token.

| Método | Ruta | Quién |
| --- | --- | --- |
| `GET`, `POST` | `/api/services/contracts/{contractId}/messages` | El técnico que ejecuta (`ROLE_PROFESSIONAL`) |
| `GET`, `POST` | `/api/services/requests/{serviceRequestId}/messages` | El cliente dueño de la solicitud (`ROLE_CLIENT`) |

| Caso | Respuesta |
| --- | --- |
| Sin token | 401 |
| Rol que no corresponde a la ruta, o empresa (`ROLE_COMPANY`) | 403 |
| Contratación o solicitud ajena, inexistente o sin contratar | 404 (la misma, para no revelar qué existe) |

### 2.1 Enviar: `POST …/messages`

```json
{ "body": "Hola, ¿llega a las 9?", "clientMessageId": "8f6c…" }
```

- `body`: obligatorio. Se quitan los espacios de los extremos y debe quedar con 1 a 2000 caracteres; si no, 400.
- `clientMessageId`: opcional (UUID). Si se repite con el mismo texto, **200** con el mensaje ya guardado; con otro texto, **409**.
- Servicio cerrado (o en un estado que no admite mensajes): **409** «Conversación cerrada».
- **201** con el mensaje:

```json
{
  "id": "…", "contractId": "…", "serviceRequestId": "…",
  "senderId": "…", "senderRole": "CLIENT", "recipientId": "…",
  "body": "Hola, ¿llega a las 9?", "clientMessageId": "8f6c…",
  "createdAt": "2026-11-10T14:05:12.123456Z", "mine": true
}
```

### 2.2 Leer: `GET …/messages?after=&limit=&wait=`

- Orden de envío, del más antiguo al más reciente.
- `limit`: 1 a 100 (50 por defecto). Fuera de rango, 400.
- `after`: el `nextCursor` de la respuesta anterior; solo se devuelven los mensajes posteriores. Cursor inválido, 400.
- `wait`: 0 a 25 segundos (0 por defecto; fuera de rango, 400). Ver §2.3.

```json
{ "items": [ … ], "nextCursor": "MjAyNi0…", "hasMore": false, "open": true }
```

- `nextCursor` es la posición del último mensaje entregado. Si no hubo mensajes nuevos, es el mismo `after`.
- La app lo guarda y pregunta cada pocos segundos con `?after=<nextCursor>` para recibir solo lo nuevo.
- `hasMore`: ya hay más mensajes esperando (se pide otra página enseguida).
- `open`: si el servicio todavía admite mensajes; la app oculta la caja de texto si es `false`.
- `mine`: si el mensaje lo envió quien consulta.

### 2.3 Long polling (`wait`)

- Con `wait` mayor que 0, si ya hay mensajes después de `after` la respuesta es inmediata. Si no, la petición **queda abierta** hasta `wait` segundos y responde **apenas llega uno**. Cumplido el plazo, responde con `items` vacío y el mismo `nextCursor`.
- Los permisos, el cursor y el límite se revisan **antes** de esperar: 401, 403, 404 y 400 responden enseguida.
- La petición estacionada no ocupa un hilo del servidor (`DeferredResult`). Mientras espera, se mira la base cada `fixia.chat.long-poll-interval` (1 s por defecto), así que el retraso máximo es de alrededor de 1 s.
- Funciona con varias réplicas: el mensaje puede guardarse en cualquiera, y todas leen la misma base.
- Si el volumen crece, la revisión se puede cambiar por `LISTEN/NOTIFY` de PostgreSQL sin tocar la API.

## 3. Datos (`ms-services`, migración V18)

Tabla `service_messages`:

| Columna | Regla |
| --- | --- |
| `id` | UUID |
| `contract_id` | FK a `service_contracts`, en cascada |
| `service_request_id` | Copia de la solicitud del contrato |
| `sender_id`, `recipient_id` | Cliente y ejecutor, en el orden de quien envía; `CHECK` distintos |
| `sender_role` | `CLIENT` o `PROFESSIONAL` |
| `body` | 1 a 2000 caracteres, sin quedar en blanco (`CHECK`) |
| `client_message_id` | Opcional |
| `created_at` | Microsegundos |

- Índice `(contract_id, created_at, id)` para leer en orden y por cursor.
- Índice único `(contract_id, sender_id, client_message_id)`: dos `NULL` no chocan, y un reintento simultáneo deja un solo mensaje.

## 4. Seguridad y privacidad

- `GET` y `POST /api/services/contracts/*/messages` exigen `ROLE_PROFESSIONAL` (regla antes de la que permite a la empresa consultar contratos). `/requests/**` ya exige `ROLE_CLIENT`.
- El técnico debe ser el **ejecutor** de ese contrato y el cliente el **dueño** de la solicitud. Cualquier otro recibe 404.
- El texto no aparece en `toString()` (logs), ni en eventos, ni en el historial de transiciones.

## 5. Lo que no entra

WebSocket o SSE, adjuntos, «escribiendo…», leído/no leído, notificaciones (RF-041), soporte del ADMIN leyendo conversaciones (RF-040
«soporte autorizado y auditado») y chat antes de contratar.

## 6. Para el front (GC-528)

1. Al abrir el servicio contratado, cargar con `GET …/messages` (sin `after`) y seguir con `nextCursor` mientras `hasMore`.
2. Mientras la pantalla del chat esté abierta, repetir en bucle `GET …/messages?after=<nextCursor>&wait=25`. Al recibir la respuesta, mostrar los mensajes nuevos, guardar el `nextCursor` y volver a preguntar **enseguida**. Si la petición falla (red o despliegue), esperar unos segundos, con espera creciente, antes de reintentar. Cancelar la petición al salir de la pantalla.
3. Enviar con un `clientMessageId` nuevo por mensaje y reutilizarlo si se reintenta.
4. Con `open = false`, mostrar la conversación sin caja de texto.

## 7. Documentos que hay que corregir

- **DD V4 (CD-05):** la mensajería pasa de la colección `Messages` de MongoDB a la tabla `service_messages` de `ms-services` (PostgreSQL).
- **SAD V2:** el componente de Mensajería no es un microservicio aparte en esta entrega. El chat se habilita al contratar, no por el evento `SERVICIO_CREADO`. ESC-03 se cumple con la comprobación de participantes de §4.
- **ADR nuevo** con D1: «Chat dentro de ms-services en PostgreSQL al cierre del proyecto». Alternativa descartada: microservicio de Mensajería con MongoDB. Consecuencia: si el volumen crece (ESC-16), se puede extraer a un servicio propio, porque la tabla no se cruza con otras salvo por `contract_id`.
