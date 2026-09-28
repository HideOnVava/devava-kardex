package com.devavaxp.kardex;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KardexTest {

    @TempDir
    Path tmp;

    private Kardex nuevo() throws IOException {
        return new Almacen(tmp.resolve("datos"), tmp.resolve("no-hay-beta")).cargar();
    }

    @Test
    void planDe260CreditosConUnaOptativa() throws IOException {
        Kardex k = nuevo();
        int total = k.plan.stream().mapToInt(Kardex.Materia::creditos).sum();
        int optativa = k.plan.stream().filter(Kardex.Materia::optativa).mapToInt(Kardex.Materia::creditos).min().orElseThrow();
        assertEquals(Kardex.CREDITOS_TOTALES, total - optativa);
        Set<String> claves = k.plan.stream().map(Kardex.Materia::clave).collect(Collectors.toSet());
        k.plan.forEach(m -> assertTrue(claves.containsAll(m.requisitos()), m.clave()));
        Set<Long> celdas = new HashSet<>();
        for (Kardex.Materia m : k.plan)
            for (int c = 0; c < m.ancho(); c++) assertTrue(celdas.add((m.semestre() + c) * 100L + m.fila()), "celda repetida " + m.clave());
    }

    @Test
    void lasFlechasNoCruzanTarjetasNiSeEnciman() throws IOException {
        Kardex k = nuevo();
        List<Flechas.Flecha> flechas = Flechas.trazar(k.plan);
        // Una flecha por requisito, salvo los de servicio social y complementarias (van como nota).
        assertEquals(k.plan.stream().flatMap(m -> m.requisitos().stream())
                .filter(r -> !k.materia(r).seAcredita()).count(), flechas.size());
        for (Flechas.Flecha f : flechas) {
            List<double[]> p = f.puntos();
            for (int i = 1; i < p.size(); i++) {
                double[] a = p.get(i - 1), b = p.get(i);
                assertTrue(a[0] == b[0] || a[1] == b[1], "tramo diagonal en " + f);
                for (Kardex.Materia m : k.plan) {
                    double x0 = Flechas.x(m.semestre()) + 1, x1 = x0 + Flechas.ancho(m.ancho()) - 2;
                    double y0 = Flechas.y(m.fila()) + 1, y1 = y0 + Flechas.H - 2;
                    boolean cruza = Math.max(a[0], b[0]) > x0 && Math.min(a[0], b[0]) < x1
                            && Math.max(a[1], b[1]) > y0 && Math.min(a[1], b[1]) < y1;
                    assertFalse(cruza, f.desde() + "→" + f.hacia() + " atraviesa " + m.clave());
                }
            }
        }
        for (Flechas.Flecha f : flechas)
            for (Flechas.Flecha g : flechas)
                if (f != g && !f.desde().equals(g.desde()) && !f.hacia().equals(g.hacia()))
                    assertFalse(seEnciman(f, g), f.desde() + "→" + f.hacia() + " se encima con " + g.desde() + "→" + g.hacia());
    }

    private static boolean seEnciman(Flechas.Flecha f, Flechas.Flecha g) {
        for (int i = 1; i < f.puntos().size(); i++)
            for (int j = 1; j < g.puntos().size(); j++) {
                double[] a = f.puntos().get(i - 1), b = f.puntos().get(i), c = g.puntos().get(j - 1), d = g.puntos().get(j);
                boolean horizontales = a[1] == b[1] && c[1] == d[1] && Math.abs(a[1] - c[1]) < 0.5;
                boolean verticales = a[0] == b[0] && c[0] == d[0] && Math.abs(a[0] - c[0]) < 0.5;
                int eje = horizontales ? 0 : 1;
                if ((horizontales || verticales)
                        && Math.min(Math.max(a[eje], b[eje]), Math.max(c[eje], d[eje]))
                        - Math.max(Math.min(a[eje], b[eje]), Math.min(c[eje], d[eje])) > 0.5) return true;
            }
        return false;
    }

    @Test
    void reglasDelTecNM() throws IOException {
        Kardex k = nuevo();
        Kardex.Materia residencia = k.materia("R19"), servicio = k.materia("S1S");
        String razon = k.bloqueo(residencia);
        assertTrue(razon.contains("Servicio Social") && razon.contains("Actividades Complementarias") && razon.contains("80 %"), razon);
        assertTrue(k.bloqueo(servicio).contains("70 %"));
        assertEquals(Kardex.Estado.DISPONIBLE, k.estado(k.materia("A11")));
        assertEquals(Kardex.Estado.BLOQUEADA, k.estado(k.materia("A12")));

        k.plan.stream().filter(m -> m != residencia).forEach(m -> k.aprobar(m, 90));
        assertNull(k.bloqueo(residencia));
        assertEquals(90.0, k.promedio());

        Kardex c = nuevo();
        assertEquals(36, c.cargaMaxima());
        c.reprobar(c.materia("A11"));
        assertEquals(30, c.cargaMaxima());
        assertNull(c.cursar(c.materia("A11"), null));
        c.reprobar(c.materia("A11"));
        assertEquals(20, c.cargaMaxima());
        c.cursar(c.materia("B11"), null);
        c.reprobar(c.materia("B11"));
        c.cursar(c.materia("B11"), null);
        c.reprobar(c.materia("B11"));
        assertEquals(10, c.cargaMaxima());
        assertTrue(c.cursar(c.materia("C11"), null).contains("curso especial"));
    }

    @Test
    void noDejaCruzarHorarios() throws IOException {
        Kardex k = nuevo();
        List<Kardex.Materia> primero = k.plan.stream().filter(m -> m.semestre() == 1 && !k.grupos(m).isEmpty()).toList();
        for (Kardex.Materia a : primero)
            for (Kardex.Materia b : primero)
                for (Kardex.Grupo ga : k.grupos(a))
                    for (Kardex.Grupo gb : k.grupos(b)) {
                        if (a == b || !seCruzan(ga, gb)) continue;
                        assertNull(k.cursar(a, ga.letra()));
                        assertTrue(k.cursar(b, gb.letra()).startsWith("Se cruza con " + a.nombre()));
                        assertEquals(ga, k.grupoActual(a));
                        return;
                    }
        throw new AssertionError("La oferta no tiene dos grupos de primer semestre a la misma hora");
    }

    private static boolean seCruzan(Kardex.Grupo a, Kardex.Grupo b) {
        return Kardex.clases(a).stream().anyMatch(x -> Kardex.clases(b).stream()
                .anyMatch(y -> x.dia() == y.dia() && x.inicio() < y.fin() && y.inicio() < x.fin()));
    }

    @Test
    void importaElAvanceDeLaBetaSinTocarla() throws IOException {
        Path beta = Files.createDirectories(tmp.resolve("KardexITL_Data"));
        Files.writeString(beta.resolve("reticula.json"), """
                [
                 {"clave":"A11","estado":"APROBADA","calificacion":95,"vecesReprobada":0,"grupos":[]},
                 {"clave":"B11","estado":"CURSANDO","calificacion":0,"vecesReprobada":1,"grupos":[
                   {"letra":"A","catedratico":"PROFE X","horarios":{"miercoles":"07:00-08:00 (19A)","lunes":"07:00-08:00 (19A)","martes":"-"}},
                   {"letra":"B","catedratico":"PROFE X","horarios":{"miercoles":"09:00-10:00 (19B)"}}]},
                 {"clave":"C11","estado":"REPROBADA","calificacion":0,"vecesReprobada":2,"grupos":[]},
                 {"clave":"COMP","estado":"APROBADA","calificacion":100,"vecesReprobada":0,"grupos":[]},
                 {"clave":"D11","estado":"CURSANDO","calificacion":0,"vecesReprobada":0,"grupos":[]}
                ]""");
        Files.writeString(beta.resolve("mi_plan.json"),
                "{\"nombre\":\"Mi_Plan\",\"materiasSeleccionadas\":{\"B11\":{\"profesor\":\"PROFE X\",\"hora\":\"09:00-10:00 (19B)\"}}}");
        Files.writeString(beta.resolve("config.json"), "{\"ultimo_layout\":\"mi_plan\"}");
        Map<Path, String> antes = contenido(beta);

        Almacen almacen = new Almacen(tmp.resolve("datos"), beta);
        Kardex k = almacen.cargar();

        assertEquals(Map.of("A11", 95, "A1C", 0), k.datos.aprobadas); // "COMP" de la beta es A1C
        assertEquals(Map.of("B11", 1, "C11", 2), k.datos.reprobadas);
        assertEquals("Mi_Plan", k.activo());
        assertEquals(Map.of("B11", "B", "D11", ""), k.horario());
        assertEquals(Kardex.Estado.CURSANDO, k.estado(k.materia("B11")));
        assertEquals("B", k.grupoActual(k.materia("B11")).letra());
        assertEquals(List.of("lunes", "miercoles"), List.copyOf(k.grupo(k.materia("B11"), "A").horarios().keySet()));
        assertEquals(95.0, k.promedio());
        assertNotNull(almacen.aviso);
        assertEquals(antes, contenido(beta));

        // La segunda vez ya no importa: lee lo guardado.
        k.aprobar(k.materia("D11"), 80);
        almacen.guardar(Almacen.HISTORIA, k);
        Almacen otra = new Almacen(tmp.resolve("datos"), beta);
        assertEquals(80, otra.cargar().calificacion(k.materia("D11")));
        assertNull(otra.aviso);

        // Un archivo guardado con la clave vieja de complementarias se lee como A1C.
        Path vieja = Files.createDirectories(tmp.resolve("vieja"));
        Files.writeString(vieja.resolve("kardex.json"), "{\"aprobadas\":{\"COMP\":0},\"ruta\":{\"COMP\":3}}");
        Kardex v = new Almacen(vieja, tmp.resolve("no-hay-beta")).cargar();
        assertEquals(Kardex.Estado.APROBADA, v.estado(v.materia("A1C")));
        assertFalse(v.datos.aprobadas.containsKey("COMP"));
    }

    private static Map<Path, String> contenido(Path carpeta) throws IOException {
        try (var archivos = Files.list(carpeta)) {
            return archivos.collect(Collectors.toMap(p -> p, p -> {
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
    }

    @Test
    void leeLaTablaDelITL() throws IOException {
        String html = """
                <table>
                <tr><th>Clave</th><th>Grupo</th><th>Materia</th><th>Hora</th><th>L</th><th>M</th><th>M</th><th>J</th><th>V</th><th>Catedrático</th></tr>
                <tr><td>A11</td><td>A</td><td>CALCULO</td><td>07:00-08:00</td><td>19A</td><td>·····</td><td>19A</td><td></td><td>19M09:00-11:00</td><td>FLORES ALARCON MA. SUSANA</td></tr>
                <tr><td>XYZ</td><td>A</td><td>OTRA</td><td>07:00-08:00</td><td>19A</td><td>19A</td><td>19A</td><td>19A</td><td>19A</td><td>ALGUIEN</td></tr>
                <tr><td>B11</td><td>B</td><td>PROG</td><td>09:00-10:00</td><td>AA1</td><td>AA1</td><td>AA1</td><td>AA1</td><td>AA1</td><td>·······</td></tr>
                </table>""";
        Map<String, List<Kardex.Grupo>> oferta = SyncITL.leer(Jsoup.parse(html), Set.of("A11", "B11"));
        assertEquals(Set.of("A11", "B11"), oferta.keySet());
        Kardex.Grupo a = oferta.get("A11").get(0);
        assertEquals("FLORES ALARCON MA. SUSANA", a.profesor()); // el punto no la vuelve celda vacía
        assertEquals(List.of("lunes", "miercoles", "viernes"), List.copyOf(a.horarios().keySet()));
        assertEquals(List.of(0, 2, 4), Kardex.clases(a).stream().map(Kardex.Clase::dia).toList());
        assertEquals(new Kardex.Clase(0, 7, 8, "19A"), Kardex.clases(a).get(0));
        assertEquals(new Kardex.Clase(4, 9, 11, "19M"), Kardex.clases(a).get(2)); // otra hora ese día
        assertEquals("POR ASIGNAR", oferta.get("B11").get(0).profesor());
        assertEquals(5, Kardex.clases(oferta.get("B11").get(0)).size());
        assertThrows(IOException.class, () -> SyncITL.leer(Jsoup.parse("<p>Sin horarios</p>"), Set.of("A11")));
    }
}
