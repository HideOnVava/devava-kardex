package com.devavaxp.kardex;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Devava Kardex: la retícula con tu avance y tu horario, en una ventana. La historia es tu
 * avance real; el patio de juegos, una copia para probar lo que quieras y planear la ruta.
 */
public final class KardexApp extends Application {

    static final String NOMBRE = "Devava Kardex";

    /** Retícula y horario sobre un kardex: la historia o el patio de juegos. */
    private record Modo(Kardex kardex, String archivo, boolean patio, ReticulaView reticula, HorarioView horario, StackPane vista) {}

    private Almacen almacen;
    private Modo historia, patio, modo;
    private String vista = "reticula";
    private HBox modos, vistas;
    private final StackPane raiz = new StackPane();
    private final Label promedio = new Label(), creditos = new Label(), carga = new Label(), cargaNombre = new Label();
    private final Label aviso = new Label();
    private final MenuButton semestre = new MenuButton();
    private final ProgressBar avance = new ProgressBar(0);
    private final PauseTransition borrarAviso = new PauseTransition(Duration.seconds(6));
    private final MenuItem reiniciar = new MenuItem();

    /** Barra del patio: la ruta semestre por semestre y en cuál terminarías. */
    private final HBox tira = new HBox(24);
    private final FlowPane rutaSemestres = new FlowPane(6, 4);
    private final Label rutaResumen = new Label(), rutaAviso = new Label();
    private final MenuButton armar = new MenuButton("Armar ruta  ▾");

    /** Capa encima de todo donde flota el panel de la materia elegida. */
    private final Pane capa = new Pane() {
        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            ubicarPanel();
        }
    };
    private MateriaPanel panel;
    private Bounds ancla;

    @Override
    public void start(Stage stage) {
        almacen = new Almacen();
        Kardex h, p;
        try {
            h = almacen.cargar();
            p = almacen.cargarPatio(h);
        } catch (IOException | RuntimeException e) {
            error(null, "No pude abrir tus datos", e.getMessage() + "\n\nCarpeta: " + almacen.carpeta);
            Platform.exit();
            return;
        }
        Scene scene = new Scene(crearUI(h, p, almacen), 1280, 800);
        scene.getStylesheets().add(css());
        stage.setTitle(NOMBRE);
        stage.getIcons().add(icono());
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        if (almacen.aviso != null) info(stage, NOMBRE, almacen.aviso);
    }

    static String css() {
        return KardexApp.class.getResource("styles.css").toExternalForm();
    }

    Parent crearUI(Kardex h, Kardex p, Almacen almacen) {
        this.almacen = almacen;
        historia = modo(h, Almacen.HISTORIA, false);
        patio = modo(p, Almacen.PATIO, true);
        modo = historia;
        patio.vista().setVisible(false);

        BorderPane contenido = new BorderPane(new StackPane(historia.vista(), patio.vista()));
        contenido.setTop(new VBox(barra(), tira()));
        capa.setPickOnBounds(false);
        raiz.getChildren().setAll(contenido, capa);
        raiz.getStyleClass().add("pantalla");
        // Un clic fuera del panel lo cierra (y sigue su camino: si fue en otra materia, la abre).
        raiz.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (panel != null && !(e.getTarget() instanceof Node n && dentroDe(n, panel))) cerrarPanel();
        });
        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) cerrarPanel();
        });
        borrarAviso.setOnFinished(e -> aviso.setText(""));
        refrescar();
        return raiz;
    }

    private Modo modo(Kardex k, String archivo, boolean esPatio) {
        ReticulaView r = new ReticulaView(k, esPatio, this::abrir);
        HorarioView h = new HorarioView(k, this::cambio, this::abrir);
        h.setVisible(false);
        return new Modo(k, archivo, esPatio, r, h, new StackPane(r, h));
    }

    private HBox barra() {
        modos = segmentado("historia", this::cambiarModo,
                new String[][]{{"historia", "Historia"}, {"patio", "Patio de juegos"}});
        modos.getStyleClass().setAll("pestanas");
        vistas = segmentado("reticula", this::mostrar, new String[][]{{"reticula", "Retícula"}, {"horario", "Horario"}});

        // Barra de créditos con una marca en el 60 % (materias de especialidad).
        avance.getStyleClass().add("thin-bar");
        Region marca = new Region();
        marca.getStyleClass().add("marca");
        Pane barraCreditos = new Pane(avance, marca);
        barraCreditos.setPrefSize(150, 6);
        barraCreditos.setMaxHeight(6);
        avance.setManaged(false);
        avance.resizeRelocate(0, 0, 150, 6);
        marca.setManaged(false);
        marca.resizeRelocate(150 * 0.6 - 1, -3, 2, 12);
        creditos.getStyleClass().add("dato-valor");
        HBox filaCreditos = new HBox(10, creditos, barraCreditos);
        filaCreditos.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(filaCreditos, new Tooltip(
                "60 %: " + Kardex.creditosPara(60) + " créditos (materias de especialidad)\n"
                        + "70 %: " + Kardex.creditosPara(70) + " créditos (servicio social)\n"
                        + "80 %: " + Kardex.creditosPara(80) + " créditos (residencia)"));

        for (int s = 1; s <= Ruta.LIMITE; s++) {
            int n = s;
            MenuItem item = new MenuItem(s + "°");
            item.setOnAction(e -> {
                modo.kardex().datos.semestre = n;
                cambio();
            });
            semestre.getItems().add(item);
        }
        semestre.getStyleClass().addAll("flat", "semestre");

        MenuItem sync = new MenuItem("Actualizar grupos del ITL");
        sync.setOnAction(e -> actualizarGrupos(sync));
        MenuItem exportar = new MenuItem("Exportar imagen…");
        exportar.setOnAction(e -> exportar());
        MenuItem carpeta = new MenuItem("Abrir carpeta de datos");
        carpeta.setOnAction(e -> getHostServices().showDocument(almacen.carpeta.toUri().toString()));
        reiniciar.getStyleClass().add("danger");
        reiniciar.setOnAction(e -> reiniciar());
        MenuButton menu = new MenuButton("⋯", null, sync, exportar, carpeta, new SeparatorMenuItem(), reiniciar);
        menu.getStyleClass().addAll("flat", "more-menu");

        Region izq = new Region(), der = new Region();
        HBox.setHgrow(izq, Priority.ALWAYS);
        HBox.setHgrow(der, Priority.ALWAYS);
        aviso.getStyleClass().add("aviso-barra");
        HBox barra = new HBox(28, modos, vistas, izq, dato("PROMEDIO", promedio), dato("CRÉDITOS", filaCreditos),
                dato(cargaNombre, carga), dato("SEMESTRE", semestre), der, aviso, menu);
        barra.getStyleClass().add("barra");
        barra.setAlignment(Pos.CENTER_LEFT);
        return barra;
    }

    private HBox tira() {
        Label titulo = new Label("PATIO DE JUEGOS");
        titulo.getStyleClass().add("patio-titulo");
        Label sub = new Label("Pruebas libres: no cambian tu historia.");
        sub.getStyleClass().add("patio-sub");
        VBox nombre = new VBox(1, titulo, sub);
        rutaResumen.getStyleClass().add("ruta-resumen");
        rutaAviso.getStyleClass().add("panel-alerta");
        rutaAviso.setWrapText(true);
        rutaAviso.setMaxWidth(300);
        VBox resumen = new VBox(2, rutaResumen, rutaAviso);
        resumen.setAlignment(Pos.CENTER_LEFT);
        // Los semestres se acomodan en otra línea si no caben; lo demás nunca se recorta.
        rutaSemestres.setAlignment(Pos.CENTER_LEFT);
        rutaSemestres.setMinWidth(0);
        HBox.setHgrow(rutaSemestres, Priority.ALWAYS);
        Button copiar = new Button("Copiar mi historia");
        copiar.setOnAction(e -> copiarHistoria());
        for (Region r : List.of(nombre, resumen, armar, copiar)) r.setMinWidth(Region.USE_PREF_SIZE);
        tira.getChildren().setAll(nombre, rutaSemestres, resumen, armar, copiar);
        tira.getStyleClass().add("tira-patio");
        tira.setAlignment(Pos.CENTER_LEFT);
        return tira;
    }

    private static VBox dato(String nombre, Node valor) {
        return dato(new Label(nombre), valor);
    }

    private static VBox dato(Label nombre, Node valor) {
        nombre.getStyleClass().add("dato-nombre");
        valor.getStyleClass().add("dato-valor");
        VBox v = new VBox(2, nombre, valor);
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    /** Guarda y vuelve a pintar. Todo cambio del usuario pasa por aquí. */
    void cambio() {
        rutaAviso.setText("");
        try {
            almacen.guardar(modo.archivo(), modo.kardex());
        } catch (IOException e) {
            error(ventana(), "No pude guardar", e.getMessage());
        }
        refrescar();
    }

    void refrescar() {
        Kardex k = modo.kardex();
        modo.reticula().refrescar();
        modo.horario().refrescar();
        double p = k.promedio();
        promedio.setText(Double.isNaN(p) ? "—" : String.format(Locale.ROOT, "%.2f", p));
        int cr = k.creditosAprobados();
        creditos.setText(cr + " / " + Kardex.CREDITOS_TOTALES);
        avance.setProgress(Math.min(1, cr / (double) Kardex.CREDITOS_TOTALES));
        avance.getStyleClass().remove("complete");
        if (cr >= Kardex.CREDITOS_TOTALES) avance.getStyleClass().add("complete");
        cargaNombre.setText("CARGA · " + k.activo());
        carga.setText(k.carga() + " / " + k.cargaMaxima());
        semestre.setText(k.datos.semestre + "°  ▾");
        reiniciar.setText(modo.patio() ? "Vaciar el patio…" : "Reiniciar avance…");
        raiz.getStyleClass().remove("en-patio");
        if (modo.patio()) raiz.getStyleClass().add("en-patio");
        tira.setVisible(modo.patio());
        tira.setManaged(modo.patio());
        if (modo.patio()) refrescarRuta();
    }

    void cambiarModo(String cual) {
        cerrarPanel();
        modo = cual.equals("patio") ? patio : historia;
        historia.vista().setVisible(modo == historia);
        patio.vista().setVisible(modo == patio);
        seleccionar(modos, cual);
        refrescar();
    }

    void mostrar(String cual) {
        cerrarPanel();
        vista = cual;
        for (Modo m : List.of(historia, patio)) {
            m.reticula().setVisible(vista.equals("reticula"));
            m.horario().setVisible(vista.equals("horario"));
        }
        seleccionar(vistas, vista);
    }

    // ------------------------------------------------------------------
    // Patio: ruta
    // ------------------------------------------------------------------

    private void refrescarRuta() {
        Kardex k = patio.kardex();
        Ruta.Resultado r = Ruta.evaluar(k);
        rutaSemestres.getChildren().clear();
        for (Ruta.Semestre s : r.semestres()) {
            Label l = new Label(s.numero() + "° · " + s.creditos() + " cr");
            boolean mal = s.error() != null || s.materias().stream().anyMatch(m -> r.errores().containsKey(m.clave()));
            l.getStyleClass().addAll("ruta-semestre", mal ? "error" : Ruta.color(k, s.numero()));
            String materias = s.materias().stream().map(Kardex.Materia::nombre).collect(Collectors.joining("\n"));
            Tooltip t = new Tooltip(materias + (s.error() != null ? "\n\n" + s.error() : mal ? "\n\nHay materias que rompen alguna regla (en rojo)." : ""));
            t.setShowDelay(Duration.millis(300));
            Tooltip.install(l, t);
            rutaSemestres.getChildren().add(l);
        }
        rutaResumen.setText(r.semestres().isEmpty() ? "Sin ruta: usa «Armar ruta» o elige en qué semestre cursarías cada materia."
                : r.faltan() > 0 ? "Faltan " + r.faltan() + (r.faltan() == 1 ? " materia" : " materias") + " por acomodar"
                : "Terminas en el " + r.termina() + "°" + (r.termina() > Ruta.LIMITE ? ", pasa del límite de " + Ruta.LIMITE : ""));

        List<MenuItem> items = new ArrayList<>();
        MenuItem rapida = new MenuItem("Lo antes posible");
        rapida.setOnAction(e -> armarRuta(0));
        items.add(rapida);
        for (int s = Ruta.masPronto(k); s <= Ruta.LIMITE; s++) { // antes de eso es imposible
            int objetivo = s;
            MenuItem item = new MenuItem("Terminar en el " + s + "°");
            item.setOnAction(e -> armarRuta(objetivo));
            items.add(item);
        }
        MenuItem borrar = new MenuItem("Borrar la ruta");
        borrar.setOnAction(e -> {
            k.datos.ruta.clear();
            cambio();
        });
        items.add(new SeparatorMenuItem());
        items.add(borrar);
        armar.getItems().setAll(items);
    }

    private void armarRuta(int objetivo) {
        cerrarPanel();
        String problema = Ruta.armar(patio.kardex(), objetivo);
        cambio();
        rutaAviso.setText(problema == null ? "" : problema);
    }

    private void copiarHistoria() {
        if (!confirmar(ventana(), "Copiar mi historia",
                "El patio se reemplaza por una copia de tu historia actual y se borra la ruta.", "Copiar", false)) return;
        cerrarPanel();
        patio.kardex().reemplazar(historia.kardex().copia());
        cambio();
    }

    // ------------------------------------------------------------------
    // Panel de la materia
    // ------------------------------------------------------------------

    void abrir(Kardex.Materia m, Node desde) {
        cerrarPanel();
        panel = new MateriaPanel(modo.kardex(), m, modo.patio(), this::cambio, this::cerrarPanel);
        ancla = capa.sceneToLocal(desde.localToScene(desde.getBoundsInLocal()));
        capa.getChildren().setAll(panel);
        modo.reticula().resaltar(m.clave(), true);
    }

    void cerrarPanel() {
        if (panel == null) return;
        modo.reticula().resaltar(panel.materia().clave(), false);
        capa.getChildren().clear();
        panel = null;
    }

    /** A un lado de la materia (derecha, o izquierda si no cabe) y siempre dentro de la ventana. */
    private void ubicarPanel() {
        if (panel == null || ancla == null) return;
        double w = panel.prefWidth(-1), h = Math.min(panel.prefHeight(w), capa.getHeight() - 16);
        double x = ancla.getMaxX() + 10;
        if (x + w > capa.getWidth() - 8) x = ancla.getMinX() - 10 - w;
        x = Math.max(8, x);
        double y = Math.max(8, Math.min(ancla.getMinY(), capa.getHeight() - 8 - h));
        panel.resizeRelocate(x, y, w, h);
    }

    private static boolean dentroDe(Node n, Node padre) {
        for (Node p = n; p != null; p = p.getParent()) if (p == padre) return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private void actualizarGrupos(MenuItem boton) {
        boton.setDisable(true);
        avisar("Actualizando grupos del ITL…", false);
        Set<String> claves = modo.kardex().plan.stream().map(Kardex.Materia::clave).collect(Collectors.toSet());
        Thread hilo = new Thread(() -> {
            try {
                Map<String, List<Kardex.Grupo>> oferta = SyncITL.descargar(claves);
                Platform.runLater(() -> {
                    try {
                        almacen.guardarOferta(oferta);
                    } catch (IOException e) {
                        error(ventana(), "No pude guardar los grupos", e.getMessage());
                    }
                    historia.kardex().setOferta(oferta);
                    patio.kardex().setOferta(oferta);
                    refrescar();
                    avisar("Grupos actualizados: " + oferta.values().stream().mapToInt(List::size).sum(), true);
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    avisar("", true);
                    error(ventana(), "No pude actualizar los grupos", e.getMessage());
                });
            } finally {
                Platform.runLater(() -> boton.setDisable(false));
            }
        }, "sync-itl");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void avisar(String texto, boolean temporal) {
        aviso.setText(texto);
        borrarAviso.stop();
        if (temporal) borrarAviso.playFromStart();
    }

    private void exportar() {
        boolean enReticula = vista.equals("reticula");
        FileChooser fc = new FileChooser();
        fc.setTitle("Exportar imagen");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagen PNG", "*.png"));
        fc.setInitialFileName(enReticula ? (modo.patio() ? "Ruta.png" : "Retícula.png") : modo.kardex().activo() + ".png");
        File destino = fc.showSaveDialog(ventana());
        if (destino == null) return;
        WritableImage imagen = enReticula ? modo.reticula().imagen() : modo.horario().imagen();
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(imagen, null), "png", destino);
            avisar("Imagen guardada", true);
        } catch (IOException e) {
            error(ventana(), "No pude exportar la imagen", e.getMessage());
        }
    }

    private void reiniciar() {
        boolean ok = modo.patio()
                ? confirmar(ventana(), "Vaciar el patio", "El patio queda en blanco, como alumno de primer semestre. Tu historia no cambia.", "Vaciar", true)
                : confirmar(ventana(), "Reiniciar avance",
                "Se borran tus calificaciones, materias reprobadas y todos tus horarios. No se puede deshacer.", "Reiniciar", true);
        if (!ok) return;
        cerrarPanel();
        modo.kardex().reiniciar();
        cambio();
    }

    private Window ventana() {
        return capa.getScene() == null ? null : capa.getScene().getWindow();
    }

    // ------------------------------------------------------------------
    // Utilidades de interfaz
    // ------------------------------------------------------------------

    /** Botones mutuamente excluyentes; {@code opciones} son pares {valor, texto}. Siempre hay uno activo. */
    static HBox segmentado(String actual, Consumer<String> alCambiar, String[][] opciones) {
        ToggleGroup grupo = new ToggleGroup();
        HBox fila = new HBox(0);
        fila.getStyleClass().add("segmented");
        fila.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < opciones.length; i++) {
            ToggleButton b = new ToggleButton(opciones[i][1]);
            b.setToggleGroup(grupo);
            b.setUserData(opciones[i][0]);
            b.setSelected(opciones[i][0].equals(actual));
            b.setFocusTraversable(false);
            if (i == 0) b.getStyleClass().add("first");
            if (i == opciones.length - 1) b.getStyleClass().add("last");
            fila.getChildren().add(b);
        }
        grupo.selectedToggleProperty().addListener((obs, antes, ahora) -> {
            if (ahora == null) {
                if (antes != null) grupo.selectToggle(antes);
                return;
            }
            alCambiar.accept(String.valueOf(ahora.getUserData()));
        });
        return fila;
    }

    private static void seleccionar(HBox segmentado, String valor) {
        for (Node n : segmentado.getChildren())
            if (n instanceof ToggleButton b && valor.equals(b.getUserData()) && !b.isSelected()) b.setSelected(true);
    }

    /** "ZAPATA DAVILA JOSE LUIS" → "Zapata Davila Jose Luis". */
    static String nombrePropio(String texto) {
        if (texto == null || texto.isBlank() || texto.toUpperCase(Locale.ROOT).contains("ASIGNAR")) return "Por asignar";
        StringBuilder b = new StringBuilder();
        for (String p : texto.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!b.isEmpty()) b.append(' ');
            boolean menor = !b.isEmpty() && Set.of("de", "del", "la", "las", "los", "y").contains(p);
            b.append(menor ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        return b.toString();
    }

    /** 13.5 → "13:30". */
    static String hora(double h) {
        int minutos = (int) Math.round(h * 60);
        return minutos / 60 + ":" + String.format("%02d", minutos % 60);
    }

    // ------------------------------------------------------------------
    // Diálogos con el estilo de la app
    // ------------------------------------------------------------------

    private static void estilo(Dialog<?> d, Window dueno) {
        if (dueno != null) {
            d.initOwner(dueno);
            d.getDialogPane().getStylesheets().addAll(dueno.getScene().getStylesheets());
        } else {
            d.getDialogPane().getStylesheets().add(css());
        }
        d.getDialogPane().getStyleClass().add("dialog");
    }

    static void info(Window dueno, String titulo, String mensaje) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, mensaje);
        a.setTitle(titulo);
        a.setHeaderText(titulo);
        estilo(a, dueno);
        a.showAndWait();
    }

    static void error(Window dueno, String titulo, String mensaje) {
        Alert a = new Alert(Alert.AlertType.ERROR, mensaje);
        a.setTitle(titulo);
        a.setHeaderText(titulo);
        estilo(a, dueno);
        a.showAndWait();
    }

    static boolean confirmar(Window dueno, String titulo, String mensaje, String boton, boolean destructivo) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, mensaje);
        a.setTitle(titulo);
        a.setHeaderText(titulo);
        ButtonType si = new ButtonType(boton, ButtonBar.ButtonData.OK_DONE);
        a.getButtonTypes().setAll(si, new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));
        estilo(a, dueno);
        if (destructivo) a.getDialogPane().lookupButton(si).getStyleClass().add("danger");
        return a.showAndWait().filter(r -> r == si).isPresent();
    }

    /** Pide un texto no vacío; vacío si se cancela. */
    static Optional<String> pedirTexto(Window dueno, String titulo, String etiqueta, String inicial) {
        TextInputDialog d = new TextInputDialog(inicial);
        d.setTitle(titulo);
        d.setHeaderText(titulo);
        d.setContentText(etiqueta);
        estilo(d, dueno);
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    /** Ícono de la ventana: tres materias unidas por flechas sobre el morado de devava. */
    private static Image icono() {
        Canvas c = new Canvas(64, 64);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#4F46E5"));
        g.fillRoundRect(0, 0, 64, 64, 28, 28);
        g.setStroke(Color.web("#C7D2FE"));
        g.setLineWidth(3);
        g.strokeLine(27, 21, 37, 21);
        g.strokePolyline(new double[]{19, 19, 37}, new double[]{27, 43, 43}, 3);
        g.setFill(Color.WHITE);
        g.fillRoundRect(11, 15, 16, 12, 5, 5);
        g.fillRoundRect(37, 15, 16, 12, 5, 5);
        g.fillRoundRect(37, 37, 16, 12, 5, 5);
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return c.snapshot(sp, null);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
