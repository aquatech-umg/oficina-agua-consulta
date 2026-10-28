package com.aquatech.consulta_service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Regla de mora copiada del monolito (app/Models/Recibo.php), que no se modifica.
 * - El recibo vence el dia 10: del mismo mes si se emitio del 1 al 10,
 *   del mes siguiente si se emitio despues.
 * - Esta atrasado cuando hoy es posterior a ese dia 10.
 * - La mora se cobra una sola vez: monto fijo + monto * porcentaje / 100.
 * - Redondeo a 2 decimales HALF_UP.
 */
public final class CalculoMora {

    public static final ZoneId ZONA = ZoneId.of("America/Guatemala");
    public static final int DIA_LIMITE_PAGO = 10;

    private CalculoMora() {
    }

    public static LocalDate fechaVencimiento(LocalDate fechaEmision) {
        if (fechaEmision.getDayOfMonth() <= DIA_LIMITE_PAGO) {
            return fechaEmision.withDayOfMonth(DIA_LIMITE_PAGO);
        }
        return fechaEmision.plusMonths(1).withDayOfMonth(DIA_LIMITE_PAGO);
    }

    public static boolean estaAtrasado(LocalDate fechaEmision, LocalDate hoy) {
        return hoy.isAfter(fechaVencimiento(fechaEmision));
    }

    public static BigDecimal mora(BigDecimal monto, BigDecimal moraPorcentaje,
            BigDecimal moraMontoFijo, LocalDate fechaEmision, LocalDate hoy) {
        if (!estaAtrasado(fechaEmision, hoy)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal fija = moraMontoFijo == null ? BigDecimal.ZERO : moraMontoFijo;
        BigDecimal porcentaje = moraPorcentaje == null ? BigDecimal.ZERO : moraPorcentaje;
        BigDecimal porPorcentaje = monto.multiply(porcentaje).movePointLeft(2);
        return fija.add(porPorcentaje).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal total(BigDecimal monto, BigDecimal mora) {
        return monto.add(mora).setScale(2, RoundingMode.HALF_UP);
    }
}