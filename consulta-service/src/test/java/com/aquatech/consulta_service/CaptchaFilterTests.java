package com.aquatech.consulta_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.aquatech.consulta_service.VerificadorCaptcha.Resultado;

class CaptchaFilterTests {

    private static final String RUTA = "/api/v1/contadores/CONT-001/estado-cuenta";

    // Verificador falso: devuelve lo que le pidamos y anota el token que recibio.
    private static VerificadorCaptcha falso(Resultado resultado, AtomicReference<String> tokenRecibido) {
        return new VerificadorCaptcha("x", "http://127.0.0.1:1/siteverify", 1) {
            @Override
            public Resultado verificar(String token) {
                tokenRecibido.set(token);
                return resultado;
            }
        };
    }

    private MockHttpServletResponse llamar(CaptchaFilter filtro, String ruta, String token,
            MockFilterChain cadena) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ruta);
        if (token != null) {
            request.addHeader(CaptchaFilter.ENCABEZADO, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, cadena);
        return response;
    }

    @Test
    void sinEncabezadoRespondeCuatrocientosTresYNoLlamaALaCadena() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        MockFilterChain cadena = new MockFilterChain();

        MockHttpServletResponse respuesta =
                llamar(new CaptchaFilter(falso(Resultado.VALIDO, recibido)), RUTA, null, cadena);

        assertEquals(403, respuesta.getStatus());
        assertTrue(respuesta.getContentAsString(StandardCharsets.UTF_8).contains("CAPTCHA_INVALIDO"));
        assertNull(cadena.getRequest());
        assertNull(recibido.get());
    }

    @Test
    void tokenEnBlancoRespondeCuatrocientosTres() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        MockFilterChain cadena = new MockFilterChain();

        MockHttpServletResponse respuesta =
                llamar(new CaptchaFilter(falso(Resultado.VALIDO, recibido)), RUTA, "   ", cadena);

        assertEquals(403, respuesta.getStatus());
        assertNull(cadena.getRequest());
        assertNull(recibido.get());
    }

    @Test
    void tokenDemasiadoLargoSeRechazaSinConsultarAHCaptcha() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        MockFilterChain cadena = new MockFilterChain();

        MockHttpServletResponse respuesta = llamar(
                new CaptchaFilter(falso(Resultado.VALIDO, recibido)), RUTA, "a".repeat(5000), cadena);

        assertEquals(403, respuesta.getStatus());
        assertNull(cadena.getRequest());
        assertNull(recibido.get());
    }

    @Test
    void tokenValidoDejaPasarLaPeticion() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        MockFilterChain cadena = new MockFilterChain();

        MockHttpServletResponse respuesta =
                llamar(new CaptchaFilter(falso(Resultado.VALIDO, recibido)), RUTA, "abc", cadena);

        assertEquals(200, respuesta.getStatus());
        assertNotNull(cadena.getRequest());
        assertEquals("abc", recibido.get());
    }

    @Test
    void tokenRechazadoPorHCaptchaRespondeCuatrocientosTres() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        MockFilterChain cadena = new MockFilterChain();

        MockHttpServletResponse respuesta =
                llamar(new CaptchaFilter(falso(Resultado.INVALIDO, recibido)), RUTA, "abc", cadena);

        assertEquals(403, respuesta.getStatus());
        assertTrue(respuesta.getContentAsString(StandardCharsets.UTF_8).contains("CAPTCHA_INVALIDO"));
        assertNull(cadena.getRequest());
    }

    @Test
    void siNoSePuedeVerificarRespondeCincocientosTresYNoDejaPasar() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        MockFilterChain cadena = new MockFilterChain();

        MockHttpServletResponse respuesta =
                llamar(new CaptchaFilter(falso(Resultado.NO_DISPONIBLE, recibido)), RUTA, "abc", cadena);

        assertEquals(503, respuesta.getStatus());
        assertTrue(respuesta.getContentAsString(StandardCharsets.UTF_8).contains("CAPTCHA_NO_DISPONIBLE"));
        assertNull(cadena.getRequest());
    }

    @Test
    void lasRutasFueraDeApiNoPidenToken() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        MockFilterChain cadena = new MockFilterChain();

        MockHttpServletResponse respuesta = llamar(
                new CaptchaFilter(falso(Resultado.INVALIDO, recibido)), "/favicon.ico", null, cadena);

        assertEquals(200, respuesta.getStatus());
        assertNotNull(cadena.getRequest());
        assertNull(recibido.get());
    }
}