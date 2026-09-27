package com.devavaxp.kardex;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.Shape;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/** La retícula completa con flechas de seriación, escalada para caber en la ventana. */
final class ReticulaView extends Region {

    private static final double RADIO = 6, PUNTA = 7;

    private final Kardex kardex;
    private final boolean patio;
    private final BiConsumer<Kardex.Materia, Node> alElegir;
    private final Map<String, Integer> planeadas = new HashMap<>();
    private Ruta.Resultado ruta;
    private final Pane lienzo = new Pane();
    private final Scale escala = new Scale(1, 1, 0, 0);
    private final List<Flechas.Flecha> flechas;
    private final List<Shape[]> trazos = new ArrayList<>();
    private final int semestres, filas;
    private final double ancho, alto;

    ReticulaView(Kardex kardex, boolean patio, BiConsumer<Kardex.Materia, Node> alElegir) {
        this.kardex = kardex;
        this.patio = patio;
        this.alElegir = alElegir;
        flechas = Flechas.trazar(kardex.plan);
        semestres = kardex.plan.stream().mapToInt(m -> m.semestre() + m.ancho() - 1).max().orElse(1);
        filas = kardex.plan.stream().mapToInt(Kardex.Materia::fila).max().orElse(1);
        ancho = Flechas.x(semestres) + Flechas.W + Flechas.X0;
        alto = Flechas.y(filas) + Flechas.H + Flechas.GY + 34;
        lienzo.getTransforms().add(escala);
        lienzo.setManaged(false);
        getChildren().add(lienzo);
        getStyleClass().add("reticula");
        setMinSize(0, 0);
    }

    @Override
    protected void layoutChildren() {
        double s = Math.min(getWidth() / ancho, getHeight() / alto);
        escala.setX(s);
        escala.setY(s);
        lienzo.resizeRelocate((getWidth() - ancho * s) / 2, (getHeight() - alto * s) / 2, ancho, alto);
    }

    void refrescar() {
        lienzo.getChildren().clear();
        trazos.clear();
        planeadas.clear();
        ruta = patio ? Ruta.evaluar(kardex) : null;
        if (ruta != null) ruta.semestres().forEach(s -> s.materias().forEach(m -> planeadas.put(m.clave(), s.numero())));
        for (int s = 1; s <= semestres; s++) {
            Label l = new Label("Semestre " + s);
            l.getStyleClass().add("semestre");
            l.setAlignment(Pos.CENTER);
            colocar(l, Flechas.x(s), 12, Flechas.W, 20);
        }
        for (Flechas.Flecha f : flechas) {
            List<double[]> p = f.puntos();
            double[] fin = p.get(p.size() - 1);
            Path linea = camino(p);
            linea.getStyleClass().add("flecha");
            Polygon punta = new Polygon(fin[0], fin[1], fin[0] - PUNTA, fin[1] - 4, fin[0] - PUNTA, fin[1] + 4);
            punta.getStyleClass().add("punta");
            lienzo.getChildren().addAll(linea, punta);
            trazos.add(new Shape[]{linea, punta});
        }
        for (Kardex.Materia m : kardex.plan) tarjeta(m);
        leyenda();
    }

    private void tarjeta(Kardex.Materia m) {
        Kardex.Estado estado = kardex.estado(m);
        // "Autómatas I", "en iOS": la palabra corta final no se queda sola en otra línea.
        Label nombre = new Label(m.nombre().replaceAll(" (\\S{1,3})$", " $1"));
        nombre.getStyleClass().add("nombre");
        nombre.setWrapText(true);
        Label clave = new Label(m.clave());
        Label dato = new Label(dato(m, estado));
        clave.getStyleClass().add("detalle");
        dato.getStyleClass().addAll("detalle", "dato");
        Region hueco = new Region(), relleno = new Region();
        HBox.setHgrow(hueco, Priority.ALWAYS);
        VBox.setVgrow(relleno, Priority.ALWAYS);
        VBox t = new VBox(nombre, relleno, new HBox(clave, hueco, dato));
        t.getStyleClass().addAll("materia", estado.name().toLowerCase(Locale.ROOT));
        if (m.optativa()) t.getStyleClass().add("optativa");
        Tooltip ayuda = new Tooltip(ayuda(m, estado));
        ayuda.setShowDelay(Duration.millis(400));
        ayuda.setWrapText(true);
        ayuda.setMaxWidth(320);
        Tooltip.install(t, ayuda);
        t.setOnMouseEntered(e -> resaltar(m.clave(), true));
        t.setOnMouseExited(e -> resaltar(m.clave(), false));
        t.setOnMouseClicked(e -> alElegir.accept(m, t));
        double x = Flechas.x(m.semestre()), y = Flechas.y(m.fila()), w = Flechas.ancho(m.ancho());
        colocar(t, x, y, w, Flechas.H);

        // Patio: en la esquina, el semestre en que la cursarías (en rojo si rompe alguna regla).
        Integer s = planeadas.get(m.clave());
        if (s != null) {
            Label insignia = new Label(s + "°");
            insignia.getStyleClass().addAll("insignia", ruta.errores().containsKey(m.clave()) ? "error"
                    : "p" + Math.min(s - kardex.datos.semestre, 6));
            insignia.setAlignment(Pos.CENTER);
            insignia.setMouseTransparent(true);
            colocar(insignia, x + w - 34, y - 9, 40, 20);
        }
    }

    /** Esquina inferior derecha de la tarjeta: calificación, repetición/especial o créditos. */
    private String dato(Kardex.Materia m, Kardex.Estado estado) {
        if (estado == Kardex.Estado.APROBADA) {
            int cal = kardex.calificacion(m);
            return cal > 0 ? String.valueOf(cal) : "✓";
        }
        int veces = kardex.vecesReprobada(m);
        if (veces >= 2) return "Especial";
        if (veces == 1) return "Repetición";
        return m.creditos() + " cr";
    }

    private String ayuda(Kardex.Materia m, Kardex.Estado estado) {
        String detalle = switch (estado) {
            case APROBADA -> kardex.calificacion(m) > 0 ? "Aprobada con " + kardex.calificacion(m) : "Aprobada";
            case CURSANDO -> {
                Kardex.Grupo g = kardex.grupoActual(m);
                yield g == null ? "Cursando, sin grupo elegido" : "Cursando en el grupo " + g.letra();
            }
            case REPROBADA -> "Reprobada";
            case DISPONIBLE -> "Puedes cursarla";
            case BLOQUEADA -> kardex.bloqueo(m);
        };
        Integer s = planeadas.get(m.clave());
        String plan = s == null ? "" : "\nLa cursarías en el " + s + "°."
                + (ruta.errores().containsKey(m.clave()) ? " " + ruta.errores().get(m.clave()) : "");
        return m.nombre() + (m.optativa() ? " (optativa)" : "") + "\n" + detalle + plan;
    }

    private void leyenda() {
        HBox fila = new HBox(18);
        fila.setAlignment(Pos.CENTER_LEFT);
        String[][] items = {{"aprobada", "Aprobada"}, {"cursando", "Cursando"}, {"reprobada", "Reprobada"},
                {"disponible", "Disponible"}, {"bloqueada", "Bloqueada"}, {"disponible optativa", "Optativa"}};
        for (String[] item : items) {
            Region muestra = new Region();
            muestra.getStyleClass().addAll(("materia muestra " + item[0]).split(" "));
            Label texto = new Label(item[1]);
            texto.getStyleClass().add("leyenda");
            fila.getChildren().add(new HBox(6, muestra, texto));
        }
        colocar(fila, Flechas.X0, alto - 30, ancho - 2 * Flechas.X0, 20);
    }

    /** Resalta las flechas que llegan a la materia o salen de ella. */
    void resaltar(String clave, boolean si) {
        for (int i = 0; i < flechas.size(); i++) {
            Flechas.Flecha f = flechas.get(i);
            if (!f.desde().equals(clave) && !f.hacia().equals(clave)) continue;
            for (Shape s : trazos.get(i)) {
                s.getStyleClass().remove("activa");
                if (si) {
                    s.getStyleClass().add("activa");
                    s.toFront();
                }
            }
        }
    }

    /** Polilínea con las esquinas redondeadas; termina antes de la punta de flecha. */
    private static Path camino(List<double[]> p) {
        Path path = new Path(new MoveTo(p.get(0)[0], p.get(0)[1]));
        for (int i = 1; i < p.size() - 1; i++) {
            double[] a = p.get(i - 1), b = p.get(i), c = p.get(i + 1);
            double r = Math.min(RADIO, Math.min(distancia(a, b), distancia(b, c)) / 2);
            double[] d1 = direccion(a, b), d2 = direccion(b, c);
            path.getElements().add(new LineTo(b[0] - d1[0] * r, b[1] - d1[1] * r));
            path.getElements().add(new QuadCurveTo(b[0], b[1], b[0] + d2[0] * r, b[1] + d2[1] * r));
        }
        double[] fin = p.get(p.size() - 1);
        path.getElements().add(new LineTo(fin[0] - PUNTA + 1, fin[1]));
        return path;
    }

    private static double distancia(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private static double[] direccion(double[] a, double[] b) {
        double d = distancia(a, b);
        return d == 0 ? new double[]{0, 0} : new double[]{(b[0] - a[0]) / d, (b[1] - a[1]) / d};
    }

    private void colocar(Region n, double x, double y, double w, double h) {
        n.setManaged(false);
        lienzo.getChildren().add(n);
        n.resizeRelocate(x, y, w, h);
    }

    /** La retícula a doble resolución, sin importar el tamaño de la ventana. */
    WritableImage imagen() {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.web("#F5F5F7"));
        sp.setTransform(new Scale(2 / escala.getX(), 2 / escala.getY()));
        return lienzo.snapshot(sp, null);
    }
}
