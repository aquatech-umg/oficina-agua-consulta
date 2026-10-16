# oficina-agua-consulta

Servicio de consulta pública de la Oficina del Agua (Fase 2, bloque 1). Cualquier persona puede
consultar el estado de cuenta de un contador de agua, sin login ni registro. Es de solo lectura:
lee de la réplica de MariaDB, no de la instancia del monolito.

El contrato está en `consulta-service/openapi.yaml`.

## Endpoint

`GET /api/v1/contadores/{codigo}/estado-cuenta` (por ejemplo `CONT-001`) devuelve el saldo
pendiente y los recibos en estado PENDIENTE del contador.

Encabezado obligatorio: `X-Captcha-Token` (token de hCaptcha, de un solo uso).

| Estado | Código | Cuándo |
|---|---|---|
| 200 | | Estado de cuenta encontrado |
| 400 | `CODIGO_INVALIDO` | El código no cumple `^[A-Za-z0-9-]{1,50}$` |
| 403 | `CAPTCHA_INVALIDO` | Falta el token o hCaptcha lo rechazó |
| 404 | `CONTADOR_NO_ENCONTRADO` | No existe un contador con ese código |
| 429 | `DEMASIADAS_PETICIONES` | La misma IP superó el límite |
| 503 | `CAPTCHA_NO_DISPONIBLE` | No se pudo verificar el captcha; no se deja pasar a nadie |

## Protección contra el scraping del padrón

1. **Límite de peticiones por IP** (se evalúa primero). Configurable en `application.properties`:
   `consulta.limite.max-peticiones` (30 por defecto) y `consulta.limite.ventana-segundos` (60).
   La IP se toma de `X-Forwarded-For`, que el BFF debe enviar con la IP real del visitante.
   Este servicio no debe ser accesible desde internet: cualquiera que lo llame directo puede
   inventar ese encabezado.
2. **Captcha hCaptcha** (se evalúa después). El token se verifica contra
   `https://api.hcaptcha.com/siteverify`. Cada token sirve una sola vez.

## Ejecutar en local

Requisitos: Java 21 y la base encendida (`docker compose up -d` en `oficina-agua-infra`),
con los datos de prueba de `datos-prueba/datos-prueba.sql`.

El secret de hCaptcha se pasa por variable de entorno y nunca va en el repositorio. Sin `HCAPTCHA_SECRET` el servicio responde 503 a todas las consultas, y sin `DB_PASSWORD` no arranca. Para pruebas locales:
```powershell
cd consulta-service
$env:HCAPTCHA_SECRET = "0x0000000000000000000000000000000000000000"
$env:DB_PASSWORD = "<contraseña del usuario lectura de la réplica>"
.\mvnw spring-boot:run
```

El servicio queda en `http://localhost:8082`.

## Claves de hCaptcha

Claves de prueba oficiales (públicas, no protegen contra bots; solo para pruebas):

| Dato | Valor |
|---|---|
| Sitekey | `10000000-ffff-ffff-ffff-000000000001` |
| Secret | `0x0000000000000000000000000000000000000000` |
| Token | `10000000-aaaa-bbbb-cccc-000000000001` |

El archivo `.env.example` de la raíz trae esas claves. Las claves reales van solo en el `.env`
local de cada desarrollador, que Git ignora.

Para probar el servicio directo:

```powershell
curl.exe -i http://localhost:8082/api/v1/contadores/CONT-001/estado-cuenta
curl.exe -i -H "X-Captcha-Token: FAKE-TOKEN" http://localhost:8082/api/v1/contadores/CONT-001/estado-cuenta
curl.exe -i -H "X-Captcha-Token: 10000000-aaaa-bbbb-cccc-000000000001" http://localhost:8082/api/v1/contadores/CONT-001/estado-cuenta
```

Con el secret de prueba, las dos primeras responden 403 y la tercera 200.

## Imagen Docker

Construir (desde la raíz del repositorio):

```powershell
docker build -t consulta-service:local ./consulta-service
```

Variables de entorno que recibe el contenedor:

| Variable | Descripción |
|---|---|
| `HCAPTCHA_SITEKEY` | Clave pública de hCaptcha |
| `HCAPTCHA_SECRET` | Clave secreta de hCaptcha. Sin ella el servicio responde 503 |
| `DB_PASSWORD` | Contraseña del usuario `lectura` de la réplica. Obligatoria: sin ella el servicio no arranca |
| `DB_HOST` | Host de la base de datos. Desde Docker en Windows/Mac se usa `host.docker.internal` |

Copia `.env.example` a `.env` y agrega `DB_PASSWORD` (sin comillas). El `.env` no se sube al repositorio.

Correr, con las bases de `oficina-agua-infra` levantadas:

```powershell
docker run --rm --name consulta-service -p 8082:8082 --env-file .env -e DB_HOST=host.docker.internal consulta-service:local
```

Para probar con las claves de prueba de la sección anterior, pásalas con `-e HCAPTCHA_SITEKEY=... -e HCAPTCHA_SECRET=...`: los `-e` tienen prioridad sobre el `.env`. Con las claves reales de hCaptcha el token de prueba da 403, porque solo valida con el secret de prueba.

## Probar el widget en local: entrada del archivo `hosts`

hCaptcha no acepta `localhost` ni `127.0.0.1` como nombre de sitio. Para probar el widget en el
navegador hay que acceder por un nombre que apunte a la máquina local. Ejemplo tomado de la
documentación de hCaptcha: agregar esta línea al archivo
`C:\Windows\System32\drivers\etc\hosts` (requiere PowerShell como administrador):

```powershell
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "`n127.0.0.1 test.mydomain.com"
```

y abrir la página por ese nombre, por ejemplo `http://test.mydomain.com:3000/consulta`.
Se puede usar otro nombre en lugar de `test.mydomain.com`.

El servidor de desarrollo de Next.js (web-bff) solo acepta peticiones de desarrollo desde los
orígenes listados en `allowedDevOrigins` de `web-bff/next.config.ts` (en `oficina-agua-rag`).
Ese archivo ya incluye `test.mydomain.com`; si usas otro nombre, agrégalo ahí también.

## Pruebas automáticas

```powershell
cd consulta-service
.\mvnw test "-Dtest=VerificadorCaptchaTests,CaptchaFilterTests,LimitePeticionesFilterTests"
```

Dos pruebas de `VerificadorCaptchaTests` usan las claves de prueba oficiales contra
`api.hcaptcha.com`; si no hay conexión a internet se omiten solas.

## Problemas conocidos

Si el servicio responde 500 y el log dice `Access denied for user 'lectura'@'localhost'`, puede
haber otro MariaDB instalado en Windows ocupando el puerto 3307 (el de la réplica). Revisar con:

```powershell
Get-NetTCPConnection -LocalPort 3307 -State Listen | ForEach-Object { (Get-Process -Id $_.OwningProcess).ProcessName }
```

Si aparece `mysqld` además de `com.docker.backend`, detener ese servicio de Windows
(`Stop-Service MariaDB`, en PowerShell como administrador).