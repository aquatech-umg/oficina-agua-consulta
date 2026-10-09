package com.aquatech.consulta_service;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aquatech.consulta_service.EstadoCuentaDtos.ErrorRespuesta;

@RestController
@RequestMapping("/api/v1/contadores")
public class EstadoCuentaController {

    private static final Pattern FORMATO_CODIGO = Pattern.compile("^[A-Za-z0-9-]{1,50}$");

    private final EstadoCuentaRepository repository;

    public EstadoCuentaController(EstadoCuentaRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{codigo}/estado-cuenta")
    public ResponseEntity<?> obtenerEstadoCuenta(@PathVariable String codigo) {
        if (!FORMATO_CODIGO.matcher(codigo).matches()) {
            return ResponseEntity.badRequest().body(new ErrorRespuesta(
                    "CODIGO_INVALIDO", "El código del contador tiene un formato inválido"));
        }
        return repository.buscarPorCodigo(codigo)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorRespuesta(
                        "CONTADOR_NO_ENCONTRADO", "No existe un contador con el código indicado")));
    }
}