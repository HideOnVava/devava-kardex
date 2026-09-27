package com.devavaxp.kardex;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Panel flotante de una materia: estado, calificación, grupos. En la historia una materia con
 * calificación solo queda aprobada al escribirla (70 a 100); en el patio se aprueba al instante
 * y además se elige el semestre en que se cursaría.
 */
final class MateriaPanel extends VBox {

    private static final PseudoClass INVALIDA = PseudoClass.getPseudoClass("invalida");

    private final Kardex kardex;
    private final Kardex.Materia m;
    private final boolean patio;
    private final Runnable alCambiar, cerrar;
    private String error;
    private boolean aprobando, enfocar;

    MateriaPanel(Kardex kardex, Kardex.Materia m, boolean patio, Runnable alCambiar, Runnable cerrar) {
        this.kardex = kardex;
        this.m = m;
        this.patio = patio;
        this.alCambiar = alCambiar;
        this.cerrar = cerrar;
        getStyleClass().add("panel");
        setPrefWidth(360);
        construir();
    }

    Kardex.Materia materia() {
        return m;
    }

    private void construir() {
        getChildren().clear();
        Kardex.Estado estado = kardex.estado(m);
        boolean aprobada = estado == Kardex.Estado.APROBADA || aprobando;

        Label titulo = new Label(m.nombre());
        titulo.getStyleClass().add("panel-titulo");
        titulo.setWrapText(true);
        titulo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titulo, Priority.ALWAYS);
        Button x = new Button("✕");
        x.setMinWidth(Region.USE_PREF_SIZE);
        x.getStyleClass().addAll("flat", "cerrar");
        x.setOnAction(e -> cerrar.run());
        HBox cabeza = new HBox(8, titulo, x);
        cabeza.setAlignment(Pos.TOP_LEFT);
        Label sub = new Label(m.clave() + " · " + m.creditos() + " créditos · Semestre " + m.semestre()
                + (m.optativa() ? " · Optativa" : ""));
        sub.getStyleClass().add("panel-sub");
        getChildren().addAll(new VBox(2, cabeza, sub));

        List<String[]> opciones = m.seAcredita()
                ? List.of(new String[]{"pendiente", "Pendiente"}, new String[]{"aprobada", "Aprobada"})
                : List.of(new String[]{"pendiente", "Pendiente"}, new String[]{"cursando", "Cursando"},
                        new String[]{"aprobada", "Aprobada"}, new String[]{"reprobada", "Reprobada"});
        String actual = aprobada ? "aprobada" : switch (estado) {
            case CURSANDO -> "cursando";
            case REPROBADA -> "reprobada";
            default -> "pendiente";
        };
        getChildren().add(llenar(KardexApp.segmentado(actual, this::cambiarEstado, opciones.toArray(String[][]::new))));

        if (aprobada && !m.seAcredita()) getChildren().add(calificacion());

        String nota = nota(estado);
        // En el patio, si la ruta ya la acomoda bien, que hoy esté bloqueada es lo esperado: va en gris.
        boolean bienPlaneada = patio && kardex.datos.ruta.containsKey(m.clave())
                && !Ruta.evaluar(kardex).errores().containsKey(m.clave());
        if (nota != null && !aprobando) getChildren().add(texto((bienPlaneada ? "Hoy: " : "") + nota,
                estado == Kardex.Estado.BLOQUEADA && !bienPlaneada ? "panel-alerta" : "panel-nota"));
        if (error != null) getChildren().add(texto(error, "panel-alerta"));
        error = null;

        // Servicio social y complementarias no van en la ruta: se hacen cuando uno quiera.
        if (patio && !aprobada && estado != Kardex.Estado.CURSANDO && !m.seAcredita()) getChildren().add(semestrePlaneado());

        if (!aprobada && !m.seAcredita()) {
            List<Kardex.Grupo> grupos = kardex.grupos(m);
            if (!grupos.isEmpty()) {
                Label t = new Label("GRUPOS");
                t.getStyleClass().add("dato-nombre");
                VBox lista = new VBox(2);
                Kardex.Grupo elegido = kardex.grupoActual(m);
                for (Kardex.Grupo g : grupos) lista.getChildren().add(fila(g, g.equals(elegido)));
                getChildren().add(new VBox(6, t, lista));
            } else {
                getChildren().add(texto("No hay grupos de esta materia en la oferta actual.", "panel-nota"));
            }
        }

        if (estado == Kardex.Estado.CURSANDO && !aprobando) {
            Button quitar = new Button("Quitar del horario");
            quitar.getStyleClass().add("flat");
            quitar.setOnAction(e -> {
                kardex.quitar(m);
                cambio();
            });
            getChildren().add(quitar);
        }
    }

    private void cambiarEstado(String valor) {
        aprobando = false;
        switch (valor) {
            case "pendiente" -> kardex.pendiente(m);
            case "cursando" -> error = kardex.cursar(m, null);
            case "aprobada" -> {
                if (patio || m.seAcredita()) kardex.aprobar(m, kardex.calificacion(m));
                else aprobando = true; // en la historia se aprueba al escribir la calificación
                enfocar = true;
            }
            case "reprobada" -> kardex.reprobar(m);
            default -> { }
        }
        cambio();
    }

    private void cambio() {
        alCambiar.run();
        construir();
    }

    /** Se guarda mientras se escribe, en cuanto es una calificación válida. En el patio puede quedar vacía. */
    private Node calificacion() {
        int cal = kardex.calificacion(m);
        TextField campo = new TextField(cal > 0 ? String.valueOf(cal) : "");
        campo.setPromptText("70–100");
        campo.setPrefColumnCount(4);
        campo.setMinWidth(Region.USE_PREF_SIZE);
        campo.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,3}") ? c : null));
        campo.textProperty().addListener((o, antes, t) -> {
            boolean valida = !t.isEmpty() && Integer.parseInt(t) >= 70 && Integer.parseInt(t) <= 100;
            campo.pseudoClassStateChanged(INVALIDA, !valida && t.length() >= 2 && !t.equals("10"));
            if (!valida && !(patio && t.isEmpty())) return;
            boolean recienAprobada = aprobando;
            kardex.aprobar(m, valida ? Integer.parseInt(t) : 0);
            aprobando = false;
            alCambiar.run();
            if (recienAprobada) {
                enfocar = true;
                construir();
            }
        });
        if (enfocar) Platform.runLater(() -> {
            campo.requestFocus();
            campo.end();
        });
        enfocar = false;
        HBox fila = new HBox(10, new Label("Calificación"), campo);
        fila.setAlignment(Pos.CENTER_LEFT);
        String ayuda = aprobando ? "Escríbela para aprobarla: cuenta en tu promedio."
                : patio ? "Opcional: si la escribes, cuenta en el promedio del patio."
                : "Escríbela para que cuente en tu promedio.";
        Label nota = texto(ayuda, "panel-nota");
        if (!aprobando) {
            nota.visibleProperty().bind(campo.textProperty().isEmpty());
            nota.managedProperty().bind(nota.visibleProperty());
        }
        return new VBox(6, fila, nota);
    }

    /** Patio: en qué semestre futuro la cursarías, y qué regla rompe si no se puede. */
    private Node semestrePlaneado() {
        int desde = Ruta.inicio(kardex), hasta = Math.max(Ruta.LIMITE, desde + 2);
        Integer planeado = kardex.datos.ruta.get(m.clave());
        List<String[]> opciones = new ArrayList<>();
        opciones.add(new String[]{"", "—"});
        for (int s = desde; s <= hasta; s++) opciones.add(new String[]{String.valueOf(s), s + "°"});
        if (planeado != null && (planeado < desde || planeado > hasta))
            opciones.add(new String[]{String.valueOf(planeado), planeado + "°"});
        HBox semestres = llenar(KardexApp.segmentado(planeado == null ? "" : String.valueOf(planeado), v -> {
            kardex.planear(m, v.isEmpty() ? null : Integer.valueOf(v));
            cambio();
        }, opciones.toArray(String[][]::new)));
        Label t = new Label("LA CURSARÍAS EN");
        t.getStyleClass().add("dato-nombre");
        VBox caja = new VBox(6, t, semestres);
        String problema = Ruta.evaluar(kardex).errores().get(m.clave());
        if (problema != null) caja.getChildren().add(texto(problema, "panel-alerta"));
        return caja;
    }

    private String nota(Kardex.Estado estado) {
        int veces = kardex.vecesReprobada(m);
        if (estado == Kardex.Estado.BLOQUEADA) return kardex.bloqueo(m);
        if (m.seAcredita() && estado != Kardex.Estado.APROBADA)
            return "No se cursa ni cuenta en tu carga: márcala aprobada cuando la termines.";
        if (estado == Kardex.Estado.REPROBADA || estado == Kardex.Estado.CURSANDO) {
            if (veces >= 2) return "Reprobada " + veces + " veces: va en curso especial.";
            if (veces == 1) return "Reprobada una vez: va en curso de repetición.";
        }
        if (m.optativa() && estado != Kardex.Estado.APROBADA) {
            String otras = kardex.plan.stream().filter(Kardex.Materia::optativa).filter(o -> !o.equals(m))
                    .map(Kardex.Materia::nombre).collect(Collectors.joining(" o "));
            return "Optativa: debes aprobar esta o " + otras + ".";
        }
        return null;
    }

    private Node fila(Kardex.Grupo g, boolean elegido) {
        List<Kardex.Clase> clases = Kardex.clases(g);
        String cuando = clases.isEmpty() ? "sin horario" : cuando(clases);
        String cruce = elegido ? null : kardex.choque(m, g);
        String aulas = clases.stream().map(Kardex.Clase::aula).filter(a -> !a.isEmpty()).distinct()
                .collect(Collectors.joining(", "));
        Label arriba = new Label("Grupo " + g.letra() + "   " + cuando);
        Label abajo = new Label(cruce != null ? "Se cruza con " + cruce
                : KardexApp.nombrePropio(g.profesor()) + (aulas.isEmpty() ? "" : " · " + aulas));
        arriba.getStyleClass().add("grupo-titulo");
        abajo.getStyleClass().add("grupo-detalle");
        VBox fila = new VBox(2, arriba, abajo);
        fila.getStyleClass().add("grupo");
        if (elegido) fila.getStyleClass().add("elegido");
        if (cruce != null) fila.getStyleClass().add("choca");
        fila.setOnMouseClicked(e -> {
            error = kardex.cursar(m, g.letra());
            cambio();
        });
        return fila;
    }

    /** "10:00–11:00 · lunes a viernes", o por horas si algún día cambia: "10:00–11:00 Lu Ma Mi Ju · 10:00–12:00 Vi". */
    private static String cuando(List<Kardex.Clase> clases) {
        Map<String, List<Kardex.Clase>> porHora = clases.stream().collect(Collectors.groupingBy(
                c -> KardexApp.hora(c.inicio()) + "–" + KardexApp.hora(c.fin()), LinkedHashMap::new, Collectors.toList()));
        if (porHora.size() == 1 && clases.size() == 5) return porHora.keySet().iterator().next() + " · lunes a viernes";
        String[] abreviados = {"Lu", "Ma", "Mi", "Ju", "Vi"};
        return porHora.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue().stream().map(c -> abreviados[c.dia()]).collect(Collectors.joining(" ")))
                .collect(Collectors.joining(" · "));
    }

    /** Los botones del segmentado se reparten todo el ancho del panel. */
    private static HBox llenar(HBox segmentado) {
        for (Node b : segmentado.getChildren()) {
            HBox.setHgrow(b, Priority.ALWAYS);
            ((Region) b).setMaxWidth(Double.MAX_VALUE);
        }
        return segmentado;
    }

    private static Label texto(String contenido, String estilo) {
        Label l = new Label(contenido);
        l.getStyleClass().add(estilo);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }
}
