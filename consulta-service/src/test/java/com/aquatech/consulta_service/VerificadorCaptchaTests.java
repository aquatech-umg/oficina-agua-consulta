package com.aquatech.consulta_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.aquatech.consulta_service.VerificadorCaptcha.Resultado;
import com.sun.net.httpserver.HttpServer;

class VerificadorCaptchaTests {

    // Claves de prueba oficiales de hCaptcha (cuenta Publisher/Pro). No protegen contra bots.
    private static final String SECRET_PRUEBA = "0x0000000000000000000000000000000000000000";
    private static final String TOKEN_PRUEBA = "10000000-aaaa-bbbb-cccc-000000000001";
    private static final String URL_REAL = "https://api.hcaptcha.com/siteverify";

    private HttpServer servidor(int estado, String cuerpo, AtomicReference<String> recibido,
            AtomicReference<String> encabezado) throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/siteverify", intercambio -> {
            if (recibido != null) {
                recibido.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            if (encabezado != null) {
                encabezado.set(intercambio.getRequestHeaders().getFirst("Content-Type")
                        + " " + intercambio.getRequestMethod());
            }
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(estado, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        servidor.start();
        return servidor;
    }

    private Resultado probar(int estado, String cuerpo, String secret, String token) throws IOException {
        HttpServer servidor = servidor(estado, cuerpo, null, null);
        try {
            String url = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/siteverify";
            return new VerificadorCaptcha(secret, url, 2).verificar(token);
        } finally {
            servidor.stop(0);
        }
    }

    private static boolean hayRed() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("api.hcaptcha.com", 443), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void aceptaUnTokenConfirmadoPorHCaptcha() throws IOException {
        assertEquals(Resultado.VALIDO, probar(200, "{\"success\":true}", "0xSECRETO", "TOKEN"));
    }

    @Test
    void rechazaUnTokenInvalido() throws IOException {
        String cuerpo = "{\"success\": false, \"error-codes\": [\"invalid-input-response\"]}";
        assertEquals(Resultado.INVALIDO, probar(200, cuerpo, "0xSECRETO", "FAKE-TOKEN"));
    }

    @Test
    void rechazaUnTokenYaUsado() throws IOException {
        String cuerpo = "{\"success\":false,\"error-codes\":[\"already-seen-response\"]}";
        assertEquals(Resultado.INVALIDO, probar(200, cuerpo, "0xSECRETO", "TOKEN"));
    }

    @Test
    void unSecretInvalidoEsErrorDeConfiguracionYNoDejaPasar() throws IOException {
        String cuerpo = "{\"success\":false,\"error-codes\":[\"invalid-input-secret\"]}";
        assertEquals(Resultado.NO_DISPONIBLE, probar(200, cuerpo, "0xMALO", "TOKEN"));
    }

    @Test
    void noDejaPasarSiHCaptchaResponde500() throws IOException {
        assertEquals(Resultado.NO_DISPONIBLE, probar(500, "error", "0xSECRETO", "TOKEN"));
    }

    @Test
    void noDejaPasarSiLaRespuestaNoEsLegible() throws IOException {
        assertEquals(Resultado.NO_DISPONIBLE, probar(200, "esto no es json", "0xSECRETO", "TOKEN"));
    }

    @Test
    void noDejaPasarSiHCaptchaNoResponde() throws IOException {
        HttpServer servidor = servidor(200, "{\"success\":true}", null, null);
        String url = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/siteverify";
        servidor.stop(0);

        assertEquals(Resultado.NO_DISPONIBLE, new VerificadorCaptcha("0xSECRETO", url, 2).verificar("TOKEN"));
    }

    @Test
    void sinSecretNoDejaPasarANadie() throws IOException {
        assertEquals(Resultado.NO_DISPONIBLE, probar(200, "{\"success\":true}", "", "TOKEN"));
        assertEquals(Resultado.NO_DISPONIBLE, probar(200, "{\"success\":true}", null, "TOKEN"));
    }

    @Test
    void enviaUnFormularioCodificadoPorPost() throws IOException {
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicReference<String> encabezado = new AtomicReference<>();
        HttpServer servidor = servidor(200, "{\"success\":true}", cuerpo, encabezado);
        try {
            String url = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/siteverify";
            new VerificadorCaptcha("0xSE&CR=ETO", url, 2).verificar("tok en+con&simbolos");
        } finally {
            servidor.stop(0);
        }

        assertEquals("application/x-www-form-urlencoded POST", encabezado.get());
        assertEquals("secret=0xSE%26CR%3DETO&response=tok+en%2Bcon%26simbolos", cuerpo.get());
    }

    @Test
    void conLasClavesDePruebaOficialesHCaptchaAceptaElTokenDePrueba() {
        assumeTrue(hayRed(), "Sin acceso a api.hcaptcha.com: prueba omitida");

        assertEquals(Resultado.VALIDO,
                new VerificadorCaptcha(SECRET_PRUEBA, URL_REAL, 5).verificar(TOKEN_PRUEBA));
    }

    @Test
    void conLasClavesDePruebaOficialesUnTokenFalsoNoPasa() {
        assumeTrue(hayRed(), "Sin acceso a api.hcaptcha.com: prueba omitida");

        assertNotEquals(Resultado.VALIDO,
                new VerificadorCaptcha(SECRET_PRUEBA, URL_REAL, 5).verificar("FAKE-TOKEN"));
    }
}