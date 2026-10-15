package com.aquatech.consulta_service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifica un token de hCaptcha contra https://api.hcaptcha.com/siteverify
 * (POST con formato application/x-www-form-urlencoded, segun la documentacion).
 *
 * El secret viene de la variable de entorno HCAPTCHA_SECRET y nunca se escribe
 * en el repositorio. Si no esta configurado, o si hCaptcha no responde bien, el
 * resultado es NO_DISPONIBLE: el servicio NO deja pasar a nadie.
 */
@Component
public class VerificadorCaptcha {

    public enum Resultado {
        /** hCaptcha confirmo el token. */
        VALIDO,
        /** hCaptcha rechazo el token (falso, vencido o ya usado). */
        INVALIDO,
        /** No se pudo verificar: sin secret, error de configuracion o hCaptcha caido. */
        NO_DISPONIBLE
    }

    private static final System.Logger LOG = System.getLogger(VerificadorCaptcha.class.getName());

    private static final Pattern SUCCESS = Pattern.compile("\"success\"\\s*:\\s*(true|false)");

    // Codigos de siteverify que indican un problema NUESTRO (configuracion), no del visitante.
    private static final List<String> ERRORES_DE_CONFIGURACION = List.of(
            "missing-input-secret",
            "invalid-input-secret",
            "not-using-dummy-passcode",
            "sitekey-secret-mismatch",
            "bad-request");

    private final String secret;
    private final URI urlVerificacion;
    private final Duration timeout;
    private final HttpClient http;

    public VerificadorCaptcha(
            @Value("${hcaptcha.secret:}") String secret,
            @Value("${hcaptcha.verify-url:https://api.hcaptcha.com/siteverify}") String urlVerificacion,
            @Value("${hcaptcha.timeout-segundos:5}") long timeoutSegundos) {
        this.secret = secret;
        this.urlVerificacion = URI.create(urlVerificacion);
        this.timeout = Duration.ofSeconds(timeoutSegundos);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public Resultado verificar(String token) {
        if (secret == null || secret.isBlank()) {
            LOG.log(System.Logger.Level.ERROR,
                    "HCAPTCHA_SECRET no esta configurado: se rechazan todas las consultas");
            return Resultado.NO_DISPONIBLE;
        }

        String cuerpo = "secret=" + codificar(secret) + "&response=" + codificar(token);
        HttpRequest peticion = HttpRequest.newBuilder(urlVerificacion)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                .build();

        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                LOG.log(System.Logger.Level.WARNING,
                        "hCaptcha respondio con estado {0}", respuesta.statusCode());
                return Resultado.NO_DISPONIBLE;
            }
            return interpretar(respuesta.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resultado.NO_DISPONIBLE;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "No se pudo contactar a hCaptcha: {0}", e.getClass().getSimpleName());
            return Resultado.NO_DISPONIBLE;
        }
    }

    private Resultado interpretar(String json) {
        Matcher m = SUCCESS.matcher(json);
        if (!m.find()) {
            return Resultado.NO_DISPONIBLE;
        }
        if (m.group(1).equals("true")) {
            return Resultado.VALIDO;
        }
        for (String codigo : ERRORES_DE_CONFIGURACION) {
            if (json.contains("\"" + codigo + "\"")) {
                LOG.log(System.Logger.Level.ERROR,
                        "hCaptcha reporta un error de configuracion: {0}", codigo);
                return Resultado.NO_DISPONIBLE;
            }
        }
        return Resultado.INVALIDO;
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }
}