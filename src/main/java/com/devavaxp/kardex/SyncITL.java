package com.devavaxp.kardex;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Baja la oferta de grupos de Sistemas de la consulta pública de horarios del ITL. */
final class SyncITL {

    private static final String URL = "https://apps2.itlalaguna.edu.mx/horarios/login.aspx";
    private static final String PLAN_SISTEMAS = "1";

    private SyncITL() {
    }

    /** Hace la consulta (tarda unos segundos: llamar fuera del hilo de JavaFX). */
    static Map<String, List<Kardex.Grupo>> descargar(Set<String> claves) throws IOException {
        Connection.Response inicio = Jsoup.connect(URL).userAgent("Mozilla/5.0").timeout(20_000).execute();
        Document formulario = inicio.parse();
        Document pagina = Jsoup.connect(URL).userAgent("Mozilla/5.0").timeout(20_000)
                .cookies(inicio.cookies())
                .data("__VIEWSTATE", formulario.select("input[name=__VIEWSTATE]").val())
                .data("__VIEWSTATEGENERATOR", formulario.select("input[name=__VIEWSTATEGENERATOR]").val())
                .data("__EVENTVALIDATION", formulario.select("input[name=__EVENTVALIDATION]").val())
                .data("ddlPlan", PLAN_SISTEMAS)
                .data("btnConsultar", "Consultar")
                .post();
        return leer(pagina, claves);
    }

    /** Columnas de la tabla: clave, grupo, -, hora, aula de lunes a viernes, catedrático. */
    static Map<String, List<Kardex.Grupo>> leer(Document pagina, Set<String> claves) throws IOException {
        Map<String, List<Kardex.Grupo>> oferta = new TreeMap<>();
        Element tabla = pagina.selectFirst("table");
        if (tabla != null) {
            for (Element fila : tabla.select("tr")) {
                Elements td = fila.select("td");
                if (td.size() < 10 || !claves.contains(td.get(0).text().trim())) continue;
                String hora = td.get(3).text().trim();
                Map<String, String> horarios = new LinkedHashMap<>();
                for (int d = 0; d < Kardex.DIAS.size(); d++) {
                    String aula = td.get(4 + d).text().trim();
                    if (hayDato(aula)) horarios.put(Kardex.DIAS.get(d), hora + " (" + aula + ")");
                }
                String profesor = td.get(9).text().trim();
                oferta.computeIfAbsent(td.get(0).text().trim(), k -> new ArrayList<>())
                        .add(new Kardex.Grupo(td.get(1).text().trim(), hayDato(profesor) ? profesor : "POR ASIGNAR", horarios));
            }
        }
        if (oferta.isEmpty()) {
            throw new IOException("La página del ITL no trajo grupos de Sistemas: puede que haya cambiado "
                    + "o que todavía no publique los horarios.");
        }
        return oferta;
    }

    /** La página rellena las celdas vacías con puntos. */
    private static boolean hayDato(String celda) {
        return !celda.isEmpty() && !celda.equals("-") && !celda.contains(".") && !celda.contains("·");
    }
}
