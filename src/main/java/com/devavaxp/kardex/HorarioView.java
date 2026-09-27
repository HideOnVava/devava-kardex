package com.devavaxp.kardex;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Semana de lunes a viernes con las materias del horario activo. */
final class HorarioView extends BorderPane {

    private static final String[] DIAS = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes"};
    private static final double COLUMNA_HORA = 52, CABEZA = 32;
    private static final int COLORES = 8;

    private final Kardex kardex;
    private final Runnable alCambiar;
    private final BiConsumer<Kardex.Materia, Node> alElegir;
    private final MenuButton selector = new MenuButton();
    private final Pane semana = new Pane();
    private final FlowPane sinGrupo = new FlowPane(8, 8);

    HorarioView(Kardex kardex, Runnable alCambiar, BiConsumer<Kardex.Materia, Node> alElegir) {
        this.kardex = kardex;
        this.alCambiar = alCambiar;
        this.alElegir = alElegir;
        selector.getStyleClass().addAll("flat", "selector");
        selector.setMnemonicParsing(false); // "layout_1" se ve con guion bajo
        HBox cabeza = new HBox(selector);
        cabeza.getStyleClass().add("horario-cabeza");
        sinGrupo.getStyleClass().add("sin-grupo");
        semana.getStyleClass().add("semana");
        semana.setMinSize(0, 0);
        semana.widthProperty().addListener(o -> dibujar());
        semana.heightProperty().addListener(o -> dibujar());
        setTop(cabeza);
        setCenter(semana);
        setBottom(sinGrupo);
        getStyleClass().add("horario");
    }

    void refrescar() {
        armarSelector();
        dibujar();
    }

    private void armarSelector() {
        selector.setText(kardex.activo() + "  ▾");
        ToggleGroup grupo = new ToggleGroup();
        List<MenuItem> items = new ArrayList<>();
        for (String nombre : kardex.horarios()) {
            RadioMenuItem item = new RadioMenuItem(nombre);
            item.setMnemonicParsing(false);
            item.setToggleGroup(grupo);
            item.setSelected(nombre.equals(kardex.activo()));
            item.setOnAction(e -> {
                kardex.activar(nombre);
                alCambiar.run();
            });
            items.add(item);
        }
        MenuItem nuevo = new MenuItem("Nuevo horario…");
        nuevo.setOnAction(e -> KardexApp.pedirTexto(ventana(), "Nuevo horario", "Nombre:", "Horario " + (kardex.horarios().size() + 1))
                .ifPresent(n -> aplicar(kardex.nuevoHorario(n))));
        MenuItem renombrar = new MenuItem("Renombrar…");
        renombrar.setOnAction(e -> KardexApp.pedirTexto(ventana(), "Renombrar horario", "Nombre:", kardex.activo())
                .ifPresent(n -> aplicar(kardex.renombrarHorario(n))));
        MenuItem eliminar = new MenuItem("Eliminar este horario");
        eliminar.getStyleClass().add("danger");
        eliminar.setDisable(kardex.horarios().size() < 2);
        eliminar.setOnAction(e -> {
            if (KardexApp.confirmar(ventana(), "Eliminar horario", "Se elimina «" + kardex.activo() + "». Tu avance no cambia.", "Eliminar", true)) {
                kardex.eliminarHorario();
                alCambiar.run();
            }
        });
        items.add(new SeparatorMenuItem());
        items.add(nuevo);
        items.add(renombrar);
        items.add(eliminar);
        selector.getItems().setAll(items);
    }

    private void aplicar(String error) {
        if (error != null) KardexApp.error(ventana(), "Horarios", error);
        alCambiar.run();
    }

    private void dibujar() {
        semana.getChildren().clear();
        sinGrupo.getChildren().clear();
        double w = semana.getWidth(), h = semana.getHeight();

        record Bloque(Kardex.Materia materia, Kardex.Grupo grupo, Kardex.Clase clase, int color) {}
        List<Bloque> bloques = new ArrayList<>();
        int color = 0;
        for (Map.Entry<String, String> e : kardex.horario().entrySet()) {
            Kardex.Materia m = kardex.materia(e.getKey());
            if (m == null) continue;
            Kardex.Grupo g = kardex.grupoActual(m);
            List<Kardex.Clase> clases = g == null ? List.of() : Kardex.clases(g);
            if (clases.isEmpty()) {
                Button chip = new Button(m.nombre());
                chip.setMnemonicParsing(false);
                chip.getStyleClass().add("chip");
                chip.setOnAction(ev -> alElegir.accept(m, chip));
                sinGrupo.getChildren().add(chip);
            }
            for (Kardex.Clase c : clases) bloques.add(new Bloque(m, g, c, color));
            color++;
        }
        if (!sinGrupo.getChildren().isEmpty()) {
            Label titulo = new Label("Sin grupo:");
            titulo.getStyleClass().add("dato-nombre");
            sinGrupo.getChildren().add(0, titulo);
        }
        sinGrupo.setVisible(!sinGrupo.getChildren().isEmpty());
        sinGrupo.setManaged(sinGrupo.isVisible());
        if (w <= COLUMNA_HORA || h <= CABEZA) return;

        int desde = 7, hasta = 20;
        for (Bloque b : bloques) {
            desde = Math.min(desde, (int) Math.floor(b.clase().inicio()));
            hasta = Math.max(hasta, (int) Math.ceil(b.clase().fin()));
        }
        double dia = (w - COLUMNA_HORA) / DIAS.length, hora = (h - CABEZA) / (hasta - desde);
        for (int d = 0; d < DIAS.length; d++) {
            Label l = new Label(DIAS[d]);
            l.getStyleClass().add("dia");
            l.setAlignment(Pos.CENTER);
            colocar(l, COLUMNA_HORA + d * dia, 0, dia, CABEZA);
        }
        for (int hr = desde; hr <= hasta; hr++) {
            double y = CABEZA + (hr - desde) * hora;
            Line linea = new Line(COLUMNA_HORA, y, w, y);
            linea.getStyleClass().add("linea");
            semana.getChildren().add(linea);
            if (hr < hasta) {
                Label l = new Label(KardexApp.hora(hr));
                l.getStyleClass().add("hora");
                colocar(l, 0, y + 4, COLUMNA_HORA - 10, 16);
                l.setAlignment(Pos.TOP_RIGHT);
            }
        }
        for (Bloque b : bloques) {
            Kardex.Clase c = b.clase();
            Label nombre = new Label(b.materia().nombre());
            nombre.getStyleClass().add("nombre");
            nombre.setWrapText(true);
            nombre.setMinHeight(Region.USE_PREF_SIZE);
            Label detalle = new Label(b.grupo().letra() + (c.aula().isEmpty() ? "" : " · " + c.aula())
                    + " · " + KardexApp.nombrePropio(b.grupo().profesor()));
            detalle.getStyleClass().add("detalle");
            VBox bloque = new VBox(1, nombre, detalle);
            bloque.getStyleClass().addAll("bloque", "c" + b.color() % COLORES);
            bloque.setOnMouseClicked(e -> alElegir.accept(b.materia(), bloque));
            double alto = (c.fin() - c.inicio()) * hora - 4;
            Rectangle recorte = new Rectangle(dia - 6, alto);
            recorte.setArcWidth(16);
            recorte.setArcHeight(16);
            bloque.setClip(recorte);
            colocar(bloque, COLUMNA_HORA + c.dia() * dia + 3, CABEZA + (c.inicio() - desde) * hora + 2, dia - 6, alto);
        }
        if (kardex.horario().isEmpty()) {
            Label vacio = new Label("Este horario está vacío. En la retícula, haz clic en una materia y elige un grupo.");
            vacio.getStyleClass().add("vacio");
            vacio.setAlignment(Pos.CENTER);
            colocar(vacio, COLUMNA_HORA, CABEZA, w - COLUMNA_HORA, h - CABEZA);
        }
    }

    private void colocar(Region n, double x, double y, double w, double h) {
        n.setManaged(false);
        semana.getChildren().add(n);
        n.resizeRelocate(x, y, w, h);
    }

    private Window ventana() {
        return getScene() == null ? null : getScene().getWindow();
    }

    /** La semana a doble resolución. */
    WritableImage imagen() {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.web("#F5F5F7"));
        sp.setTransform(new Scale(2, 2));
        return semana.snapshot(sp, null);
    }
}
