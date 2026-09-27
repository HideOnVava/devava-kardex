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

/** Panel flotante de una materia: estado, calificación, grupos. */
final class MateriaPanel extends VBox {

    private static final PseudoClass INVALIDA = PseudoClass.getPseudoClass("invalida");

    private final Kardex kardex;
    private final Kardex.Materia m;
    private final Runnable alCambiar, cerrar;
    private String error;
    private boolean pedirCalificacion;

    MateriaPanel(Kardex kardex, Kardex.Materia m, Runnable alCambiar, Runnable cerrar) {
        this.kardex = kardex;
        this.m = m;
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

        List<String[]> opciones = new ArrayList<>(List.of(
                new String[]{"pendiente", "Pendiente"}, new String[]{"cursando", "Cursando"}, new String[]{"aprobada", "Aprobada"}));
        if (!m.sinCalificacion()) opciones.add(new String[]{"reprobada", "Reprobada"});
        String actual = switch (estado) {
            case APROBADA -> "aprobada";
            case CURSANDO -> "cursando";
            case REPROBADA -> "reprobada";
            default -> "pendiente";
        };
        HBox estados = KardexApp.segmentado(actual, this::cambiarEstado, opciones.toArray(String[][]::new));
        for (Node b : estados.getChildren()) {
            HBox.setHgrow(b, Priority.ALWAYS);
            ((Region) b).setMaxWidth(Double.MAX_VALUE);
        }
        getChildren().add(estados);

        if (estado == Kardex.Estado.APROBADA && !m.sinCalificacion()) getChildren().add(calificacion());

        String nota = nota(estado);
        if (nota != null) getChildren().add(texto(nota, estado == Kardex.Estado.BLOQUEADA ? "panel-alerta" : "panel-nota"));
        if (error != null) getChildren().add(texto(error, "panel-alerta"));
        error = null;

        if (estado != Kardex.Estado.APROBADA) {
            List<Kardex.Grupo> grupos = kardex.grupos(m);
            if (!grupos.isEmpty()) {
                Label t = new Label("GRUPOS");
                t.getStyleClass().add("dato-nombre");
                VBox lista = new VBox(2);
                Kardex.Grupo elegido = kardex.grupoActual(m);
                for (Kardex.Grupo g : grupos) lista.getChildren().add(fila(g, g.equals(elegido)));
                getChildren().add(new VBox(6, t, lista));
            } else if (!m.sinCalificacion()) {
                getChildren().add(texto("No hay grupos de esta materia en la oferta actual.", "panel-nota"));
            }
        }

        if (estado == Kardex.Estado.CURSANDO) {
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
        switch (valor) {
            case "pendiente" -> kardex.pendiente(m);
            case "cursando" -> error = kardex.cursar(m, null);
            case "aprobada" -> {
                kardex.aprobar(m, kardex.calificacion(m));
                pedirCalificacion = true;
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

    /** Se guarda mientras se escribe, en cuanto es una calificación válida (70 a 100) o se deja vacía. */
    private Node calificacion() {
        int cal = kardex.calificacion(m);
        TextField campo = new TextField(cal > 0 ? String.valueOf(cal) : "");
        campo.setPromptText("70–100");
        campo.setPrefColumnCount(4);
        campo.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,3}") ? c : null));
        campo.textProperty().addListener((o, antes, t) -> {
            boolean valida = t.isEmpty() || Integer.parseInt(t) >= 70 && Integer.parseInt(t) <= 100;
            campo.pseudoClassStateChanged(INVALIDA, !valida && t.length() >= 2 && !t.equals("10"));
            if (valida) {
                kardex.aprobar(m, t.isEmpty() ? 0 : Integer.parseInt(t));
                alCambiar.run();
            }
        });
        campo.setMinWidth(Region.USE_PREF_SIZE);
        if (pedirCalificacion) Platform.runLater(campo::requestFocus);
        pedirCalificacion = false;
        HBox fila = new HBox(10, new Label("Calificación"), campo);
        fila.setAlignment(Pos.CENTER_LEFT);
        Label ayuda = texto("Escríbela para que cuente en tu promedio.", "panel-nota");
        ayuda.visibleProperty().bind(campo.textProperty().isEmpty());
        ayuda.managedProperty().bind(ayuda.visibleProperty());
        return new VBox(6, fila, ayuda);
    }

    private String nota(Kardex.Estado estado) {
        int veces = kardex.vecesReprobada(m);
        if (estado == Kardex.Estado.BLOQUEADA) return kardex.bloqueo(m);
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

    private static Label texto(String contenido, String estilo) {
        Label l = new Label(contenido);
        l.getStyleClass().add(estilo);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }
}
