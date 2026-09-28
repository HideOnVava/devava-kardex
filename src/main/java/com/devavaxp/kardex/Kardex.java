package com.devavaxp.kardex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El avance del alumno sobre el plan de estudios y las reglas del TecNM para cursar.
 * "Cursando" no se guarda: es lo que está en el horario activo.
 */
final class Kardex {

    static final int CREDITOS_TOTALES = 260;
    /** Carga mínima por semestre (la máxima depende de repeticiones y especiales: cargaMaxima). */
    static final int CARGA_MINIMA = 18;
    static final List<String> DIAS = List.of("lunes", "martes", "miercoles", "jueves", "viernes");

    /**
     * Una materia del plan. {@code fila} y {@code ancho} solo sirven para dibujar la retícula.
     * {@code seAcredita}: servicio social y complementarias, que no se cursan ni llevan
     * calificación; solo se marcan aprobadas al terminarlas. {@code carga}: lo que pesa en la
     * carga del semestre si no son sus créditos (residencia vale 10 pero pesa 20).
     */
    record Materia(String clave, String nombre, int semestre, int fila, int ancho, int creditos, int porcentaje,
                   List<String> requisitos, boolean optativa, boolean seAcredita, int carga) {
        Materia {
            ancho = Math.max(1, ancho);
            requisitos = requisitos == null ? List.of() : List.copyOf(requisitos);
            if (carga < 1) carga = creditos;
        }
    }

    /** Un grupo de la oferta del ITL; {@code horarios}: día → "10:00-11:00 (19L)". */
    record Grupo(String letra, String profesor, Map<String, String> horarios) {}

    /** Una clase de un grupo: día (0 = lunes), hora de inicio y de fin, aula. */
    record Clase(int dia, double inicio, double fin, String aula) {}

    enum Estado { APROBADA, CURSANDO, REPROBADA, DISPONIBLE, BLOQUEADA }

    /** Lo que se guarda en kardex.json (la historia) o en patio.json (el patio de juegos). */
    static final class Datos {
        Map<String, Integer> aprobadas = new TreeMap<>();   // clave → calificación (0 = sin calificación)
        Map<String, Integer> reprobadas = new TreeMap<>();  // clave → veces reprobada (solo materias no aprobadas)
        Map<String, Map<String, String>> horarios = new LinkedHashMap<>(); // nombre → clave → letra ("" = sin grupo)
        String activo = "Horario 1";
        int semestre;                                       // el que cursas ahora (0 = sin definir)
        Map<String, Integer> ruta = new TreeMap<>();        // patio: clave → semestre en que la cursarías
    }

    final List<Materia> plan;
    final Datos datos;
    private final Map<String, Materia> porClave = new LinkedHashMap<>();
    private Map<String, List<Grupo>> oferta;

    Kardex(List<Materia> plan, Datos datos, Map<String, List<Grupo>> oferta) {
        this.plan = List.copyOf(plan);
        this.datos = datos;
        this.oferta = oferta;
        plan.forEach(m -> porClave.put(m.clave(), m));
        if (datos.semestre < 1) datos.semestre = 1;
        if (datos.horarios.isEmpty()) datos.horarios.put(datos.activo, new LinkedHashMap<>());
        if (!datos.horarios.containsKey(datos.activo)) datos.activo = datos.horarios.keySet().iterator().next();
        // Un horario solo tiene materias del plan que se cursan y aún no están aprobadas.
        datos.horarios.values().forEach(h -> h.keySet().removeIf(c ->
                !porClave.containsKey(c) || porClave.get(c).seAcredita() || datos.aprobadas.containsKey(c)));
    }

    Materia materia(String clave) {
        return porClave.get(clave);
    }

    Map<String, String> horario() {
        return datos.horarios.get(datos.activo);
    }

    // ------------------------------------------------------------------
    // Estado y reglas
    // ------------------------------------------------------------------

    Estado estado(Materia m) {
        String c = m.clave();
        if (datos.aprobadas.containsKey(c)) return Estado.APROBADA;
        if (horario().containsKey(c)) return Estado.CURSANDO;
        if (datos.reprobadas.containsKey(c)) return Estado.REPROBADA;
        return bloqueo(m) == null ? Estado.DISPONIBLE : Estado.BLOQUEADA;
    }

    /** Calificación de una materia aprobada; 0 si no tiene. */
    int calificacion(Materia m) {
        return Objects.requireNonNullElse(datos.aprobadas.get(m.clave()), 0);
    }

    int vecesReprobada(Materia m) {
        return datos.reprobadas.getOrDefault(m.clave(), 0);
    }

    /**
     * Por qué no se puede cursar la materia, o null si se puede: seriación, porcentaje de
     * créditos (60 % especialidad, 70 % servicio social, 80 % residencia) y el candado de las
     * dos materias en curso especial.
     */
    String bloqueo(Materia m) {
        List<String> razones = new ArrayList<>();
        if (especiales() >= 2 && vecesReprobada(m) < 2)
            razones.add("Con dos materias en curso especial solo puedes cursar esas dos.");
        List<String> faltan = m.requisitos().stream()
                .filter(r -> porClave.containsKey(r) && !datos.aprobadas.containsKey(r))
                .map(r -> porClave.get(r).nombre())
                .toList();
        if (!faltan.isEmpty()) razones.add("Falta aprobar " + String.join(", ", faltan) + ".");
        int necesarios = creditosPara(m.porcentaje());
        if (creditosAprobados() < necesarios)
            razones.add("Requiere el " + m.porcentaje() + " % de los créditos (" + necesarios
                    + "); llevas " + creditosAprobados() + ".");
        return razones.isEmpty() ? null : String.join(" ", razones);
    }

    static int creditosPara(int porcentaje) {
        return (int) Math.ceil(CREDITOS_TOTALES * porcentaje / 100.0);
    }

    int creditosAprobados() {
        return datos.aprobadas.keySet().stream().map(porClave::get).filter(Objects::nonNull)
                .mapToInt(Materia::creditos).sum();
    }

    /** Promedio de las calificaciones capturadas (sin servicio social ni complementarias); NaN si no hay. */
    double promedio() {
        return datos.aprobadas.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .filter(e -> porClave.containsKey(e.getKey()) && !porClave.get(e.getKey()).seAcredita())
                .mapToInt(Map.Entry::getValue).average().orElse(Double.NaN);
    }

    /** Créditos de carga del horario activo. */
    int carga() {
        return horario().keySet().stream().map(porClave::get).filter(Objects::nonNull)
                .mapToInt(Materia::carga).sum();
    }

    int cargaMaxima() {
        return cargaMaxima(datos.reprobadas);
    }

    /** 36 créditos; 30 con alguna materia en repetición; 20 con una en especial; 10 con dos. */
    static int cargaMaxima(Map<String, Integer> reprobadas) {
        long especiales = reprobadas.values().stream().filter(v -> v >= 2).count();
        if (especiales >= 2) return 10;
        if (especiales == 1) return 20;
        return reprobadas.containsValue(1) ? 30 : 36;
    }

    private long especiales() {
        return datos.reprobadas.values().stream().filter(v -> v >= 2).count();
    }

    // ------------------------------------------------------------------
    // Grupos y choques de horario
    // ------------------------------------------------------------------

    List<Grupo> grupos(Materia m) {
        return oferta.getOrDefault(m.clave(), List.of());
    }

    Grupo grupo(Materia m, String letra) {
        return grupos(m).stream().filter(g -> g.letra().equals(letra)).findFirst().orElse(null);
    }

    /** Grupo con el que se cursa la materia en el horario activo, o null. */
    Grupo grupoActual(Materia m) {
        String letra = horario().get(m.clave());
        return letra == null || letra.isEmpty() ? null : grupo(m, letra);
    }

    /** Nombre de la materia del horario activo que se cruza con ese grupo, o null. */
    String choque(Materia m, Grupo g) {
        for (String clave : horario().keySet()) {
            Materia otra = porClave.get(clave);
            Grupo suyo = otra == null || clave.equals(m.clave()) ? null : grupoActual(otra);
            if (suyo == null) continue;
            for (Clase a : clases(g))
                for (Clase b : clases(suyo))
                    if (a.dia() == b.dia() && a.inicio() < b.fin() && b.inicio() < a.fin()) return otra.nombre();
        }
        return null;
    }

    private static final Pattern HORA = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})");

    /**
     * Las clases de un grupo. Acepta "10:00-11:00 (19L)" y el formato de la beta "10:00-11:00/19L".
     * Si ese día la clase es a otra hora, el ITL la pega al aula ("19M10:00-12:00"): manda esa.
     */
    static List<Clase> clases(Grupo g) {
        List<Clase> clases = new ArrayList<>();
        for (int d = 0; d < DIAS.size(); d++) {
            String texto = g.horarios() == null ? null : g.horarios().get(DIAS.get(d));
            if (texto == null) continue;
            Matcher mt = HORA.matcher(texto);
            double inicio = -1, fin = -1;
            while (mt.find()) {
                inicio = Integer.parseInt(mt.group(1)) + Integer.parseInt(mt.group(2)) / 60.0;
                fin = Integer.parseInt(mt.group(3)) + Integer.parseInt(mt.group(4)) / 60.0;
            }
            if (inicio < 0) continue;
            String aula = HORA.matcher(texto).replaceAll("").replaceAll("[/()]", " ").trim();
            clases.add(new Clase(d, inicio, fin > inicio ? fin : inicio + 1, aula));
        }
        return clases;
    }

    Map<String, List<Grupo>> oferta() {
        return oferta;
    }

    void setOferta(Map<String, List<Grupo>> oferta) {
        this.oferta = oferta;
    }

    // ------------------------------------------------------------------
    // Cambios
    // ------------------------------------------------------------------

    /** Pone la materia en el horario activo, con grupo o sin él ({@code letra} null). Devuelve el error o null. */
    String cursar(Materia m, String letra) {
        String c = m.clave();
        if (m.seAcredita()) return m.nombre() + " no se cursa: márcala aprobada cuando la termines.";
        boolean nueva = !horario().containsKey(c);
        if (nueva) {
            if (datos.aprobadas.containsKey(c)) return "Ya la aprobaste.";
            String razon = bloqueo(m);
            if (razon != null) return razon;
            if (carga() + m.carga() > cargaMaxima())
                return "Con esta materia pasarías tu carga máxima de " + cargaMaxima() + " créditos.";
        }
        if (letra == null) {
            if (nueva) horario().put(c, "");
            datos.ruta.remove(c);
            return null;
        }
        Grupo g = grupo(m, letra);
        if (g == null) return "El grupo " + letra + " ya no está en la oferta.";
        String cruce = choque(m, g);
        if (cruce != null) return "Se cruza con " + cruce + ".";
        horario().put(c, letra);
        datos.ruta.remove(c);
        return null;
    }

    /** Patio: semestre futuro en que se cursaría la materia (null la saca de la ruta). */
    void planear(Materia m, Integer semestre) {
        if (semestre == null) datos.ruta.remove(m.clave());
        else datos.ruta.put(m.clave(), semestre);
    }

    void quitar(Materia m) {
        horario().remove(m.clave());
    }

    void aprobar(Materia m, int calificacion) {
        datos.aprobadas.put(m.clave(), m.seAcredita() ? 0 : calificacion);
        datos.reprobadas.remove(m.clave());
        datos.ruta.remove(m.clave());
        datos.horarios.values().forEach(h -> h.remove(m.clave()));
    }

    void reprobar(Materia m) {
        if (estado(m) == Estado.REPROBADA) return;
        datos.aprobadas.remove(m.clave());
        datos.reprobadas.merge(m.clave(), 1, Integer::sum);
        horario().remove(m.clave());
    }

    void pendiente(Materia m) {
        datos.aprobadas.remove(m.clave());
        datos.reprobadas.remove(m.clave());
        horario().remove(m.clave());
    }

    void reiniciar() {
        datos.aprobadas.clear();
        datos.reprobadas.clear();
        datos.ruta.clear();
        datos.horarios.clear();
        datos.activo = "Horario 1";
        datos.horarios.put(datos.activo, new LinkedHashMap<>());
        datos.semestre = 1;
    }

    /** Copia independiente de los datos: el patio arranca desde la historia sin tocarla. */
    Datos copia() {
        Datos d = new Datos();
        d.aprobadas = new TreeMap<>(datos.aprobadas);
        d.reprobadas = new TreeMap<>(datos.reprobadas);
        datos.horarios.forEach((n, h) -> d.horarios.put(n, new LinkedHashMap<>(h)));
        d.activo = datos.activo;
        d.semestre = datos.semestre;
        return d;
    }

    /** Reemplaza todo el avance por otro (el patio al copiar la historia). */
    void reemplazar(Datos otros) {
        datos.aprobadas = otros.aprobadas;
        datos.reprobadas = otros.reprobadas;
        datos.horarios = otros.horarios;
        datos.activo = otros.activo;
        datos.semestre = otros.semestre;
        datos.ruta = otros.ruta;
    }

    // ------------------------------------------------------------------
    // Horarios guardados
    // ------------------------------------------------------------------

    Set<String> horarios() {
        return datos.horarios.keySet();
    }

    String activo() {
        return datos.activo;
    }

    void activar(String nombre) {
        if (datos.horarios.containsKey(nombre)) datos.activo = nombre;
    }

    String nuevoHorario(String nombre) {
        if (datos.horarios.containsKey(nombre)) return "Ya tienes un horario llamado " + nombre + ".";
        datos.horarios.put(nombre, new LinkedHashMap<>());
        datos.activo = nombre;
        return null;
    }

    String renombrarHorario(String nombre) {
        if (nombre.equals(datos.activo)) return null;
        if (datos.horarios.containsKey(nombre)) return "Ya tienes un horario llamado " + nombre + ".";
        Map<String, Map<String, String>> renombrados = new LinkedHashMap<>();
        datos.horarios.forEach((n, h) -> renombrados.put(n.equals(datos.activo) ? nombre : n, h));
        datos.horarios = renombrados;
        datos.activo = nombre;
        return null;
    }

    void eliminarHorario() {
        if (datos.horarios.size() < 2) return;
        datos.horarios.remove(datos.activo);
        datos.activo = datos.horarios.keySet().iterator().next();
    }
}
