package com.aquatech.consulta_service;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Segunda barrera contra el scraping (la primera es el captcha en el BFF):
 * limita las peticiones por IP en una ventana de tiempo fija.
 *
 * La IP real se toma de X-Forwarded-For, porque este servicio recibe las
 * peticiones del BFF y no de los usuarios. El BFF debe SOBRESCRIBIR ese
 * encabezado con la IP del cliente. Si alguien llega directo a este servicio
 * puede inventarlo, por eso el servicio no debe ser accesible desde internet.
 */
@Component
public class LimitePeticionesFilter extends OncePerRequestFilter {

    private static final int MAX_IPS_ANTES_DE_LIMPIAR = 10_000;
    private static final int MAX_LARGO_IP = 64;
    private static final String CUERPO_429 =
            "{\"codigo\":\"DEMASIADAS_PETICIONES\","
            + "\"mensaje\":\"Demasiadas peticiones desde la misma IP. Intente de nuevo más tarde\"}";

    private record Ventana(long inicioMs, int cuenta) {
    }

    private final int maxPeticiones;
    private final long ventanaMs;
    private final Clock reloj;
    private final ConcurrentHashMap<String, Ventana> ventanas = new ConcurrentHashMap<>();

    @Autowired
    public LimitePeticionesFilter(
            @Value("${consulta.limite.max-peticiones:30}") int maxPeticiones,
            @Value("${consulta.limite.ventana-segundos:60}") long ventanaSegundos) {
        this(maxPeticiones, ventanaSegundos, Clock.systemUTC());
    }

    LimitePeticionesFilter(int maxPeticiones, long ventanaSegundos, Clock reloj) {
        this.maxPeticiones = maxPeticiones;
        this.ventanaMs = ventanaSegundos * 1000;
        this.reloj = reloj;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String ip = obtenerIp(request);
        long ahora = reloj.millis();

        Ventana ventana = ventanas.compute(ip, (clave, actual) -> {
            if (actual == null || ahora - actual.inicioMs() >= ventanaMs) {
                return new Ventana(ahora, 1);
            }
            return new Ventana(actual.inicioMs(), actual.cuenta() + 1);
        });
        limpiarVencidas(ahora);

        if (ventana.cuenta() > maxPeticiones) {
            long restanteSeg = Math.max(1, (ventana.inicioMs() + ventanaMs - ahora + 999) / 1000);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(restanteSeg));
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(CUERPO_429);
            return;
        }
        chain.doFilter(request, response);
    }

    private String obtenerIp(HttpServletRequest request) {
        String reenviada = request.getHeader("X-Forwarded-For");
        if (reenviada != null && !reenviada.isBlank()) {
            String primera = reenviada.split(",")[0].trim();
            if (!primera.isEmpty()) {
                return primera.length() > MAX_LARGO_IP ? primera.substring(0, MAX_LARGO_IP) : primera;
            }
        }
        return request.getRemoteAddr();
    }

    private void limpiarVencidas(long ahora) {
        if (ventanas.size() > MAX_IPS_ANTES_DE_LIMPIAR) {
            ventanas.entrySet().removeIf(e -> ahora - e.getValue().inicioMs() >= ventanaMs);
        }
    }
}