package com.aquatech.consulta_service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.aquatech.consulta_service.EstadoCuentaDtos.EstadoCuenta;
import com.aquatech.consulta_service.EstadoCuentaDtos.ReciboPendiente;

@Repository
public class EstadoCuentaRepository {

    private final JdbcClient jdbc;
    private final Clock reloj;

    public EstadoCuentaRepository(DataSource dataSource, Clock reloj) {
        this.jdbc = JdbcClient.create(dataSource);
        this.reloj = reloj;
    }

    private record ContadorFila(long id, String codigo, String direccion, String sector) {
    }

    public Optional<EstadoCuenta> buscarPorCodigo(String codigo) {
        Optional<ContadorFila> contador = jdbc.sql("""
                SELECT id, numero_registro, direccion_servicio, sector
                FROM contadores
                WHERE numero_registro = :codigo
                """)
                .param("codigo", codigo)
                .query((rs, n) -> new ContadorFila(
                        rs.getLong("id"),
                        rs.getString("numero_registro"),
                        rs.getString("direccion_servicio"),
                        rs.getString("sector")))
                .optional();

        if (contador.isEmpty()) {
            return Optional.empty();
        }
        ContadorFila c = contador.get();

        LocalDate hoy = LocalDate.now(reloj);

        List<ReciboPendiente> recibos = jdbc.sql("""
                SELECT r.numero_recibo, l.periodo, r.fecha_emision, r.monto,
                       t.mora_porcentaje, t.mora_monto_fijo
                FROM recibos r
                JOIN lecturas l ON l.id = r.lectura_id
                LEFT JOIN tarifas t ON t.id = r.tarifa_id
                WHERE l.contador_id = :contadorId
                  AND r.estado = 'PENDIENTE'
                ORDER BY l.periodo
                """)
                .param("contadorId", c.id())
                .query((rs, n) -> {
                    LocalDate emision = rs.getObject("fecha_emision", LocalDate.class);
                    BigDecimal monto = rs.getBigDecimal("monto");
                    BigDecimal mora = CalculoMora.mora(
                            monto,
                            rs.getBigDecimal("mora_porcentaje"),
                            rs.getBigDecimal("mora_monto_fijo"),
                            emision,
                            hoy);
                    return new ReciboPendiente(
                            rs.getString("numero_recibo"),
                            YearMonth.from(rs.getObject("periodo", LocalDate.class)).toString(),
                            emision,
                            monto,
                            mora,
                            CalculoMora.total(monto, mora));
                })
                .list();

        BigDecimal saldo = recibos.stream()
                .map(ReciboPendiente::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return Optional.of(new EstadoCuenta(c.codigo(), c.direccion(), c.sector(), saldo, recibos));
    }
}