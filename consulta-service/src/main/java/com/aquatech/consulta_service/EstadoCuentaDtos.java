package com.aquatech.consulta_service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class EstadoCuentaDtos {

    private EstadoCuentaDtos() {
    }

    public record EstadoCuenta(
            String codigoContador,
            String direccionServicio,
            String sector,
            BigDecimal saldoPendiente,
            List<ReciboPendiente> recibosPendientes) {
    }

    public record ReciboPendiente(
            String numeroRecibo,
            String periodo,
            LocalDate fechaEmision,
            BigDecimal monto,
            BigDecimal mora,
            BigDecimal total) {
    }

    public record ErrorRespuesta(String codigo, String mensaje) {
    }
}