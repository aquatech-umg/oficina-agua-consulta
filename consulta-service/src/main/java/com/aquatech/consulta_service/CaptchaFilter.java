package com.aquatech.consulta_service;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Primera barrera contra el scraping: exige un token de hCaptcha valido en el
 * encabezado X-Captcha-Token. Se evalua DESPUES del limite por IP (Order 1),
 * asi quien abusa se corta antes de gastar llamadas a hCaptcha.
 *
 * La verificacion vive aqui y no solo en el BFF para que el servicio quede
 * protegido aunque se publique y alguien lo llame directo.
 */
@Component
@Order(2)
public class CaptchaFilter extends OncePerRequestFilter {

    static final String ENCABEZADO = "X-Captcha-Token";

    // Los tokens de hCaptcha son largos; este tope evita que nos manden basura enorme.
    private static final int MAX_LARGO_TOKEN = 4096;

    private static final String CUERPO_403 =
            "{\"codigo\":\"CAPTCHA_INVALIDO\",\"mensaje\":\"Debe resolver el captcha para consultar\"}";
    private static final String CUERPO_503 =
            "{\"codigo\":\"CAPTCHA_NO_DISPONIBLE\","
            + "\"mensaje\":\"No se pudo verificar el captcha. Intente de nuevo más tarde\"}";

    private final VerificadorCaptcha verificador;

    public CaptchaFilter(VerificadorCaptcha verificador) {
        this.verificador = verificador;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(ENCABEZADO);
        if (token == null || token.isBlank() || token.length() > MAX_LARGO_TOKEN) {
            responder(response, HttpStatus.FORBIDDEN, CUERPO_403);
            return;
        }

        switch (verificador.verificar(token.trim())) {
            case VALIDO -> chain.doFilter(request, response);
            case INVALIDO -> responder(response, HttpStatus.FORBIDDEN, CUERPO_403);
            case NO_DISPONIBLE -> responder(response, HttpStatus.SERVICE_UNAVAILABLE, CUERPO_503);
        }
    }

    private void responder(HttpServletResponse response, HttpStatus estado, String cuerpo)
            throws IOException {
        response.setStatus(estado.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(cuerpo);
    }
}