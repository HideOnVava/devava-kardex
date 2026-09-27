package com.devavaxp.kardex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Los archivos del usuario, en Documentos/Devava Kardex: {@code kardex.json} (su avance y sus
 * horarios) y {@code oferta.json} (grupos del ITL). El plan de estudios viene dentro de la app.
 */
final class Almacen {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type PLAN = new TypeToken<List<Kardex.Materia>>() {}.getType();
    private static final Type OFERTA = new TypeToken<Map<String, List<Kardex.Grupo>>>() {}.getType();
    static final String HISTORIA = "kardex.json", PATIO = "patio.json";

    final Path carpeta;
    /** Carpeta de la beta (KardexByVava), de donde se importa el avance la primera vez. */
    private final Path beta;
    /** Algo que avisar al usuario después de cargar, o null. */
    String aviso;

    Almacen() {
        this(documentos().resolve("Devava Kardex"),
                Path.of(System.getProperty("user.home"), "Documents", "KardexITL_Data"));
    }

    Almacen(Path carpeta, Path beta) {
        this.carpeta = carpeta;
        this.beta = beta;
    }

    /** La historia: el avance real del alumno. */
    Kardex cargar() throws IOException {
        Files.createDirectories(carpeta);
        List<Kardex.Materia> plan = recurso("plan-isc.json", PLAN);
        boolean hayBeta = Files.exists(beta.resolve("reticula.json"));
        if (!Files.exists(carpeta.resolve(HISTORIA)) && hayBeta) importarBeta(plan);
        Kardex.Datos datos = leer(HISTORIA);
        if (datos != null && datos.semestre < 1 && hayBeta) datos.semestre = semestreBeta();
        Path oferta = carpeta.resolve("oferta.json");
        Map<String, List<Kardex.Grupo>> grupos = Files.exists(oferta)
                ? GSON.fromJson(Files.readString(oferta), OFERTA)
                : recurso("oferta-isc.json", OFERTA);
        return new Kardex(plan, datos != null ? datos : new Kardex.Datos(), grupos);
    }

    /** El patio de juegos; la primera vez es una copia de la historia. */
    Kardex cargarPatio(Kardex historia) throws IOException {
        Kardex.Datos datos = leer(PATIO);
        return new Kardex(historia.plan, datos != null ? datos : historia.copia(), historia.oferta());
    }

    void guardar(String archivo, Kardex kardex) throws IOException {
        escribir(carpeta.resolve(archivo), kardex.datos);
    }

    /** Null si el archivo no existe; si está dañado lo aparta y avisa, para no perderlo al guardar. */
    private Kardex.Datos leer(String archivo) throws IOException {
        Path p = carpeta.resolve(archivo);
        if (!Files.exists(p)) return null;
        try {
            return GSON.fromJson(Files.readString(p), Kardex.Datos.class);
        } catch (JsonParseException e) {
            Path copia = carpeta.resolve(archivo.replace(".json", "") + "-danado-" + System.currentTimeMillis() + ".json");
            Files.move(p, copia);
            aviso = "El archivo " + archivo + " estaba dañado. Lo guardé como " + copia.getFileName() + " y empecé uno nuevo.";
            return null;
        }
    }

    /** La beta guardaba el semestre actual en las preferencias de Java (en Windows, el registro). */
    private static int semestreBeta() {
        try {
            Preferences raiz = Preferences.userRoot();
            if (raiz.nodeExists("com/example/kardexbyvava"))
                return Math.max(1, raiz.node("com/example/kardexbyvava").getInt("semestre_guardado", 1));
        } catch (BackingStoreException | SecurityException ignorada) {
            // Sin acceso a las preferencias: se queda en 1.
        }
        return 1;
    }

    void guardarOferta(Map<String, List<Kardex.Grupo>> oferta) throws IOException {
        escribir(carpeta.resolve("oferta.json"), oferta);
    }

    /** Escribe a un temporal y lo mueve encima: un cierre a medias no deja el archivo cortado. */
    private static void escribir(Path archivo, Object contenido) throws IOException {
        Path temporal = archivo.resolveSibling(archivo.getFileName() + ".tmp");
        Files.writeString(temporal, GSON.toJson(contenido));
        Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static <T> T recurso(String nombre, Type tipo) throws IOException {
        try (Reader r = new InputStreamReader(
                Objects.requireNonNull(Almacen.class.getResourceAsStream(nombre), nombre), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, tipo);
        }
    }

    /** La carpeta Documentos real: en Windows puede estar redirigida a OneDrive. */
    private static Path documentos() {
        Path home = Path.of(System.getProperty("user.home"));
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            try {
                File d = FileSystemView.getFileSystemView().getDefaultDirectory();
                if (d != null && d.isDirectory()) return d.toPath();
            } catch (RuntimeException | LinkageError ignorada) {
                // Sin shell de Windows disponible: se usa ~/Documents.
            }
        }
        Path documents = home.resolve("Documents");
        return Files.isDirectory(documents) ? documents : home;
    }

    // ------------------------------------------------------------------
    // Importación de la beta: reticula.json (estado de cada materia y sus grupos),
    // config.json (último layout) y un archivo por layout. La carpeta vieja no se toca.
    // ------------------------------------------------------------------

    private void importarBeta(List<Kardex.Materia> plan) throws IOException {
        Set<String> claves = plan.stream().map(Kardex.Materia::clave).collect(Collectors.toSet());
        Set<String> seAcreditan = plan.stream().filter(Kardex.Materia::seAcredita)
                .map(Kardex.Materia::clave).collect(Collectors.toSet());
        Kardex.Datos datos = new Kardex.Datos();
        Map<String, List<Kardex.Grupo>> oferta = new TreeMap<>();
        Map<String, JsonArray> gruposBeta = new HashMap<>();
        List<String> cursando = new ArrayList<>();

        for (JsonElement e : JsonParser.parseString(Files.readString(beta.resolve("reticula.json"))).getAsJsonArray()) {
            JsonObject m = e.getAsJsonObject();
            String clave = texto(m, "clave");
            if (!claves.contains(clave)) continue;
            int veces = entero(m, "vecesReprobada");
            switch (texto(m, "estado")) {
                case "APROBADA" -> datos.aprobadas.put(clave, seAcreditan.contains(clave) ? 0 : entero(m, "calificacion"));
                case "REPROBADA" -> datos.reprobadas.put(clave, Math.max(1, veces));
                case "CURSANDO" -> {
                    cursando.add(clave);
                    if (veces > 0) datos.reprobadas.put(clave, veces);
                }
                default -> { }
            }
            JsonArray grupos = m.has("grupos") && m.get("grupos").isJsonArray() ? m.getAsJsonArray("grupos") : new JsonArray();
            gruposBeta.put(clave, grupos);
            for (JsonElement ge : grupos) {
                JsonObject g = ge.getAsJsonObject();
                JsonObject hs = g.has("horarios") && g.get("horarios").isJsonObject() ? g.getAsJsonObject("horarios") : new JsonObject();
                Map<String, String> horarios = new LinkedHashMap<>();
                for (String dia : Kardex.DIAS) {
                    String h = texto(hs, dia);
                    if (!h.isEmpty() && Character.isDigit(h.charAt(0))) horarios.put(dia, h);
                }
                oferta.computeIfAbsent(clave, k -> new ArrayList<>())
                        .add(new Kardex.Grupo(texto(g, "letra"), texto(g, "catedratico"), horarios));
            }
        }

        String ultimo = "";
        Path config = beta.resolve("config.json");
        if (Files.exists(config)) ultimo = texto(leerObjeto(config), "ultimo_layout");
        List<Path> layouts;
        try (Stream<Path> archivos = Files.list(beta)) {
            layouts = archivos.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !Set.of("reticula.json", "reticula_original.json", "config.json")
                            .contains(p.getFileName().toString()))
                    .sorted().toList();
        }
        for (Path archivo : layouts) {
            try {
                JsonObject layout = leerObjeto(archivo);
                String nombre = texto(layout, "nombre");
                if (nombre.isEmpty()) nombre = archivo.getFileName().toString().replace(".json", "");
                Map<String, String> horario = new LinkedHashMap<>();
                JsonObject seleccion = layout.has("materiasSeleccionadas") && layout.get("materiasSeleccionadas").isJsonObject()
                        ? layout.getAsJsonObject("materiasSeleccionadas") : new JsonObject();
                for (Map.Entry<String, JsonElement> e : seleccion.entrySet()) {
                    String clave = e.getKey();
                    if (!claves.contains(clave) || datos.aprobadas.containsKey(clave)) continue;
                    JsonObject detalle = e.getValue().getAsJsonObject();
                    horario.put(clave, letraBeta(gruposBeta.get(clave), texto(detalle, "profesor"), texto(detalle, "hora")));
                }
                datos.horarios.put(nombre, horario);
                if (nombre.equalsIgnoreCase(ultimo)) datos.activo = nombre;
            } catch (JsonParseException | IllegalStateException | IOException ignorado) {
                // Un archivo que no es un layout de la beta se deja pasar.
            }
        }
        if (datos.horarios.isEmpty()) datos.horarios.put(datos.activo, new LinkedHashMap<>());
        if (!datos.horarios.containsKey(datos.activo)) datos.activo = datos.horarios.keySet().iterator().next();
        // Lo que la beta marcaba como "cursando" se sigue cursando, aunque no tuviera grupo.
        cursando.forEach(c -> datos.horarios.get(datos.activo).putIfAbsent(c, ""));
        datos.semestre = semestreBeta();

        escribir(carpeta.resolve("oferta.json"), oferta);
        escribir(carpeta.resolve(HISTORIA), datos);
        aviso = "Importé tu avance de la versión anterior. La carpeta vieja (" + beta + ") quedó intacta como respaldo.";
    }

    /** La beta identificaba el grupo por profesor y el primer horario guardado del grupo. */
    private static String letraBeta(JsonArray grupos, String profesor, String hora) {
        if (grupos == null) return "";
        for (JsonElement ge : grupos) {
            JsonObject g = ge.getAsJsonObject();
            JsonObject hs = g.has("horarios") && g.get("horarios").isJsonObject() ? g.getAsJsonObject("horarios") : new JsonObject();
            String primero = hs.isEmpty() ? "" : hs.entrySet().iterator().next().getValue().getAsString();
            if (texto(g, "catedratico").equals(profesor) && primero.equals(hora)) return texto(g, "letra");
        }
        return "";
    }

    private static JsonObject leerObjeto(Path archivo) throws IOException {
        return JsonParser.parseString(Files.readString(archivo)).getAsJsonObject();
    }

    private static String texto(JsonObject o, String campo) {
        JsonElement e = o.get(campo);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int entero(JsonObject o, String campo) {
        JsonElement e = o.get(campo);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }
}
