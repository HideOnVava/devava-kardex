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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Devava Kardex: la retícula con tu avance y tu horario, en una ventana. */
public final class KardexApp extends Application {

    static final String NOMBRE = "Devava Kardex";

    private Almacen almacen;
    private Kardex kardex;
    private ReticulaView reticula;
    private HorarioView horario;
    private HBox vistas;
    private final Label promedio = new Label(), creditos = new Label(), carga = new Label(), cargaNombre = new Label();
    private final Label aviso = new Label();
    private final ProgressBar avance = new ProgressBar(0);
    private final PauseTransition borrarAviso = new PauseTransition(Duration.seconds(6));

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
        Kardex cargado;
        try {
            cargado = almacen.cargar();
        } catch (IOException | RuntimeException e) {
            error(null, "No pude abrir tus datos", e.getMessage() + "\n\nCarpeta: " + almacen.carpeta);
            Platform.exit();
            return;
        }
        Scene scene = new Scene(crearUI(cargado, almacen), 1280, 800);
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

    Parent crearUI(Kardex kardex, Almacen almacen) {
        this.kardex = kardex;
        this.almacen = almacen;
        reticula = new ReticulaView(kardex, this::abrir);
        horario = new HorarioView(kardex, this::cambio, this::abrir);
        horario.setVisible(false);

        BorderPane contenido = new BorderPane(new StackPane(reticula, horario));
        contenido.setTop(barra());
        capa.setPickOnBounds(false);
        StackPane raiz = new StackPane(contenido, capa);
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

    private HBox barra() {
        vistas = segmentado("reticula", v -> mostrar(v), new String[][]{{"reticula", "Retícula"}, {"horario", "Horario"}});

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

        MenuItem sync = new MenuItem("Actualizar grupos del ITL");
        sync.setOnAction(e -> actualizarGrupos(sync));
        MenuItem exportar = new MenuItem("Exportar imagen…");
        exportar.setOnAction(e -> exportar());
        MenuItem carpeta = new MenuItem("Abrir carpeta de datos");
        carpeta.setOnAction(e -> getHostServices().showDocument(almacen.carpeta.toUri().toString()));
        MenuItem reiniciar = new MenuItem("Reiniciar avance…");
        reiniciar.getStyleClass().add("danger");
        reiniciar.setOnAction(e -> reiniciar());
        MenuButton menu = new MenuButton("⋯", null, sync, exportar, carpeta, new SeparatorMenuItem(), reiniciar);
        menu.getStyleClass().addAll("flat", "more-menu");

        Region izq = new Region(), der = new Region();
        HBox.setHgrow(izq, Priority.ALWAYS);
        HBox.setHgrow(der, Priority.ALWAYS);
        aviso.getStyleClass().add("aviso-barra");
        HBox barra = new HBox(32, vistas, izq, dato("PROMEDIO", promedio), dato("CRÉDITOS", filaCreditos),
                dato(cargaNombre, carga), der, aviso, menu);
        barra.getStyleClass().add("barra");
        barra.setAlignment(Pos.CENTER_LEFT);
        return barra;
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
        try {
            almacen.guardar(kardex);
        } catch (IOException e) {
            error(ventana(), "No pude guardar", e.getMessage());
        }
        refrescar();
    }

    void refrescar() {
        reticula.refrescar();
        horario.refrescar();
        double p = kardex.promedio();
        promedio.setText(Double.isNaN(p) ? "—" : String.format(Locale.ROOT, "%.2f", p));
        int cr = kardex.creditosAprobados();
        creditos.setText(cr + " / " + Kardex.CREDITOS_TOTALES);
        avance.setProgress(Math.min(1, cr / (double) Kardex.CREDITOS_TOTALES));
        avance.getStyleClass().remove("complete");
        if (cr >= Kardex.CREDITOS_TOTALES) avance.getStyleClass().add("complete");
        cargaNombre.setText("CARGA · " + kardex.activo());
        carga.setText(kardex.carga() + " / " + kardex.cargaMaxima());
    }

    void mostrar(String vista) {
        cerrarPanel();
        reticula.setVisible(vista.equals("reticula"));
        horario.setVisible(vista.equals("horario"));
        seleccionar(vistas, vista);
    }

    // ------------------------------------------------------------------
    // Panel de la materia
    // ------------------------------------------------------------------

    void abrir(Kardex.Materia m, Node desde) {
        cerrarPanel();
        panel = new MateriaPanel(kardex, m, this::cambio, this::cerrarPanel);
        ancla = capa.sceneToLocal(desde.localToScene(desde.getBoundsInLocal()));
        capa.getChildren().setAll(panel);
        reticula.resaltar(m.clave(), true);
    }

    void cerrarPanel() {
        if (panel == null) return;
        reticula.resaltar(panel.materia().clave(), false);
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
        Set<String> claves = kardex.plan.stream().map(Kardex.Materia::clave).collect(Collectors.toSet());
        Thread hilo = new Thread(() -> {
            try {
                Map<String, List<Kardex.Grupo>> oferta = SyncITL.descargar(claves);
                Platform.runLater(() -> {
                    try {
                        almacen.guardarOferta(oferta);
                    } catch (IOException e) {
                        error(ventana(), "No pude guardar los grupos", e.getMessage());
                    }
                    kardex.setOferta(oferta);
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
        boolean enReticula = reticula.isVisible();
        FileChooser fc = new FileChooser();
        fc.setTitle("Exportar imagen");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagen PNG", "*.png"));
        fc.setInitialFileName(enReticula ? "Retícula.png" : kardex.activo() + ".png");
        File destino = fc.showSaveDialog(ventana());
        if (destino == null) return;
        WritableImage imagen = enReticula ? reticula.imagen() : horario.imagen();
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(imagen, null), "png", destino);
            avisar("Imagen guardada", true);
        } catch (IOException e) {
            error(ventana(), "No pude exportar la imagen", e.getMessage());
        }
    }

    private void reiniciar() {
        if (confirmar(ventana(), "Reiniciar avance",
                "Se borran tus calificaciones, materias reprobadas y todos tus horarios. No se puede deshacer.",
                "Reiniciar", true)) {
            cerrarPanel();
            kardex.reiniciar();
            cambio();
        }
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
