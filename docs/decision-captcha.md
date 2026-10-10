# AQ-88: Elección del proveedor de captcha

- **Fecha:** 9 de octubre de 2026
- **Responsable:** Pablo Mauricio López Carrillo
- **Proyecto:** Consulta pública (AQ-78)

## Contexto

El captcha es la primera barrera contra el scraping del padrón. La segunda es el límite de peticiones por IP en el microservicio de consulta. El docente indicó dos opciones: reCAPTCHA v2 o hCaptcha.

## Comparación

Información consultada el 9/10/2026 en la documentación de cada proveedor.

| Criterio | reCAPTCHA v2 | hCaptcha |
|---|---|---|
| Alta de cuenta | Se administra desde Google Cloud (proyecto de Google Cloud) | Cuenta propia en dashboard.hcaptcha.com |
| Costo | 10,000 evaluaciones al mes gratis; para pasar ese límite hay que habilitar facturación | Plan Publisher gratuito, incluye la verificación en servidor (ver pendientes) |
| Al superar el límite | Sin facturación, reCAPTCHA devuelve error en las peticiones nuevas | No revisado |
| Verificación en el servidor | No revisada en detalle | POST con formato application/x-www-form-urlencoded a https://api.hcaptcha.com/siteverify |
| Claves de prueba | No revisadas | Publicadas en su documentación oficial |
| Desarrollo local | No revisado | No acepta localhost ni 127.0.0.1; se usa un nombre en el archivo hosts |

## Decisión

**Se elige hCaptcha.**

1. No depende de un proyecto de Google Cloud ni de una cuenta de facturación.
2. El plan gratuito es suficiente para el tráfico esperado de una oficina comunitaria de agua.
3. La verificación en el servidor es un solo POST, que Spring Boot puede hacer sin librerías adicionales.
4. Tiene claves de prueba oficiales para las pruebas automáticas.

## Riesgos y pendientes

- El dashboard muestra la etiqueta "Pro Publisher". Falta confirmar las condiciones exactas del plan y que no genere cobros.
- hCaptcha rechaza localhost: para probar en local hay que agregar una entrada en el archivo hosts.
- El captcha por sí solo no basta: falta implementar el límite de peticiones por IP.

## Claves

Claves de prueba oficiales de hCaptcha (públicas, no protegen contra bots; solo para pruebas):

| Dato | Valor |
|---|---|
| Sitekey | `10000000-ffff-ffff-ffff-000000000001` |
| Secret | `0x0000000000000000000000000000000000000000` |
| Token | `10000000-aaaa-bbbb-cccc-000000000001` |

Las claves reales están en el `.env` local de cada desarrollador y **no se versionan**. El archivo `.env.example` trae las claves de prueba.

## Fuentes

- https://docs.hcaptcha.com
- https://docs.cloud.google.com/recaptcha/docs/billing-information