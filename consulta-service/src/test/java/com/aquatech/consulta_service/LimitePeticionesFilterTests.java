package com.aquatech.consulta_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LimitePeticionesFilterTests {

    private static final class RelojManual extends Clock {
        private long ms = 1_000_000L;

        void avanzarSegundos(long segundos) {
            ms += segundos * 1000;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }
    }

    private MockHttpServletResponse llamar(LimitePeticionesFilter filtro, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/contadores/CONT-001/estado-cuenta");
        request.addHeader("X-Forwarded-For", ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void rechazaConCuatroCientosVeintinueveAlSuperarElLimite() throws Exception {
        LimitePeticionesFilter filtro = new LimitePeticionesFilter(3, 60, new RelojManual());

        for (int i = 0; i < 3; i++) {
            assertEquals(200, llamar(filtro, "1.1.1.1").getStatus());
        }
        MockHttpServletResponse rechazada = llamar(filtro, "1.1.1.1");

        assertEquals(429, rechazada.getStatus());
        assertNotNull(rechazada.getHeader("Retry-After"));
        assertTrue(rechazada.getContentAsString(StandardCharsets.UTF_8).contains("DEMASIADAS_PETICIONES"));
    }

    @Test
    void cuentaCadaIpPorSeparado() throws Exception {
        LimitePeticionesFilter filtro = new LimitePeticionesFilter(2, 60, new RelojManual());

        llamar(filtro, "1.1.1.1");
        llamar(filtro, "1.1.1.1");
        assertEquals(429, llamar(filtro, "1.1.1.1").getStatus());

        assertEquals(200, llamar(filtro, "2.2.2.2").getStatus());
    }

    @Test
    void reiniciaElConteoCuandoVenceLaVentana() throws Exception {
        RelojManual reloj = new RelojManual();
        LimitePeticionesFilter filtro = new LimitePeticionesFilter(1, 60, reloj);

        assertEquals(200, llamar(filtro, "1.1.1.1").getStatus());
        assertEquals(429, llamar(filtro, "1.1.1.1").getStatus());

        reloj.avanzarSegundos(61);

        assertEquals(200, llamar(filtro, "1.1.1.1").getStatus());
    }
}