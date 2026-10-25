# Transaction Execution API

Este componente es el resultado del challenge de Spin, un API REST que ejecuta transacciones de crédito y débito contra
un proveedor externo simulado con WireMock de acuerdo a los contratos proporcionados, guarda el resultado de la
transacción y permite consultas usando paginación y filtros. La idea principal del proyecto es:

 - Evitar dobles cobros.
 - Mantener en operaciones inciertas veracidad.
 - Alta concurrencia utilizando las herramientas que el propio lenguaje brinda.
 - Arquitectura escalable.
 - Pragmatismo, uso solo de dependencias necesarias, cada pieza responde a un problema concreto.
 - Mostrar el paso a paso del ciclo de ingeniería de una solución, desde el diseño hasta la liberación.

El stack es Java 25, Spring Boot 4.1, PostgreSQL 18 y Docker.

Como parte del ciclo de ingeniería, agregué un LLD, creado a partir del ejercicio que proporcionó el equipo de Spin. En
ese documento se cierra el diseño, la arquitectura y los flujos, que si bien durante la implementación pueden cambiar un
poco, permiten mantener coherencia y minimizar los desvíos. El documento, así como los diagramas de secuencia y de
arquitectura, se encuentran en la carpeta docs de este proyecto para su consulta.

## Demo en AWS

Además de compartir el repositorio y las instrucciones para su ejecución local, el componente se deployó en AWS a manera
de lab, una demo temporal sin autenticación y con datos ficticios. El Swagger puede consultarse en esta URL 
http://100.30.30.204/swagger-ui/index.html#/ y la colección de Postman puede ser ejecutada bien contra el ambiente local 
o contra el deploy en AWS cambiando la variable baseUrl.

## Cómo correrlo en local

**Requisito:** Docker (con Docker Desktop abierto). La aplicación se compila dentro de Docker, así que no hace falta
tener Java instalado para levantarla.

1. Clonar el repositorio:

   ```bash
   git clone https://github.com/arturoquiroz/transaction-execution-api.git
   cd transaction-execution-api
   ```

2. Levantar todo el sistema (API, PostgreSQL y el proveedor simulado con WireMock):

   ```bash
   docker compose up -d --build --wait
   ```

   La primera vez tarda unos minutos porque compila la aplicación. Al terminar, la API queda en el puerto 8080.

3. Probarla:

   - **Swagger UI:** http://localhost:8080/swagger-ui/index.html
   - **Colección de Postman:** `postman/transaction-execution-api.postman_collection.json`. Se importa en Postman y se
     corre completa con el Collection Runner, o sin abrir Postman:

     ```bash
     npx newman run postman/transaction-execution-api.postman_collection.json
     ```

### Correr la aplicación desde el IDE

El contenedor de la API también usa el puerto 8080: si está arriba, Spring no arrancará desde el IDE por el conflicto
de puertos. En ese caso, levantar solo las dependencias:

```bash
docker compose up -d postgres wiremock
./mvnw spring-boot:run
```

### Tests y cobertura

Requieren **Java 25** (`JAVA_HOME` apuntando a un JDK 25) y Docker abierto, porque las pruebas de integración usan una
base de datos PostgreSQL real con Testcontainers:

```bash
./mvnw verify
```

La cobertura se valida con JaCoCo; el reporte queda en `target/site/jacoco/index.html`.

## API

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/transactions` | Ejecuta una transacción. Header **`Idempotency-Key` obligatorio** (máx. 100 caracteres; se recomienda un UUID) |
| `GET` | `/transactions` | Lista paginada, del más reciente al más antiguo. Filtros opcionales `accountId`, `status`, `type`; `page` (desde 0, por defecto 0) y `limit` (1–100, por defecto 20) |
| `GET` | `/transactions/{id}` | Una transacción por su id (`404` si no existe) |

El contrato completo está en Swagger UI (`/swagger-ui/index.html`) y en el documento OpenAPI (`/v3/api-docs`).

### Ejecutar una transacción

```http
POST /transactions
Content-Type: application/json
Idempotency-Key: 5f0c2a8e-3b1d-4c6e-9a7f-2d4b8e1c0a93

{
  "accountId": "acc-123456",
  "type": "CREDIT",
  "amount": 1500.00,
  "currency": "MXN",
  "description": "Transferencia recibida"
}
```

```http
HTTP/1.1 201 Created
Location: /transactions/019a1b2c-3d4e-7f60-8a9b-0c1d2e3f4a5b
Idempotent-Replayed: false
X-Trace-Id: 1245f38d5261ad0feaf9f09fe126b9e4

{
  "id": "019a1b2c-3d4e-7f60-8a9b-0c1d2e3f4a5b",
  "accountId": "acc-123456",
  "type": "CREDIT",
  "amount": 1500.00,
  "currency": "MXN",
  "description": "Transferencia recibida",
  "status": "EXECUTED",
  "providerTransactionId": "txn-789",
  "balanceAfter": 5500.00,
  "failureCode": null,
  "failureMessage": null,
  "createdAt": "2026-10-25T16:30:00.123Z",
  "executedAt": "2026-10-25T16:30:00Z"
}
```

Validación del body (`400`): `accountId` obligatorio, máx. 64, solo letras, dígitos y guiones; `type` `CREDIT` o
`DEBIT`; `amount` obligatorio con máximo 2 decimales; `currency` de 3 letras; `description` opcional, máx. 255.

### Código HTTP según el resultado

| Situación | HTTP | `status` en el body |
|-----------|------|---------------------|
| El proveedor aprobó | `201` | `EXECUTED` |
| El proveedor rechazó por regla de negocio (p. ej. fondos insuficientes) | `201` | `REJECTED`, con `failureCode` y `failureMessage` |
| No se sabe si el proveedor ejecutó (timeout, 5xx sin cuerpo, conexión cortada) | `202` | `UNKNOWN` |
| Es seguro que no se ejecutó (proveedor inalcanzable, circuito abierto) | `503` | `FAILED`: se puede reintentar con **otra** `Idempotency-Key` |
| Misma `Idempotency-Key` y mismo payload | el código original | la misma transacción, con `Idempotent-Replayed: true` |
| Misma `Idempotency-Key` con otro payload | `422` | — (`IDEMPOTENCY_KEY_REUSED`) |
| Misma `Idempotency-Key` mientras la original sigue en curso | `409` | — (`REQUEST_IN_PROGRESS`) |
| Regla de negocio violada: monto ≤ 1.00, débito > 10,000.00, moneda distinta de MXN | `422` | — (código de la regla); no se guarda ni se llama al proveedor |
| Falta la `Idempotency-Key` o el request está mal formado | `400` | — |

### Errores

Todos los errores siguen RFC 9457 (`application/problem+json`) y llevan un `code` estable para que el cliente decida
sin interpretar el texto. Solo se reporta la primera regla violada:

```json
{
  "type": "https://errors.transactions.local/business-rule-violation",
  "title": "Business rule violation",
  "status": 422,
  "detail": "DEBIT amount must not exceed 10000.00",
  "instance": "/transactions",
  "code": "DEBIT_LIMIT_EXCEEDED"
}
```

| `code` | HTTP |
|--------|------|
| `MISSING_IDEMPOTENCY_KEY`, `INVALID_IDEMPOTENCY_KEY` | `400` |
| `VALIDATION_FAILED` (con `errors[]` por campo), `MALFORMED_REQUEST`, `INVALID_PARAMETER` | `400` |
| `AMOUNT_BELOW_MINIMUM`, `DEBIT_LIMIT_EXCEEDED`, `UNSUPPORTED_CURRENCY`, `IDEMPOTENCY_KEY_REUSED` | `422` |
| `REQUEST_IN_PROGRESS` | `409` |
| `TRANSACTION_NOT_FOUND` | `404` |
| `INTERNAL_ERROR` (sin detalles internos; el detalle queda en el log con su `traceId`) | `500` |

### Headers de respuesta

| Header | Para qué |
|--------|----------|
| `Location` | Ruta de la transacción (`/transactions/{id}`) para consultarla después |
| `Idempotent-Replayed` | `true` si la respuesta es la de una transacción que ya existía con esa llave |
| `X-Trace-Id` | Id de la traza: aparece en cada línea de log del request y viaja al proveedor en `traceparent` |

### Escenarios del proveedor simulado

WireMock elige la respuesta por `accountId` (`wiremock/mappings/`), así que cada escenario se dispara desde Swagger o
Postman sin configurar nada:

| `accountId` | Respuesta del proveedor | Resultado |
|-------------|-------------------------|-----------|
| cualquier otro | `200 APPROVED` | `201 EXECUTED` |
| `acc-insufficient` | `422 REJECTED / INSUFFICIENT_FUNDS` | `201 REJECTED` |
| `acc-blocked` | `403 REJECTED / ACCOUNT_BLOCKED` | `201 REJECTED` |
| `acc-bad-request` | `400` sin cuerpo | `503 FAILED` |
| `acc-server-error` | `500` sin cuerpo | `202 UNKNOWN` |
| `acc-slow` | `200` con 5 s de retraso (el timeout de lectura es 3 s) | `202 UNKNOWN` |
| `acc-reset` | conexión cortada tras recibir la petición | `202 UNKNOWN` |

## Decisiones de diseño

Las siguientes son las decisiones de diseño que se tomaron, con una breve explicación del porqué de cada una:

**1. Máquina de estados.** Se decide utilizar una máquina de estados para manejar el estado de las operaciones en un
momento dado del ciclo de vida del componente, esto porque durante un ciclo completo una transacción pasa de estar
pendiente a completada, y completado puede significar ejecutado, rechazado o fallido. También hay escenarios donde
desconocemos el estado final de una transacción, como se explica en los siguientes puntos, estado que debe comunicarse
de forma honesta. Derivado de todo esto, se optó por una máquina de estados con los valores
PENDING -> EXECUTED / REJECTED / FAILED / UNKNOWN, donde cada uno representa un estado lógico de la transacción. Solo se
puede salir de PENDING, y eso se protege en dos niveles: el dominio rechaza cualquier otra transición, y la base de datos
solo actualiza con `UPDATE ... WHERE status = 'PENDING'`, que es atómico aunque haya varias instancias del servicio.

**2. FAILED vs UNKNOWN, la decisión central.** El proveedor no acepta una llave de idempotencia, así que reintentar una
petición que sí llegó puede cobrar dos veces. Por eso se separan dos tipos de error:

- **FAILED:** es seguro que la petición no se ejecutó (conexión rechazada, circuito abierto, 4xx sin cuerpo). Responde
  `503` y el cliente puede reintentar con otra `Idempotency-Key`.
- **UNKNOWN:** la petición pudo ejecutarse, pero no hubo una respuesta confiable (timeout de lectura, 5xx sin cuerpo,
  conexión cortada). Responde `202` ("registrada, resultado pendiente de confirmar") y no invita a reintentar.

Tratar un timeout como "falló" sería lo más simple, pero invitaría al cliente a reintentar y, si el proveedor sí había
ejecutado el débito, se cobraría dos veces. Un rechazo de negocio (fondos insuficientes, cuenta bloqueada) responde
`201 REJECTED`: la petición se procesó bien y quedó registrada con un resultado válido; el estado va en el cuerpo.

**3. Write-ahead + separación en transacciones cortas.** Se decidió insertar la transacción como pendiente en una
transacción de base de datos antes de llamar al proveedor o ejecutar alguna otra cosa, esto porque de esa forma dejamos
rastro de la transacción aun si el proceso o el pod muere en un paso posterior, al llamar al proveedor o al guardar el
resultado. El resultado se guarda después en una segunda transacción, también corta, así que ninguna transacción de base
de datos queda abierta durante la llamada al proveedor: no se retiene una conexión del pool mientras se espera la red
(hasta 3 segundos), y unos cuantos requests lentos no dejan sin base de datos al resto. Así se usa solo lo necesario.

**4. Llave de idempotencia obligatoria.** Usamos una llave de idempotencia para garantizar que las transacciones solo se
ejecuten una vez. La manda el cliente porque solo él sabe si está reintentando: dos créditos idénticos de $100 a la misma
cuenta pueden ser legítimos, así que no se puede deducir del contenido. Lo validamos de dos formas:

- **En la base de datos**, con un `UNIQUE` sobre la llave: aunque lleguen dos requests con la misma llave al mismo
  tiempo, solo uno puede insertarse; el otro recibe la transacción existente.
- **Con un hash del payload** guardado junto a la llave, que detecta otra cosa: la misma llave reutilizada con datos
  distintos (responde `422 IDEMPOTENCY_KEY_REUSED`). El hash se calcula sobre el request ya normalizado, así que `100` y
  `100.00` cuentan como el mismo payload.

Un reintento legítimo recibe la misma transacción con el código HTTP original (`Idempotent-Replayed: true`), o `409` si
la original todavía está en curso.

**5. Reglas de negocio con fail-fast.** Se validan las reglas de negocio bajo un patrón fail-fast: se iteran las reglas
y, en la primera que hay una violación, se retorna la respuesta acorde al caso, descriptiva. Fail-fast evita que, si en
un futuro alguna regla depende de un proveedor, una consulta a BD o algún proceso que consuma más recursos, la aplicación
gaste recursos en una petición que de cualquier forma se sabe que será rechazada. Además, con las reglas actuales una
petición puede violar como máximo dos a la vez (el monto mínimo y el tope de débito se excluyen entre sí), así que
fail-fast le cuesta al cliente, a lo sumo, un intento extra. Las reglas se validan antes de guardar nada o llamar al
proveedor, y sus límites vienen de configuración.

**6. Comunicación con el proveedor.** Cada respuesta del proveedor se clasifica en uno de cuatro resultados (aprobado,
rechazado, no ejecutado, incierto), siguiendo la regla de que si no estamos seguros de que la petición no llegó, el
resultado es incierto. Sobre esa clasificación:

- **Timeouts** de 1 s para conectar y 3 s para leer: sin ellos, un proveedor colgado dejaría requests esperando para
  siempre.
- **Reintentos solo cuando es seguro:** se reintenta una sola vez y únicamente si la conexión fue rechazada o no se pudo
  establecer, porque ahí la petición nunca salió. Un timeout de lectura o un 5xx nunca se reintentan (punto 2).
- **Circuit breaker** para proteger la comunicación, en el sentido de que si el proveedor está caído o intermitente, no
  le mandemos más carga temporalmente hasta que se recupere y el error no se propague a nuestro lado. Solo cuenta como
  falla lo que indica un proveedor degradado; un rechazo de negocio es el proveedor funcionando bien y no abre el
  circuito.

Ambas funcionalidades se ejecutan con Resilience4j.

**7. Sweeper e historial de estados.** Una transacción solo queda en PENDING si nadie llega a guardar su resultado: el
proceso o el pod muere después del INSERT (antes, durante o después de llamar al proveedor), o el UPDATE final falla
porque la base de datos no responde en ese instante. Sin nada más, se quedaría pendiente para siempre. Para solucionarlo
agregué un sweeper, un proceso periódico que corre cada minuto y mueve a UNKNOWN las transacciones que llevan más de
5 minutos en PENDING. Los 5 minutos son mucho más que lo que puede durar un request vivo (unos 5 segundos con timeouts y
reintento), así que nunca toca una transacción que todavía está en curso. Las pasa a UNKNOWN porque no puede preguntarle
al proveedor si ejecutó o no, y al no tener el dato, pasarla a EXECUTED o a FAILED sería erróneo.

Además:

- **Historial de solo inserción (`transaction_events`):** cada cambio de estado, incluida la creación, queda registrado
  con su estado de origen y de destino, quién lo hizo (`API` o `SWEEPER`) y cuándo. El UPDATE sobrescribe el estado
  actual, pero el historial permite responder cuándo y por qué cambió cada transacción.
- **Si el resultado del proveedor no se pudo guardar**, se registra un log ERROR con lo que respondió el proveedor
  (estado, `providerTransactionId`, saldo). Es el único lugar donde queda ese dato para conciliar, y puede disparar una
  alerta.

La conciliación automática de los UNKNOWN no es posible con este contrato del proveedor (ver
[Limitaciones y evolución](#limitaciones-y-evolución)).

**8. Observabilidad.** Se agregó Micrometer Tracing con OpenTelemetry, de inicio para tener el trace id en los logs sin
tener que manejar MDC a mano y sin el problema de perderlo entre hilos. El mismo id se devuelve al cliente en el header
`X-Trace-Id` y viaja al proveedor en el header estándar `traceparent`, así que un problema reportado se puede seguir de
punta a punta. Cada transacción deja en el log su registro como PENDING y su resultado final, y el sweeper una línea por
ejecución. Esto nos deja también con una aplicación lista para conectarse con Grafana, Splunk, OpenObserve y otras
herramientas de este tipo; en el demo, la exportación a esas herramientas está apagada.

## Persistencia: PostgreSQL y JdbcClient

Se decide usar como BD una BD relacional, en este caso PostgreSQL, por varias razones. Primero, el hecho de que se manejan
datos financieros, lo que nos lleva a buscar ACID; aunado a ello, los constraints nos son de utilidad: el UNIQUE sobre la
llave para garantizar la idempotencia, y los CHECK para que nunca exista en la base un dato inválido (un estado que no
existe, un monto menor o igual a cero, un tipo que no es CREDIT ni DEBIT), aunque hubiera un bug en el código. También
NUMERIC para manejar las cifras exactas para dinero, sobrevive a reinicios y escala, aunque esas últimas características
las podríamos obtener con otros motores.

Las otras opciones que permite el reto se descartaron por eso: una base in-memory no sobrevive un reinicio ni se comparte
entre instancias, y la idempotencia necesita ambas cosas; una base de documentos como MongoDB no da de forma natural el
UNIQUE y el UPDATE condicional atómico en los que descansa la correctitud del flujo.

Respecto a la forma de conectar el componente Java con el motor de BD, opté por JdbcClient y no JPA. Esto también se
deriva de varios motivos: el primero es que se trata de solo 2 tablas y 6 queries aprox., sin relaciones complejas entre
ellas ni más objetos, y la correctitud de los datos depende de 2 operaciones atómicas, más su inserción en la tabla de
eventos, con UNIQUE y UPDATE, que con SQL explícito se pueden ver y son fácilmente legibles y entendibles. Además, al usar
arquitectura hexagonal, de la que hablaremos más adelante, la entidad JPA sería otra clase más un mapper; esto nos lleva
a agregar cierta complejidad a código que en su estado actual puede ser mucho más simple, sin hablar de que con JPA
se tendría que manejar el flush y demás estados de los objetos para guardar consistencias que ya guardamos con JdbcClient.
Por otro lado, si en el futuro el proyecto creciera a un modelo más complejo, entonces se podría migrar a JPA con esa
justificación, y dado que la arquitectura es hexagonal, solo habría que cambiar el adaptador y agregar las entidades; el
cambio no es tan significativo a nivel de impacto técnico. Con JPA, el UPDATE condicional se resolvería con una consulta
`@Modifying` (o con bloqueo optimista vía `@Version`), y el insert con `saveAndFlush`, para que la violación del UNIQUE
ocurra en ese momento y no hasta el commit.

Se decidió además versionar el esquema con Liquibase porque permite migraciones entre motores con ajustes menores, es
un estándar en la industria y mi experiencia me decanta hacia allá. Se consideró Flyway, pero al final se descarta por lo
mencionado.

Se decidió probar el adaptador de persistencia con Testcontainers, es decir, contra un PostgreSQL 18 real en Docker, el
mismo motor y versión que en producción. Así se verifican de verdad el UNIQUE, los CHECK, el UPDATE condicional, la
consulta del sweeper y el manejo de fechas con zona horaria; una base en memoria como H2 no soporta algunas de esas cosas
o las soporta con otro comportamiento, y los tests pasarían sin probar lo que corre en producción. El dominio y los casos
de uso, en cambio, se prueban sin base de datos, con objetos en memoria, porque no saben de infraestructura.

## Qué usé y qué no

Durante el desarrollo hubo varias decisiones sobre qué herramientas usar y cuáles dejar fuera. La regla fue la misma en
todos los casos: cada dependencia tiene que resolver un problema concreto del reto; si no, se queda fuera.

| Usé | En lugar de | Por qué |
|-----|-------------|---------|
| JdbcClient con SQL explícito | JPA / Hibernate | Ver [Persistencia](#persistencia-postgresql-y-jdbcclient): pocas tablas, sin relaciones, y la correctitud depende de operaciones atómicas que con SQL se ven tal cual. |
| Virtual threads (Java 25) | WebFlux | La llamada al proveedor es I/O bloqueante; con virtual threads se atienden muchos requests esperando red sin programación reactiva, y con JDBC bloqueante WebFlux obligaría además a pasar a R2DBC. Java 25 y no 21 porque desde Java 24 (JEP 491) un virtual thread ya no se ancla a su hilo de plataforma dentro de un `synchronized`, y ese `synchronized` puede estar en librerías que no controlamos. |
| Records y código explícito; Lombok solo `@RequiredArgsConstructor` y `@Slf4j` | Lombok completo, o nada de Lombok | Los records cubren los objetos de valor y los DTOs. En la entidad `Transaction` no quiero setters generados, porque se saltarían la máquina de estados. Lombok queda solo para el boilerplate sin riesgo: constructores de inyección y loggers, y nunca en el dominio. |
| Resilience4j | `@Retryable` de Spring | Spring trae reintentos, pero no circuit breaker; Resilience4j da los dos y permite decidir con precisión qué cuenta como falla y qué se reintenta. |
| Micrometer Tracing con OpenTelemetry | MDC a mano | El MDC tiene trampas conocidas (se fuga entre requests si no se limpia, se pierde al cambiar de hilo, permite inyectar texto en los logs). Si las conocemos, no tiene caso llevárnoslas; la librería las resuelve y además propaga el id al proveedor. |
| WireMock | Un stub en código o un mock de Postman | El mismo mock sirve para los tests de integración, para docker compose y para AWS, y simula fallas reales de red (retrasos, conexión cortada), no solo respuestas JSON. |
| UUIDv7 con `java-uuid-generator` | UUIDv4, una secuencia o un generador propio | Ordenado por tiempo, así que los inserts caen al final del índice, y no es adivinable. Existe una librería probada, así que no hay por qué reinventar la rueda. |
| Liquibase | Flyway | Ver [Persistencia](#persistencia-postgresql-y-jdbcclient). La versión 5 que trae Spring Boot cambió su licencia a FSL (libre de usar, salvo para ofrecer Liquibase como producto); para un servicio propio no hay restricción. |
| Testcontainers con PostgreSQL 18 | H2 en memoria | Probar contra el mismo motor que en producción. |
| springdoc-openapi | Documentar el API a mano | Swagger UI y el contrato OpenAPI se generan del propio código, así que no se desactualizan. |

Y lo que se consideró y se dejó fuera, a propósito:

- **MapStruct:** convertir `Transaction` a su respuesta son unas 15 líneas; no justifica otra dependencia ni otro
  procesador de anotaciones.
- **Spring Security / OAuth2:** fuera del alcance del reto (ver
  [Limitaciones y evolución](#limitaciones-y-evolución)).
- **Lock distribuido para el sweeper (ShedLock):** si varias instancias corren el sweeper a la vez no pasa nada, porque
  el UPDATE solo mueve filas que siguen en PENDING; no hace falta coordinarlas.
- **Kafka / eventos a otros servicios:** el reto no pide notificar a nadie; queda como evolución con el patrón outbox.

## Arquitectura y estructura del código

Se decide usar una arquitectura hexagonal ligera (puertos y adaptadores), en un solo módulo de Maven y separada por
paquetes. La regla es que el núcleo no conoce a nadie: el dominio no importa nada de Spring, ni de HTTP, ni de SQL, y
los adaptadores dependen del núcleo, nunca al revés. Esto es lo que permite probar el dominio y los casos de uso en
milisegundos, sin levantar Spring, y cambiar una pieza de infraestructura (por ejemplo JdbcClient por JPA) sin tocar las
reglas. No lo separé en varios módulos de Maven porque, para un servicio de este tamaño, agrega ceremonia sin beneficio.

```
com.aquiroz.transactions
├── domain/                 sin Spring
│   ├── model/              Transaction (máquina de estados), TransactionRequest, estados y tipos
│   ├── rule/               reglas de negocio y TransactionPolicy (fail-fast)
│   └── port/               lo que el núcleo necesita de afuera: TransactionRepository, ExecutionProvider,
│                           TransactionIdGenerator y sus tipos (ProviderOutcome, PageQuery, ...)
├── application/            casos de uso: ExecuteTransactionService, QueryTransactionsService, StalePendingSweeper
├── adapter/
│   ├── in/web/             REST: controller, DTOs, manejo de errores, header X-Trace-Id
│   ├── in/scheduling/      el disparador periódico del sweeper (el tiempo es la entrada, como HTTP lo es del controller)
│   ├── out/persistence/    PostgreSQL con JdbcClient
│   ├── out/provider/       cliente HTTP del proveedor, clasificación de respuestas, Resilience4j
│   └── out/id/             generador de UUIDv7
└── config/                 cableado de beans y propiedades
```

Los puertos de salida son interfaces que define el dominio y que implementan los adaptadores (inversión de
dependencias): el caso de uso pide "guarda esta transacción" o "ejecútala en el proveedor" sin saber que detrás hay
PostgreSQL o un `RestClient`. En `application` se aceptó `@Service` como única concesión a Spring; el dominio queda
completamente limpio.

El diseño completo está documentado antes de la implementación:

- **LLD:** [`docs/design/LLD-Transaction-Execution-API.pdf`](docs/design/LLD-Transaction-Execution-API.pdf): drivers,
  componentes, flujos, máquina de estados, modelo de datos, decisiones (ADR) y plan de pruebas.
- **Diagramas** (en formato draw.io; se abren en [app.diagrams.net](https://app.diagrams.net) o con la extensión de
  draw.io para IntelliJ o VS Code):
  - [`docs/diagrams/arquitectura.drawio`](docs/diagrams/arquitectura.drawio): componentes y capas.
  - [`docs/diagrams/secuencias.drawio`](docs/diagrams/secuencias.drawio): flujos de ejecución, replay e idempotencia.
  - [`docs/diagrams/despliegue-aws.drawio`](docs/diagrams/despliegue-aws.drawio): el demo en AWS.

## Pruebas

Las pruebas siguen la misma separación de la arquitectura: cuanto más adentro del hexágono, más rápidas y sin
infraestructura; cuanto más afuera, más reales.

| Nivel | Qué cubre | Cómo |
|-------|-----------|------|
| Unitarias de dominio | Cada borde de las reglas (1.00 ✗, 1.01 ✓, débito 10,000.00 ✓, 10,000.01 ✗, `mxn` ✗), todas las transiciones válidas e inválidas, montos con más de 2 decimales | JUnit y AssertJ, sin Spring |
| Unitarias de casos de uso | Cada resultado del proveedor y su estado final, que una regla violada no guarde ni llame al proveedor, replay, llave reutilizada, petición en curso, la carrera en el INSERT, el sweeper ganándole al request, el UPDATE final fallando | Fakes en memoria de los puertos |
| Web | El código HTTP de cada caso de la tabla de la sección API, el formato `ProblemDetail`, validaciones y headers | `@WebMvcTest` |
| Proveedor | Cada fila de la tabla de clasificación: aprobado, rechazos en 4xx y 5xx, 4xx y 5xx sin cuerpo, timeout, conexión cortada, conexión rechazada con su reintento, circuito abierto | WireMock |
| Persistencia | El esquema de Liquibase, el UNIQUE, el UPDATE condicional, filtros y paginación, el sweeper, el historial de eventos | PostgreSQL 18 real con Testcontainers |
| End-to-end | `POST` → `Location` → `GET` con Tomcat real, base real y el mismo mock de docker compose; el sweeper corriendo de verdad sobre un PENDING huérfano | `@SpringBootTest` |

En números: **153 tests**, con una cobertura de **98.5 % de líneas y 93 % de ramas** (JaCoCo); el dominio queda al
100 %. Además, la colección de Postman tiene **30 requests y 65 aserciones**, y es la misma que se corre contra local y
contra AWS.

Los tests de integración usan la anotación `@IntegrationTest`, que activa el perfil de pruebas y levanta PostgreSQL; por
eso `./mvnw verify` necesita Docker abierto.

## Limitaciones y evolución

Algunas cosas quedaron fuera del alcance a propósito, y otras no se pueden resolver con el contrato actual del
proveedor. Las dejo documentadas para que quede claro que son conocidas y cuál sería el siguiente paso:

- **Sin autenticación ni autorización.** Hoy `GET /transactions` devuelve transacciones de cualquier cuenta, porque el
  reto define todos los filtros como opcionales; lo encontré probando el API. En producción: OAuth2/JWT, el `accountId`
  tomado del token (un cliente solo ve sus cuentas aunque mande otra en el filtro) y la consulta global solo para un rol
  interno, con auditoría. Lo que ya está cuidado: la respuesta no expone la `Idempotency-Key` ni el hash, y Actuator
  solo expone `health`.
- **Conciliación de los UNKNOWN.** Con este contrato no es posible, porque el proveedor no ofrece una consulta de estado
  ni acepta una referencia nuestra: no hay forma de preguntarle por una transacción. Le pediría tres cosas: aceptar
  nuestro `id` como referencia, una llave de idempotencia (con ella, reintentar un timeout sería seguro y el UNKNOWN casi
  desaparecería) y un endpoint de estado o un archivo de liquidación. Con eso, un proceso conciliador movería los
  UNKNOWN a EXECUTED o FAILED.
- **Mejoras al sweeper.** Reintentar el UPDATE final ante una falla breve de la base de datos, para que menos
  transacciones terminen en UNKNOWN; marcar "llamada al proveedor iniciada" antes de llamarlo, para poder marcar FAILED
  con certeza cuando el proceso murió sin llamarlo; y una métrica de última ejecución para alertar si el job deja de
  correr.
- **Historial protegido por permisos.** `transaction_events` es de solo inserción por convención del código; en
  producción el usuario de la aplicación tendría solo `INSERT` y `SELECT` sobre esa tabla.
- **Escala de datos.** Paginación por keyset en lugar de offset, y particionado de la tabla por fecha con archivado de
  históricos.
- **Eventos a otros servicios.** Si otros sistemas necesitaran enterarse de cada transacción, patrón outbox: el evento se
  inserta en la misma transacción que el cambio de estado y se publica después.
- **Producción en AWS.** El demo es una sola instancia con docker compose. En producción: contenedores en ECS Fargate o
  EKS detrás de un ALB con TLS, RDS PostgreSQL Multi-AZ, contraseñas en Secrets Manager, la imagen construida en CI, y
  la exportación de trazas y métricas a un collector OpenTelemetry.

## Uso de IA

Usé IA (Claude Code) durante todo el desarrollo, y el reto pide documentar de qué forma, así que aquí va.

**Cómo trabajé.** Antes de escribir código cerré el diseño: el LLD, las decisiones y los diagramas esto como parte del ciclo
de ingenieria que estoy acostumbrado a llevar siempre que es posible, que no siempre lo es claro. Después construí el
servicio por partes: dominio, casos de uso, proveedor, persistencia, web, sweeper, empaquetado y despliegue. En
cada parte la IA trabaja como mi partner, mi ingeniero de software que rebota ideas conmigo y me ayuda a producir productos
con la misma calidad pero en un menor tiempo, sin embargo, siempre esta bajo mi supervision estricta, por ejemplo, durante
este ejercicio, una vez que tenia claro los documentos que habia que generar le pedi a cladue que me ayudara en parte con 
el codigo, o sobre todo con los tests, todo a partir de mi diseño; una vez hecho yo revisaba linea por linea lo que se genero, 
si algo no me quedaba claro o no era como lo esperaba, cambiaba lo que no me convencía, rebotaba la idea con claude y refactorizaba
 antes de cada commit. La IA aceleró la escritura del código lo que me vuelve mas productivo, sin embargo las decisiones 
el criterio, el diseño y la revisión fueron mías, y puedo explicar cada línea.

