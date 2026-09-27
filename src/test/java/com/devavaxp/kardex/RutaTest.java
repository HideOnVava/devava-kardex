package com.devavaxp.kardex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RutaTest {

    @TempDir
    Path tmp;

    private Almacen almacen() {
        return new Almacen(tmp.resolve("datos"), tmp.resolve("no-hay-beta"));
    }

    @Test
    void laRutaMasRapidaCumpleTodasLasReglas() throws IOException {
        Kardex k = almacen().cargar();
        assertNull(Ruta.armar(k, 0));
        Ruta.Resultado r = Ruta.evaluar(k);
        assertTrue(r.errores().isEmpty(), r.errores().toString());
        assertEquals(0, r.faltan());
        assertTrue(r.semestres().stream().allMatch(s -> s.error() == null && s.creditos() <= 36));
        assertTrue(r.termina() <= Ruta.LIMITE, "termina en " + r.termina());
        assertEquals(54, k.datos.ruta.size()); // todas menos una de las dos optativas
    }

    @Test
    void terminarMasTardeAligeraLaCarga() throws IOException {
        Kardex k = almacen().cargar();
        Ruta.armar(k, 0);
        Ruta.Resultado rapida = Ruta.evaluar(k);
        int maxRapida = rapida.semestres().stream().mapToInt(Ruta.Semestre::creditos).max().orElseThrow();

        assertNull(Ruta.armar(k, rapida.termina() + 2));
        Ruta.Resultado ligera = Ruta.evaluar(k);
        assertTrue(ligera.errores().isEmpty(), ligera.errores().toString());
        assertEquals(0, ligera.faltan());
        assertTrue(ligera.termina() <= rapida.termina() + 2);
        assertTrue(ligera.semestres().stream().mapToInt(Ruta.Semestre::creditos).max().orElseThrow() < maxRapida);

        String aviso = Ruta.armar(k, 2); // imposible terminar en segundo semestre
        assertTrue(aviso.contains("lo antes posible es el " + rapida.termina() + "°"), aviso);
    }

    @Test
    void marcaLoQueRompeLasReglas() throws IOException {
        Kardex k = almacen().cargar();
        k.aprobar(k.materia("A11"), 90);
        k.planear(k.materia("A12"), 2);
        k.planear(k.materia("A13"), 2);   // su requisito va en el mismo semestre
        k.planear(k.materia("51D"), 3);   // requiere 60 % de créditos
        for (String c : new String[]{"B11", "C11", "D11", "E11", "F11", "C12", "D12", "E12", "F12"}) k.planear(k.materia(c), 4); // 41 cr
        Ruta.Resultado r = Ruta.evaluar(k);
        assertTrue(r.errores().get("A13").contains("Cálculo Integral"));
        assertFalse(r.errores().containsKey("A12"));
        assertTrue(r.errores().get("51D").contains("156"));
        assertTrue(r.semestres().stream().filter(s -> s.numero() == 4).findFirst().orElseThrow().error().contains("máximo es 36"));
    }

    @Test
    void lasReprobadasSeRecursanPrimero() throws IOException {
        Kardex k = almacen().cargar();
        k.aprobar(k.materia("A11"), 90);
        k.reprobar(k.materia("C12"));
        k.datos.semestre = 2;
        Ruta.armar(k, 0);
        assertEquals(3, k.datos.ruta.get("C12"));
        Ruta.Resultado r = Ruta.evaluar(k);
        assertEquals(30, r.semestres().get(0).cargaMaxima()); // con una materia en repetición
        assertTrue(r.errores().isEmpty(), r.errores().toString());
    }

    @Test
    void elPatioNoTocaLaHistoria() throws IOException {
        Almacen a = almacen();
        Kardex historia = a.cargar();
        historia.aprobar(historia.materia("A11"), 95);
        a.guardar(Almacen.HISTORIA, historia);

        Kardex patio = a.cargarPatio(historia);
        assertEquals(95, patio.calificacion(patio.materia("A11")));
        patio.aprobar(patio.materia("A12"), 0);
        patio.pendiente(patio.materia("A11"));
        Ruta.armar(patio, 0);
        a.guardar(Almacen.PATIO, patio);

        Kardex historiaOtraVez = a.cargar();
        assertEquals(95, historiaOtraVez.calificacion(historiaOtraVez.materia("A11")));
        assertEquals(Kardex.Estado.BLOQUEADA, historiaOtraVez.estado(historiaOtraVez.materia("A13")));
        assertTrue(historiaOtraVez.datos.ruta.isEmpty());
        Kardex patioOtraVez = a.cargarPatio(historiaOtraVez);
        assertEquals(Kardex.Estado.APROBADA, patioOtraVez.estado(patioOtraVez.materia("A12")));
        assertFalse(patioOtraVez.datos.ruta.isEmpty());
    }
}
