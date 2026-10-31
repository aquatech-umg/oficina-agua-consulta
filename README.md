Nombre: Pablo Mauricio López Carrillo
Carnet: [COMPLETAR]
Repositorio: https://github.com/aquatech-umg/oficina-agua-consulta
Modulo asignado: Consulta pública con captcha

# AquaTech · Fase 2 · Oficina del Agua

Documentación de la solución integrada de la Fase 2 y, en detalle, del módulo **Consulta pública con captcha** (`consulta-service`), desarrollado por Pablo.

**Participación en otros módulos**

- **AQ-134** (mora y total a pagar en la consulta pública): además de `consulta-service`, toqué el `web-bff` (Next.js) que vive en el repositorio `oficina-agua-rag` (PR #24 allá, PR #11 aquí).
- **AQ-131** (documentación de despliegue en AWS ECS Fargate): `docs/despliegue-ecs-fargate.md`. **El despliegue en AWS no se ejecutó** (las cuentas disponibles tenían una política que bloqueaba ECR, ECS y SSM); lo verificado es la demo local.

---

## 1. Contexto

La Oficina del Agua usa un **monolito heredado en Laravel** (AdminLTE 4 + MariaDB, repositorio `MCambara707/oficina-agua`) que gestiona clientes, contadores, tarifas, lecturas, recibos y pagos, con roles Administrador, Secretaria y Lector. **El monolito no se modifica en la Fase 2.**

Alrededor de él, el equipo AquaTech (organización `aquatech-umg`) construyó cuatro features nuevas más la infraestructura de datos:

| Parte | Repositorio | Responsable |
|---|---|---|
| Infraestructura (BD principal, réplica) | `oficina-agua-infra` | Marvin |
| **1. Consulta pública con captcha** | **`oficina-agua-consulta`** (este repo) | **Pablo** |
| 2. Chatbot con RAG (rag-service y web-bff Next.js) | `oficina-agua-rag` | Marvin (rag-service), Manuel (web-bff e historial) |
| 3. Envío de pagos por colas | `oficina-agua-pagos` | Gustavo |
| 4. Replicación asíncrona a NoSQL | `oficina-agua-replicacion` | Nayeli |

---

## 2. Arquitectura general

![Arquitectura Fase 2](arquitectura-fase2.png)

*Todo corre en Docker Compose local, salvo los sistemas de la columna "Fuera de Docker Compose" (hCaptcha, Ollama y los simuladores de Banco y ERP).*

### 2.1 Idea central

1. El **monolito Laravel** (`:8000`) lee y escribe en **`db-primary`** (MariaDB 11.4, `:3306`).
2. MariaDB **replica** a **`db-replica`** (`:3307`), de solo lectura, con un usuario `lectura` que únicamente puede hacer `SELECT`.
3. Los servicios nuevos **leen solo de la réplica**: ninguna feature puede dañar los datos del monolito ni competir con su carga de escritura.
4. Cada feature guarda lo suyo en su propia base (DynamoDB Local o Postgres con pgvector), nunca en la del monolito.
5. El navegador nunca habla directo con los servicios: pasa por el **web-bff** (Next.js), que oculta URLs internas y claves.

### 2.2 Componentes

| Componente | Tecnología / puerto | Qué hace |
|---|---|---|
| Monolito Laravel | PHP · `:8000` | Sistema heredado usado por cajeros y administración. No se toca. |
| `db-primary` | MariaDB 11.4 · `:3306` | Base que lee y escribe el monolito. |
| `db-replica` | MariaDB solo lectura · `:3307` | Réplica; fuente de datos de los servicios nuevos. |
| `consulta-service` | Spring Boot (Java 21) · `:8082` | **(Mi módulo)** Estado de cuenta público: hCaptcha, límite por IP y cálculo de mora. |
| Página `/consulta` | Next.js (`web-bff`) · `:3000` | **(Mi módulo)** Formulario con hCaptcha, muestra Monto, Mora y Total a pagar. |
| hCaptcha | Servicio externo | Verifica el token del captcha. |
| Widget de chat | Embebido en `/consulta` | Interfaz del chatbot. |
| `web-bff` | Next.js · `:3000` | Capa web única: `/consulta` y `/api/chat`. |
| `rag-service` | Spring Boot · `:8081` | Chatbot RAG: fragmenta, genera embeddings, busca en pgvector y consulta a Ollama. |
| Postgres + pgvector | `:5433` · vector(1024) | Embeddings del RAG. |
| Ollama | GPU local · `:11434` (`qwen3:8b`, `bge-m3`) | Modelo de lenguaje y embeddings, fuera de Docker Compose. |
| DynamoDB Local `chatbot-historial` | `:8010` | Historial de conversaciones. |
| Productor de pagos | Spring Boot · `:8083` | Detecta pagos nuevos en la réplica y los publica en RabbitMQ. |
| RabbitMQ | `:5672` · consola `:15672` | Colas `pagos.banco` y `pagos.erp` con TTL y DLX. |
| Consumidor de pagos | Spring Boot · `:8084` | Entrega a Banco y ERP, con reintentos. |
| DynamoDB Local `pagos-control` / `pagos-logs` | `:8012` | Estado y bitácora de envíos. |
| Simuladores Banco y ERP | API del docente (AWS) | Sistemas externos que reciben los pagos. |
| `sync-service` | Spring Boot · `:8085` | Copia clientes y pagos de la réplica a NoSQL. |
| DynamoDB Local (réplica NoSQL) | `:8011` | Destino de la replicación. |

### 2.3 Qué representa cada conexión del diagrama

| Conexión | Significado |
|---|---|
| Personal de la oficina → Monolito ("usa") | Cajeros y administración usan el monolito. |
| Monolito → `db-primary` ("lee y escribe") | Operación normal del monolito. |
| `db-primary` → `db-replica` (azul continua) | Replicación MariaDB. |
| Líneas azules punteadas hacia `db-replica` ("solo lectura") | `consulta-service`, `rag-service` (tarifas y contenido público), productor de pagos (pagos pendientes) y `sync-service`. |
| Ciudadano → `/consulta` ("navegador") | El ciudadano abre la página pública. |
| Ciudadano → widget ("chatea") | El ciudadano conversa con el chatbot. |
| `/consulta` → `consulta-service` (`/api/consulta`, BFF) | Next.js reenvía la consulta; el navegador no llama directo al servicio. |
| `consulta-service` → hCaptcha ("verifica el token") | Llamada externa para validar el captcha. |
| Widget → `web-bff` → `rag-service` (`POST /api/chat`) | Flujo del chat. |
| `rag-service` → Postgres/pgvector ("embeddings") | Guarda y busca fragmentos. |
| `rag-service` → Ollama (Feign `/api/embed`, `/api/chat`) | Llamadas al modelo. |
| `rag-service` → DynamoDB `chatbot-historial` ("historial") | Persistencia de conversaciones. |
| Productor → RabbitMQ ("publica") → Consumidor ("consume") | Flujo asíncrono de pagos. |
| Consumidor → Banco / ERP | Entrega a sistemas externos. |
| Consumidor → `pagos-logs` ("estado y logs") | Bitácora de envíos. |
| Productor → `pagos-control` ("marca pagos ya enviados") | Evita reenviar pagos. |
| `sync-service` → DynamoDB réplica ("diff + upsert") | Escribe solo lo que cambió. |

Leyenda del diagrama: gris = HTTP/escritura; azul continua = replicación; azul punteada = solo lectura a la réplica; punteada = llamada externa (Feign).

### 2.4 Las cuatro features en una línea

- **Consulta pública con captcha** (Pablo): el ciudadano ve su saldo y recibos pendientes sin login, protegido por hCaptcha y límite por IP.
- **Chatbot con RAG** (Marvin y Manuel): responde con tarifas y contenido público de la réplica como contexto.
- **Envío de pagos por colas** (Gustavo): pagos nuevos van por RabbitMQ al Banco y al ERP, con reintentos escalonados (2 a 32 s), `pagos.dlq`, reenvío con `POST /retry` e idempotencia con llave `EQA-AQT-######` (409 si ya existe).
- **Replicación a NoSQL** (Nayeli): `sync-service` copia la réplica a DynamoDB en segundo plano, por páginas y escribiendo solo diferencias (`POST /api/sync/resumen` responde 202; 409 si ya hay un job corriendo).

---

## 3. Cómo se ejecuta la solución

La base principal, la réplica y los scripts SQL (`db/`) están en `oficina-agua-infra`. Los datos de prueba de este módulo están en `datos-prueba/datos-prueba.sql`.

```powershell
# 1. Infraestructura (db-primary en :3306 y db-replica en :3307)
git clone https://github.com/aquatech-umg/oficina-agua-infra
cd oficina-agua-infra
docker compose up -d

# 2. Servicios de cada feature (cada uno en su repositorio, con su propio Dockerfile)
#    oficina-agua-consulta, oficina-agua-rag, oficina-agua-pagos, oficina-agua-replicacion
```

> **[VERIFICAR]** Marvin preparó un `docker-compose.equipo.yml` que levanta el equipo completo. Confirma en qué repositorio quedó y si ya se fusionó; si existe, pon aquí su comando (`docker compose -f docker-compose.equipo.yml up -d --build`). Si no existe, deja solo el procedimiento de arriba.

El módulo de este README se levanta de forma independiente (sección 4.4). Los secretos se pasan por variables de entorno desde un `.env` local que **no** se versiona.

---

## 4. Mi módulo: Consulta pública con captcha

### 4.1 Funcionalidad y objetivo

**Problema.** Los usuarios del agua necesitan saber cuánto deben sin ir a la oficina ni tener usuario. Una consulta pública sin protección invita a bots y a la enumeración masiva de contadores.

**Qué construí.** `consulta-service` (Spring Boot, Java 21, puerto `8082`): un servicio **de solo lectura** que lee de `db-replica`, no de la base del monolito.

```
GET /api/v1/contadores/{codigo}/estado-cuenta
Cabecera: X-Captcha-Token: <token de hCaptcha>
```

Respuesta (200): `codigoContador`, `direccionServicio`, `sector`, `saldoPendiente` y `recibosPendientes[]` con `numeroRecibo`, `periodo`, `fechaEmision`, `monto`, `mora` y `total`. El contrato está en `consulta-service/openapi.yaml`. Códigos de respuesta:

| Estado | Código de error | Cuándo |
|---|---|---|
| 200 | | Estado de cuenta encontrado |
| 400 | `CODIGO_INVALIDO` | El código no cumple `^[A-Za-z0-9-]{1,50}$` |
| 403 | `CAPTCHA_INVALIDO` | Falta el token o hCaptcha lo rechazó |
| 404 | `CONTADOR_NO_ENCONTRADO` | No existe un contador con ese código |
| 429 | `DEMASIADAS_PETICIONES` | La misma IP superó el límite |
| 503 | `CAPTCHA_NO_DISPONIBLE` | No se pudo verificar el captcha; no se deja pasar a nadie |

**Flujo**

1. El ciudadano abre `/consulta` (`ConsultaForm.tsx`), escribe el código del contador y resuelve el hCaptcha (solo se usa la *sitekey* pública).
2. La ruta del servidor `src/app/api/consulta/route.ts` (en `oficina-agua-rag/web-bff`) reenvía la petición a `consulta-service` con el token en `X-Captcha-Token` y la IP del visitante en `X-Forwarded-For`. **[VERIFICAR a qué URL/variable de entorno llama esa ruta]**
3. `LimitePeticionesFilter` (se evalúa primero) limita las peticiones por IP: 30 por ventana de 60 s por defecto (`consulta.limite.max-peticiones` y `consulta.limite.ventana-segundos`). La IP sale de `X-Forwarded-For`, que el BFF envía con la IP real del visitante. Si se supera: **429**.
4. `CaptchaFilter` + `VerificadorCaptcha` (se evalúan después) validan el token contra `https://api.hcaptcha.com/siteverify` **en el servidor**, con el secret. Cada token sirve una sola vez. Si falla: **403**; si hCaptcha no responde: **503** (no se deja pasar a nadie).
5. `EstadoCuentaController` → `EstadoCuentaRepository` leen la réplica.
6. `CalculoMora` calcula mora y total de cada recibo y el saldo.

**Regla de mora (AQ-134).** Replica la regla real del monolito (`app/Models/Recibo.php`) sin modificarlo:

- El pago **vence el día 10**: un recibo emitido del 1 al 10 vence el 10 del mismo mes; uno emitido después del 10 vence el 10 del mes siguiente.
- Un recibo está **atrasado** solo si está `PENDIENTE` y hoy es posterior al fin del día 10.
- `mora = mora_monto_fijo + monto × mora_porcentaje / 100` (valores nulos cuentan como 0), **una sola vez**.
- `total = monto + mora`; el `saldoPendiente` es la suma de los totales.
- Redondeo `HALF_UP` a 2 decimales, zona horaria `America/Guatemala`.

### 4.2 Integración y responsabilidades

| Se comunica con | Para qué | Por qué esa frontera |
|---|---|---|
| `db-replica` (solo lectura) | Leer contadores y recibos | El monolito no se modifica y el servicio no puede escribir; el usuario `lectura` solo tiene `SELECT`. |
| hCaptcha (externo) | Verificar el token | Se valida en el servidor: el secret nunca llega al navegador. |
| `web-bff` Next.js (`oficina-agua-rag`) | Formulario y ruta `/api/consulta` | El navegador no habla directo con el servicio ni conoce su URL interna. |

- Next.js **no accede a la base de datos**: solo llama a la API.
- Configuración y secretos **solo por variables de entorno** (`DB_HOST`, `DB_PORT`, `DB_PASSWORD`, `HCAPTCHA_SITEKEY`, `HCAPTCHA_SECRET`) y `Dockerfile` de dos etapas (AQ-118): compila con Maven + JDK 21 y ejecuta solo con JRE 21 como usuario sin privilegios.
- `consulta-service` **no debe ser accesible desde internet**: cualquiera que lo llamara directo podría inventar el encabezado `X-Forwarded-For` y evadir el límite por IP. Solo el BFF debe poder alcanzarlo.
- El servicio es independiente: si se satura la consulta pública, no afecta al chatbot ni a los pagos.

### 4.3 Decisiones técnicas

**Decisión principal: reproducir la regla de mora del monolito en un servicio aparte, con pruebas y un reloj configurable, en lugar de modificar el monolito.**

- *Alternativa: agregar la consulta pública al monolito.* Descartada: la Fase 2 exige no tocarlo, y un endpoint público dentro del sistema de caja expone justo lo que se quiere proteger.
- *Alternativa: copiar un cálculo "aproximado".* Descartada: un ciudadano vería un monto distinto al que le cobra la oficina. Por eso la regla se tomó de `Recibo.php` y se corrigió cuando se detectó que una versión previa suponía "15 días de gracia" (la regla real es el día 10).
- *Reloj configurable:* `CalculoMora` recibe la fecha actual como dependencia, así se prueba "antes/después del día 10" sin depender del día en que corre el test (32 pruebas unitarias, 11 de `CalculoMora`).

**Otras decisiones**

- **Leer de la réplica y no de la principal:** la consulta pública es tráfico anónimo y potencialmente alto; no debe competir con las escrituras del monolito.
- **Validar hCaptcha en el servidor:** validarlo solo en el navegador permitiría saltarse el widget llamando a la API directamente; el secret nunca sale del servidor.
- **Filtros de Spring para captcha y límite por IP:** se ejecutan antes de tocar la base de datos, así una petición inválida no cuesta una consulta, y el controlador queda solo con lógica de negocio.
- **Variables de entorno para secretos:** nada sensible en el repositorio.

### 4.4 Instrucciones para probar

**Requisitos previos**

- Docker y Docker Compose; Java 21 si se corre sin Docker.
- Base encendida: `docker compose up -d` en `oficina-agua-infra`, con los datos de `datos-prueba/datos-prueba.sql` cargados.
- Contadores de prueba: `CONT-001` (pruebas de captcha y errores) y `CONT-025` (12 recibos pendientes, saldo Q1,662.12 en la base de pruebas del equipo).
- Claves de prueba **oficiales y públicas** de hCaptcha (no protegen contra bots, solo sirven para pruebas):

| Dato | Valor |
|---|---|
| Sitekey | `10000000-ffff-ffff-ffff-000000000001` |
| Secret | `0x0000000000000000000000000000000000000000` |
| Token | `10000000-aaaa-bbbb-cccc-000000000001` |

- Las claves **reales** de hCaptcha y la contraseña del usuario `lectura` van solo en el `.env` local (ignorado por Git). `DB_PASSWORD` es obligatoria: sin ella el servicio no arranca; sin `HCAPTCHA_SECRET` responde 503 a todo.

**Levantar el servicio** (elige una forma)

```powershell
# A) Directo con Maven
cd consulta-service
$env:HCAPTCHA_SECRET = "<secret de prueba de la tabla>"
$env:DB_PASSWORD = "<contraseña del usuario lectura de la réplica>"
.\mvnw spring-boot:run

# B) Imagen Docker, en la red de la infraestructura
docker build -t consulta-service:local ./consulta-service
docker run --rm --name consulta-service -p 8082:8082 --network oficina-agua-infra_default --env-file .env -e DB_HOST=db-replica -e DB_PORT=3306 -e HCAPTCHA_SECRET=<secret de prueba> consulta-service:local
```

El servicio queda en `http://localhost:8082`. Dentro de la red de Docker la réplica es `db-replica:3306`; el `3307` es solo el puerto publicado al host (y un MariaDB instalado en Windows puede ocupar ese puerto, ver "Problemas conocidos" abajo).

**Pruebas del servicio directo** (resultado esperado en la columna derecha)

```powershell
curl.exe -i http://localhost:8082/api/v1/contadores/CONT-001/estado-cuenta
curl.exe -i -H "X-Captcha-Token: FAKE-TOKEN" http://localhost:8082/api/v1/contadores/CONT-001/estado-cuenta
curl.exe -i -H "X-Captcha-Token: 10000000-aaaa-bbbb-cccc-000000000001" http://localhost:8082/api/v1/contadores/CONT-001/estado-cuenta
```

| # | Prueba | Resultado esperado |
|---|---|---|
| 1 | Sin cabecera `X-Captcha-Token` | **403** `CAPTCHA_INVALIDO` |
| 2 | Token falso (`FAKE-TOKEN`) | **403** `CAPTCHA_INVALIDO` |
| 3 | Token de prueba con el secret de prueba | **200** con `saldoPendiente` y `recibosPendientes` (monto, mora y total por recibo) |
| 4 | Código con caracteres no permitidos | **400** `CODIGO_INVALIDO` |
| 5 | Contador inexistente | **404** `CONTADOR_NO_ENCONTRADO` |
| 6 | Más de 30 peticiones en 60 s desde la misma IP | **429** `DEMASIADAS_PETICIONES` hasta que pase la ventana |

Con las claves reales de hCaptcha el token de prueba da 403: solo valida con el secret de prueba.

**Prueba de la pantalla web** (`/consulta`, en `oficina-agua-rag/web-bff`)

1. hCaptcha no acepta `localhost`: agrega un nombre al archivo `hosts` (PowerShell como administrador). Ese nombre ya está en `allowedDevOrigins` de `web-bff/next.config.ts`.
   ```powershell
   Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "`n127.0.0.1 test.mydomain.com"
   ```
2. Levanta el web-bff. **[VERIFICAR comando, probablemente `npm install` y `npm run dev` dentro de `oficina-agua-rag/web-bff`]**
3. Abre `http://test.mydomain.com:3000/consulta`, escribe `CONT-025` y resuelve el captcha.
4. **Resultado esperado:** tabla de recibos con **Monto, Mora y Total a pagar**, y el saldo pendiente.

**Pruebas automáticas** (32 pruebas unitarias, 11 de ellas de `CalculoMora`)

```powershell
cd consulta-service
.\mvnw test
```

Dos pruebas de `VerificadorCaptchaTests` llaman a `api.hcaptcha.com` con las claves de prueba; sin internet se omiten solas.

**Problemas conocidos.** Si el servicio responde 500 con `Access denied for user 'lectura'@'localhost'`, puede haber otro MariaDB de Windows ocupando el puerto 3307. Revisa con:

```powershell
Get-NetTCPConnection -LocalPort 3307 -State Listen | ForEach-Object { (Get-Process -Id $_.OwningProcess).ProcessName }
```

Si aparece `mysqld` además de `com.docker.backend`, detén ese servicio de Windows (`Stop-Service MariaDB`, como administrador).

---

## 5. Seguridad

- No se publican contraseñas, claves, tokens ni archivos `.env`; `.gitignore` excluye el `.env` local.
- `.env.example` contiene solo placeholders.
- Solo la *sitekey* de hCaptcha es pública; el *secret* vive únicamente en el servidor.
- Los servicios leen la réplica con un usuario de solo lectura (`SELECT`).
