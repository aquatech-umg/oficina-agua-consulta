package com.aquatech.consulta_service;

import java.math.BigDecimal;
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

    public EstadoCuentaRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
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

        List<ReciboPendiente> recibos = jdbc.sql("""
                SELECT r.numero_recibo, l.periodo, r.fecha_emision, r.monto
                FROM recibos r
                JOIN lecturas l ON l.id = r.lectura_id
                WHERE l.contador_id = :contadorId
                  AND r.estado = 'PENDIENTE'
                ORDER BY l.periodo
                """)
                .param("contadorId", c.id())
                .query((rs, n) -> new ReciboPendiente(
                        rs.getString("numero_recibo"),
                        YearMonth.from(rs.getObject("periodo", LocalDate.class)).toString(),
                        rs.getObject("fecha_emision", LocalDate.class),
                        rs.getBigDecimal("monto")))
                .list();

        BigDecimal saldo = recibos.stream()
                .map(ReciboPendiente::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return Optional.of(new EstadoCuenta(c.codigo(), c.direccion(), c.sector(), saldo, recibos));
    }
}