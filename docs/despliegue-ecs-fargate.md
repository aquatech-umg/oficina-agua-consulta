# AQ-131: Despliegue de consulta-service en AWS ECS Fargate

- **Fecha:** 20 de octubre de 2026
- **Responsable:** Pablo Mauricio López Carrillo
- **Proyecto:** Consulta pública (AQ-78)
- **Estado:** NO EJECUTADO. Bloqueado por políticas de la organización de AWS (ver Evidencia).

## Contexto

El criterio de AQ-131 es correr consulta-service en Fargate, responder una consulta real desde internet y poder apagarlo sin dejar recursos que cobren. Desplegar es opcional: la demo local es la que respalda la entrega.

## Evidencia del bloqueo

Se probaron dos cuentas de AWS de plan gratuito, creadas desde aws.amazon.com, ninguna pidió tarjeta. Resultado en ambas:

| Servicio | Operación probada | Resultado |
| --- | --- | --- |
| ECR | `CreateRepository` | Denegado por SCP |
| ECS | `ListClusters` | Denegado por SCP (primera cuenta) |
| SSM | `DescribeParameters` | Denegado por SCP (primera cuenta) |
| CloudWatch Logs | `DescribeLogGroups` | Permitido |

Ambas cuentas tienen una SCP que niega esos servicios. Una SCP la define el administrador de una organización y gana sobre cualquier permiso IAM, incluso con acceso total. No se verificó si es la misma organización o distintas. No se intentó evadirla.

Se desconoce quién administra esas organizaciones. Se consultó al equipo para saber si hay una vía oficial (cuenta con tarjeta, cuenta asignada por el docente u otra).

**Cierre de seguridad:** la clave de acceso creada se desactivó y eliminó, se borró el archivo local de credenciales y `aws sts get-caller-identity` responde `NoCredentials`.

### Actualización (20/10/2026)

- **Comprobado:** un compañero del equipo probó en su propia cuenta de AWS y ahí sí se pueden crear repositorios en ECR y usar ECS. El bloqueo es de las dos cuentas probadas arriba, no del servicio.
- **Hipótesis, no comprobada:** las dos cuentas sin tarjeta serían cuentas administradas por una organización externa, y por eso tienen la SCP. Es una suposición del equipo.
- **Vía acordada con el equipo:** crear una tercera cuenta con correo personal por el registro normal de aws.amazon.com, con método de pago, plan gratuito y región us-east-1. Medidas previstas antes de crear recursos: MFA en el usuario root y presupuesto de gasto cero.
- **Estado de la tercera cuenta:** creada, con el método de pago pendiente de verificación. AWS indica que la activación completa puede tardar hasta 24 horas. Aún no se ha probado ECR ni ECS.
- **Si la cuenta se habilita:** la primera prueba es `aws ecr create-repository`. Si aparece otra SCP, se detiene el intento y se entrega el despliegue local con los pasos documentados.

## Decisiones acordadas

- **Base de datos:** MariaDB como segundo contenedor dentro de la misma tarea, con imagen propia (esquema y datos de prueba de `oficina-agua-infra/db`). consulta-service se conecta a `localhost` y usa `dependsOn` con healthcheck. No se usa RDS (costo y tiempo) ni túnel (depende de una laptop).
- **Acceso:** sin balanceador. IP pública de la tarea y un security group que abre solo el puerto 8082.
- **Secretos:** Parameter Store (SecureString) referenciado desde la task definition; si no hay permisos, variables escritas en la consola. Nunca en el repo.

## Variables de entorno del servicio

| Variable | Origen en Fargate |
| --- | --- |
| `DB_HOST` | `localhost` (sidecar) |
| `DB_PORT` | `3306` (puerto interno del sidecar) |
| `DB_NAME` | `oficina_agua` |
| `DB_USER` | `lectura` |
| `DB_PASSWORD` | Secreto |
| `HCAPTCHA_SITEKEY` | Variable normal |
| `HCAPTCHA_SECRET` | Secreto |

Nota: en local la réplica se publica en el 3307. Dentro de la tarea el sidecar escucha en el 3306.

## Plan de pasos (NO PROBADO en AWS)

Requiere una cuenta sin SCP sobre ECR, ECS y SSM. Región `us-east-1`. Docker debe construir con `--platform linux/amd64`.

1. Verificar identidad: `aws sts get-caller-identity`.
2. Crear repositorios ECR: uno para consulta-service y otro para la imagen de MariaDB.
3. Login de Docker: `aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <cuenta>.dkr.ecr.us-east-1.amazonaws.com`.
4. Construir, etiquetar (tag versionado, no `latest`) y subir ambas imágenes.
5. Crear log group `/ecs/consulta-service` con retención de 7 días.
6. Guardar los secretos en Parameter Store o en la consola.
7. Registrar la task definition (Fargate, 2 contenedores, `dependsOn` con condición `HEALTHY`).
8. Crear cluster y security group (entrada solo TCP 8082).
9. Crear el servicio con 1 tarea e IP pública.
10. Probar: `GET /api/v1/contadores/{codigo}/estado-cuenta` con la cabecera `X-Captcha-Token`.

## Checklist de apagado

- [ ] Servicio a 0 tareas y eliminarlo
- [ ] Eliminar el cluster
- [ ] Eliminar los repositorios ECR
- [ ] Eliminar el log group
- [ ] Eliminar los parámetros de Parameter Store
- [ ] Eliminar el security group
- [ ] Desactivar y eliminar la clave de acceso del usuario IAM
- [ ] Borrar el archivo local de credenciales
- [ ] Revisar Billing al día siguiente

## Demo local (respaldo)

consulta-service en el puerto 8082 contra la réplica de `oficina-agua-infra`. Pruebas ya verificadas: sin `X-Captcha-Token` responde 403, con token falso 403 y con el token de prueba de hCaptcha 200.

### Verificación del 20/10/2026

- `consulta-service` se ejecutó en el puerto 8082 contra `db-replica` (con datos de prueba) y respondió `200` con el estado de cuenta de `CONT-001` usando el token y el secreto de prueba publicados por hCaptcha. Sin `X-Captcha-Token` y con token falso respondió `403`.
- **Conflicto de puertos en Windows:** si hay una MariaDB instalada en el equipo y escucha en el 3307, `host.docker.internal:3307` llega a esa base y no a la réplica (síntoma: `500` con `Access denied for user 'lectura'@'localhost'`). Solución usada, sin tocar archivos de los repos: ejecutar el contenedor en la red del compose (`--network oficina-agua-infra_default`) con `DB_HOST=db-replica` y `DB_PORT=3306`.