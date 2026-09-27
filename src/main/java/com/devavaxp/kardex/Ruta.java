package com.devavaxp.kardex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Patio de juegos: en qué semestre cursarías cada materia que falta y en cuál terminarías,
 * con las reglas del TecNM (seriación, porcentaje de créditos, carga máxima). Supone que
 * apruebas lo que cursas ahora y lo que planeas.
 */
final class Ruta {

    /** El TecNM da hasta 12 semestres para terminar la carrera. */
    static final int LIMITE = 12;

    record Semestre(int numero, int creditos, int cargaMaxima, List<Kardex.Materia> materias, String error) {}

    /** {@code termina}: último semestre de la ruta; {@code faltan}: materias que no están en ella. */
    record Resultado(List<Semestre> semestres, Map<String, String> errores, int termina, int faltan) {}

    private Ruta() {
    }

    /** Primer semestre de la ruta: el siguiente, o el actual si ahora no cursas nada. */
    static int inicio(Kardex k) {
        return k.horario().isEmpty() ? k.datos.semestre : k.datos.semestre + 1;
    }

    /** Clase de estilo del semestre (p1…p6, en ciclo), la misma en la barra y en la retícula. */
    static String color(Kardex k, int semestre) {
        return "p" + (Math.floorMod(semestre - inicio(k), 6) + 1);
    }

    static Resultado evaluar(Kardex k) {
        int actual = k.datos.semestre, inicio = inicio(k);
        Map<String, Integer> plan = vigente(k);
        TreeMap<Integer, List<Kardex.Materia>> porSemestre = new TreeMap<>();
        plan.forEach((c, s) -> porSemestre.computeIfAbsent(s, x -> new ArrayList<>()).add(k.materia(c)));
        Map<String, String> errores = new HashMap<>();
        List<Semestre> semestres = new ArrayList<>();
        for (Map.Entry<Integer, List<Kardex.Materia>> e : porSemestre.entrySet()) {
            int s = e.getKey();
            int antes = creditosAntes(k, plan, s);
            Map<String, Integer> reprobadas = reprobadasPendientes(k, plan, s);
            int maximo = Kardex.cargaMaxima(reprobadas);
            boolean soloEspeciales = reprobadas.values().stream().filter(v -> v >= 2).count() >= 2;
            int suma = 0;
            for (Kardex.Materia m : e.getValue()) {
                if (!m.seAcredita()) suma += m.creditos(); // servicio social y complementarias no son carga
                List<String> razones = new ArrayList<>();
                if (s < inicio) razones.add("La ruta empieza en el " + inicio + "°.");
                if (s == 1 && !dePrimero(m)) razones.add("En primer semestre solo se llevan las materias de primer semestre.");
                if (s != 1 && inicio == 1 && dePrimero(m)) razones.add("Se lleva sí o sí en primer semestre.");
                for (String r : m.requisitos())
                    if (!lista(k, plan, r, s)) razones.add("Antes necesitas " + k.materia(r).nombre() + ".");
                int necesarios = Kardex.creditosPara(m.porcentaje());
                if (antes < necesarios)
                    razones.add("Al empezar el " + s + "° llevarías " + antes + " créditos; necesita " + necesarios + ".");
                if (soloEspeciales && !m.seAcredita() && reprobadas.getOrDefault(m.clave(), 0) < 2)
                    razones.add("Con dos materias en curso especial ese semestre solo llevas esas dos.");
                if (!razones.isEmpty()) errores.put(m.clave(), String.join(" ", razones));
            }
            List<String> problemas = new ArrayList<>();
            if (suma > maximo) problemas.add("Suma " + suma + " créditos y tu máximo es " + maximo + ".");
            if (Kardex.excedeResidencia(e.getValue()))
                problemas.add("Con Residencia van hasta " + Kardex.MATERIAS_CON_RESIDENCIA + " materias más.");
            if (s > LIMITE) problemas.add("Pasa del límite de " + LIMITE + " semestres.");
            semestres.add(new Semestre(s, suma, maximo, e.getValue(), problemas.isEmpty() ? null : String.join(" ", problemas)));
        }
        return new Resultado(semestres, errores, porSemestre.isEmpty() ? actual : porSemestre.lastKey(), faltan(k, plan));
    }

    /**
     * Acomoda todo lo que falta: lo antes posible ({@code objetivo} 0) o con la carga más ligera
     * que todavía termina en el semestre {@code objetivo}. Devuelve un aviso, o null si salió.
     */
    static String armar(Kardex k, int objetivo) {
        List<Kardex.Materia> pendientes = pendientes(k);
        Map<String, Integer> elegida = acomodar(k, pendientes, 36);
        String aviso = null;
        int antes = terminaEn(k, elegida);
        if (objetivo > 0 && antes > objetivo) {
            aviso = "No alcanza para terminar en el " + objetivo + "°: lo antes posible es el " + antes + "°.";
        } else if (objetivo > 0) {
            int minimo = pendientes.stream().mapToInt(Kardex.Materia::creditos).max().orElse(1);
            for (int tope = minimo; tope < 36; tope++) {
                Map<String, Integer> ligera = acomodar(k, pendientes, tope);
                if (ligera.size() == pendientes.size() && terminaEn(k, ligera) <= objetivo) {
                    elegida = ligera;
                    break;
                }
            }
        }
        if (elegida.size() < pendientes.size()) aviso = "No pude acomodar todas las materias: revisa las que están en rojo.";
        k.datos.ruta.clear();
        k.datos.ruta.putAll(elegida);
        return aviso;
    }

    /** Semestre por semestre, toma lo que ya se puede cursar por prioridad hasta llenar la carga. */
    private static Map<String, Integer> acomodar(Kardex k, List<Kardex.Materia> pendientes, int tope) {
        Map<String, Integer> plan = new HashMap<>();
        List<Kardex.Materia> faltan = new ArrayList<>(pendientes);
        for (int s = inicio(k); !faltan.isEmpty(); s++) {
            int semestre = s, antes = creditosAntes(k, plan, s);
            Map<String, Integer> reprobadas = reprobadasPendientes(k, plan, s);
            boolean soloEspeciales = reprobadas.values().stream().filter(v -> v >= 2).count() >= 2;
            int maximo = Math.min(tope, Kardex.cargaMaxima(reprobadas)), carga = 0;
            List<Kardex.Materia> tomadas = new ArrayList<>();
            if (s == 1) {
                // Al ingresar se llevan sí o sí las materias de primer semestre, y solo esas.
                tomadas.addAll(faltan.stream().filter(Ruta::dePrimero).toList());
            } else {
                for (Kardex.Materia m : faltan) {
                    if (!m.requisitos().stream().allMatch(r -> lista(k, plan, r, semestre))) continue;
                    if (antes < Kardex.creditosPara(m.porcentaje())) continue;
                    if (!m.seAcredita()) { // lo que se cursa: carga, especiales y el límite con Residencia
                        if (soloEspeciales && reprobadas.getOrDefault(m.clave(), 0) < 2) continue;
                        if (carga + m.creditos() > maximo) continue;
                        List<Kardex.Materia> juntas = new ArrayList<>(tomadas);
                        juntas.add(m);
                        if (Kardex.excedeResidencia(juntas)) continue;
                        carga += m.creditos();
                    }
                    tomadas.add(m);
                }
                if (tomadas.isEmpty()) break; // nada más se puede acomodar
            }
            tomadas.forEach(m -> plan.put(m.clave(), semestre));
            faltan.removeAll(tomadas);
        }
        return plan;
    }

    /**
     * Lo que falta por cursar: obligatorias y una optativa (la que ya esté en la ruta o la del
     * semestre más temprano) si aún no hay ninguna. Primero las reprobadas, luego las que abren
     * cadenas más largas de seriación.
     */
    private static List<Kardex.Materia> pendientes(Kardex k) {
        List<Kardex.Materia> p = new ArrayList<>(k.plan.stream()
                .filter(m -> !m.optativa() && !cubierta(k, m.clave())).toList());
        boolean conOptativa = k.plan.stream().anyMatch(m -> m.optativa() && cubierta(k, m.clave()));
        if (!conOptativa) {
            k.plan.stream().filter(Kardex.Materia::optativa)
                    .min(Comparator.comparing((Kardex.Materia m) -> !k.datos.ruta.containsKey(m.clave()))
                            .thenComparingInt(Kardex.Materia::semestre))
                    .ifPresent(p::add);
        }
        Map<String, Integer> altura = alturas(k);
        p.sort(Comparator.comparing((Kardex.Materia m) -> k.vecesReprobada(m) == 0)
                .thenComparing(m -> -altura.get(m.clave()))
                .thenComparingInt(Kardex.Materia::semestre)
                .thenComparing(m -> -m.creditos()));
        return p;
    }

    /** Cuántas materias dependen en cadena de cada una (la más larga). */
    private static Map<String, Integer> alturas(Kardex k) {
        Map<String, Integer> altura = new HashMap<>();
        List<Kardex.Materia> orden = new ArrayList<>(k.plan);
        orden.sort(Comparator.comparingInt(Kardex.Materia::semestre).reversed());
        for (Kardex.Materia m : orden) {
            int h = 0;
            for (Kardex.Materia d : k.plan)
                if (d.requisitos().contains(m.clave())) h = Math.max(h, 1 + altura.getOrDefault(d.clave(), 0));
            altura.put(m.clave(), h);
        }
        return altura;
    }

    /** La ruta guardada, sin las materias que ya están aprobadas o se cursan ahora. */
    private static Map<String, Integer> vigente(Kardex k) {
        Map<String, Integer> plan = new HashMap<>();
        k.datos.ruta.forEach((c, s) -> {
            if (k.materia(c) != null && !k.datos.aprobadas.containsKey(c) && !k.horario().containsKey(c)) plan.put(c, s);
        });
        return plan;
    }

    /** Las materias que todo alumno lleva al ingresar (las de primer semestre que se cursan). */
    private static boolean dePrimero(Kardex.Materia m) {
        return m.semestre() == 1 && !m.seAcredita();
    }

    private static boolean cubierta(Kardex k, String clave) {
        return k.datos.aprobadas.containsKey(clave) || k.horario().containsKey(clave);
    }

    /** ¿Estará aprobada al empezar el semestre s? (aprobada, en curso ahora o planeada antes) */
    private static boolean lista(Kardex k, Map<String, Integer> plan, String clave, int s) {
        if (k.materia(clave) == null || cubierta(k, clave)) return true;
        Integer p = plan.get(clave);
        return p != null && p < s;
    }

    private static int creditosAntes(Kardex k, Map<String, Integer> plan, int s) {
        return k.plan.stream().filter(m -> lista(k, plan, m.clave(), s)).mapToInt(Kardex.Materia::creditos).sum();
    }

    /** Reprobadas que siguen sin pasar al empezar el semestre s (clave → veces). */
    private static Map<String, Integer> reprobadasPendientes(Kardex k, Map<String, Integer> plan, int s) {
        Map<String, Integer> r = new HashMap<>();
        k.datos.reprobadas.forEach((c, v) -> {
            if (!lista(k, plan, c, s)) r.put(c, v);
        });
        return r;
    }

    private static int terminaEn(Kardex k, Map<String, Integer> plan) {
        return plan.values().stream().mapToInt(Integer::intValue).max().orElse(k.datos.semestre);
    }

    private static int faltan(Kardex k, Map<String, Integer> plan) {
        int n = (int) k.plan.stream().filter(m -> !m.optativa())
                .filter(m -> !cubierta(k, m.clave()) && !plan.containsKey(m.clave())).count();
        boolean optativa = k.plan.stream().filter(Kardex.Materia::optativa)
                .anyMatch(m -> cubierta(k, m.clave()) || plan.containsKey(m.clave()));
        return optativa ? n : n + 1;
    }
}
