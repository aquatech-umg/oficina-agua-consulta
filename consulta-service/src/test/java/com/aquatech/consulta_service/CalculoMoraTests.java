package com.aquatech.consulta_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class CalculoMoraTests {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 22);

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    // --- Casos de la tarea ---

    @Test
    void vencidoConPorcentaje_redondeaHalfUp() {
        // 106.25 * 10 % = 10.625 -> 10.63
        BigDecimal mora = CalculoMora.mora(bd("106.25"), bd("10"), null,
                LocalDate.of(2025, 11, 25), HOY);
        assertEquals(bd("10.63"), mora);
        assertEquals(bd("116.88"), CalculoMora.total(bd("106.25"), mora));
    }

    @Test
    void vencidoConMontoFijo() {
        BigDecimal mora = CalculoMora.mora(bd("100.00"), null, bd("5.00"),
                LocalDate.of(2025, 11, 25), HOY);
        assertEquals(bd("5.00"), mora);
    }

    @Test
    void vencidoConPorcentajeYMontoFijo_seSuman() {
        // 180 * 5 % = 9.00, mas 9.00 fijo = 18.00 -> total 198.00
        BigDecimal mora = CalculoMora.mora(bd("180.00"), bd("5"), bd("9.00"),
                LocalDate.of(2025, 11, 25), HOY);
        assertEquals(bd("18.00"), mora);
        assertEquals(bd("198.00"), CalculoMora.total(bd("180.00"), mora));
    }

    @Test
    void vencidoSinMoraConfigurada_esCero() {
        BigDecimal mora = CalculoMora.mora(bd("180.00"), null, null,
                LocalDate.of(2025, 11, 25), HOY);
        assertEquals(bd("0.00"), mora);
    }

    @Test
    void noVencido_moraEsCero() {
        // emitido el 20/10/2026 vence el 10/11/2026; hoy es 22/10/2026
        BigDecimal mora = CalculoMora.mora(bd("180.00"), bd("10"), bd("9.00"),
                LocalDate.of(2026, 10, 20), HOY);
        assertEquals(bd("0.00"), mora);
    }

    @Test
    void laMoraSeCobraUnaSolaVez_noCrecePorMes() {
        BigDecimal reciente = CalculoMora.mora(bd("100.00"), bd("10"), null,
                LocalDate.of(2026, 9, 25), HOY);
        BigDecimal antiguo = CalculoMora.mora(bd("100.00"), bd("10"), null,
                LocalDate.of(2025, 1, 25), HOY);
        assertEquals(reciente, antiguo);
    }

    // --- Fecha de vencimiento (dia 10) ---

    @Test
    void emitidoDel1Al10_venceElDia10DelMismoMes() {
        assertEquals(LocalDate.of(2026, 10, 10), CalculoMora.fechaVencimiento(LocalDate.of(2026, 10, 1)));
        assertEquals(LocalDate.of(2026, 10, 10), CalculoMora.fechaVencimiento(LocalDate.of(2026, 10, 10)));
    }

    @Test
    void emitidoDespuesDel10_venceElDia10DelMesSiguiente() {
        assertEquals(LocalDate.of(2026, 11, 10), CalculoMora.fechaVencimiento(LocalDate.of(2026, 10, 11)));
        assertEquals(LocalDate.of(2025, 12, 10), CalculoMora.fechaVencimiento(LocalDate.of(2025, 11, 25)));
    }

    @Test
    void emitidoEnDiciembre_venceEnEneroDelAnioSiguiente() {
        assertEquals(LocalDate.of(2026, 1, 10), CalculoMora.fechaVencimiento(LocalDate.of(2025, 12, 25)));
    }

    // --- Bordes del dia 10 y del dia 11 ---

    @Test
    void elDia10EsUltimoDiaSinMora() {
        LocalDate emision = LocalDate.of(2026, 9, 25); // vence el 10/10/2026
        assertFalse(CalculoMora.estaAtrasado(emision, LocalDate.of(2026, 10, 10)));
        assertEquals(bd("0.00"), CalculoMora.mora(bd("100.00"), bd("10"), null, emision, LocalDate.of(2026, 10, 10)));
    }

    @Test
    void elDia11YaTieneMora() {
        LocalDate emision = LocalDate.of(2026, 9, 25); // vence el 10/10/2026
        assertTrue(CalculoMora.estaAtrasado(emision, LocalDate.of(2026, 10, 11)));
        assertEquals(bd("10.00"), CalculoMora.mora(bd("100.00"), bd("10"), null, emision, LocalDate.of(2026, 10, 11)));
    }
}