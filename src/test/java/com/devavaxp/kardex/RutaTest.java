package com.devavaxp.kardex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        int pronto = Ruta.masPronto(k);
        assertNull(Ruta.armar(k, 0));
        Ruta.Resultado r = Ruta.evaluar(k);
        assertEquals(pronto, r.termina());
        assertTrue(r.errores().isEmpty(), r.errores().toString());
        assertEquals(0, r.faltan());
        assertTrue(r.semestres().stream().allMatch(s -> s.error() == null && s.creditos() <= 36));
        assertTrue(r.termina() <= Ruta.LIMITE, "termina en " + r.termina());
        assertEquals(52, k.datos.ruta.size()); // todas menos una optativa, servicio social y complementarias
    }

    @Test
    void alIngresarVanLasSeisDePrimerSemestre() throws IOException {
        Kardex k = almacen().cargar();
        Ruta.armar(k, 12);
        Ruta.Semestre primero = Ruta.evaluar(k).semestres().get(0);
        assertEquals(1, primero.numero());
        assertEquals(Set.of("A11", "B11", "C11", "D11", "E11", "F11"),
                primero.materias().stream().map(Kardex.Materia::clave).collect(Collectors.toSet()));

        k.planear(k.materia("F12"), 1);   // otra materia en primero
        k.planear(k.materia("C11"), 2);   // una de primero, después
        Ruta.Resultado r = Ruta.evaluar(k);
        assertTrue(r.errores().get("F12").contains("primer semestre"));
        assertTrue(r.errores().get("C11").contains("primer semestre"));
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
        assertEquals(2, k.datos.ruta.get("C12")); // no cursa nada ahora: la ruta arranca en este semestre
        k.cursar(k.materia("B11"), null);
        Ruta.armar(k, 0);
        assertEquals(3, k.datos.ruta.get("C12")); // ya cursa algo: arranca en el siguiente
        Ruta.Resultado r = Ruta.evaluar(k);
        assertEquals(30, r.semestres().get(0).cargaMaxima()); // con una materia en repetición
        assertTrue(r.errores().isEmpty(), r.errores().toString());
    }

    @Test
    void servicioSocialYComplementariasNoSeCursanNiVanEnLaRuta() throws IOException {
        Kardex k = almacen().cargar();
        assertTrue(k.cursar(k.materia("COMP"), null).contains("no se cursa"));
        assertTrue(k.horario().isEmpty());

        k.planear(k.materia("S1S"), 3); // aunque estén en una ruta guardada, no cuentan
        Ruta.armar(k, 0);
        assertFalse(k.datos.ruta.containsKey("S1S"));
        assertFalse(k.datos.ruta.containsKey("COMP"));
        Ruta.Resultado r = Ruta.evaluar(k);
        assertEquals(0, r.faltan());
        assertTrue(r.errores().isEmpty(), r.errores().toString()); // residencia no las espera
        assertTrue(k.datos.ruta.containsKey("R19"));

        // En Principal, residencia sí las pide aprobadas.
        assertTrue(k.bloqueo(k.materia("R19")).contains("Servicio Social"));
    }

    @Test
    void cargaMinimaDe18SalvoAlFinal() throws IOException {
        Kardex k = almacen().cargar();
        k.plan.stream().filter(m -> m.semestre() == 1 && !m.seAcredita()).forEach(m -> k.aprobar(m, 90));
        k.datos.semestre = 2;
        k.planear(k.materia("A12"), 2);                                                  // 5 cr y aún faltan más
        for (String c : new String[]{"B12", "C12", "D12", "E12"}) k.planear(k.materia(c), 3); // 19 cr
        k.planear(k.materia("F12"), 4);                                                  // 5 cr, pero es el final
        Ruta.Resultado r = Ruta.evaluar(k);
        assertTrue(r.semestres().get(0).error().contains("carga mínima"));
        assertNull(r.semestres().get(1).error());
        assertNull(r.semestres().get(2).error());

        // "Terminar en el 12°" reparte la carga sin bajar del mínimo (salvo el último semestre).
        Kardex z = new Almacen(tmp.resolve("z"), tmp.resolve("no-hay-beta")).cargar();
        assertNull(Ruta.armar(z, 12));
        List<Ruta.Semestre> ss = Ruta.evaluar(z).semestres();
        assertTrue(ss.stream().allMatch(s -> s.error() == null));
        for (int i = 0; i < ss.size() - 1; i++) assertTrue(ss.get(i).creditos() >= Kardex.CARGA_MINIMA, ss.get(i).toString());
    }

    @Test
    void residenciaConLasMateriasQueQuepanEnLaCarga() throws IOException {
        Kardex h = almacen().cargar();
        h.plan.stream().filter(m -> !Set.of("R19", "C16", "F18", "41D", "31D").contains(m.clave())).forEach(m -> h.aprobar(m, 90));
        assertNull(h.cursar(h.materia("R19"), null));
        for (String c : new String[]{"C16", "F18", "41D", "31D"}) assertNull(h.cursar(h.materia(c), null)); // 10 + 19 = 29
        assertEquals(29, h.carga());
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
