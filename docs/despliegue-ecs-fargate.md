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

Cada cuenta pertenece a una organización distinta, con su propia SCP. Una SCP la define el administrador de la organización y gana sobre cualquier permiso IAM, incluso con acceso total. No se intentó evadirla.

Se desconoce quién administra esas organizaciones. Se consultó al equipo para saber si hay una vía oficial (cuenta con tarjeta, cuenta asignada por el docente u otra).

**Cierre de seguridad:** la clave de acceso creada se desactivó y eliminó, se borró el archivo local de credenciales y `aws sts get-caller-identity` responde `NoCredentials`.

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
