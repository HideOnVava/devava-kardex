package com.devavaxp.kardex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Geometría de la retícula y rutas de las flechas de seriación. Como en la retícula impresa,
 * las flechas van en ángulo recto por los pasillos entre columnas y filas, así que nunca
 * atraviesan una tarjeta. Si dos flechas comparten pasillo se reparten en carriles, salvo que
 * salgan de la misma materia o lleguen a la misma (entonces se juntan, como un tronco).
 */
final class Flechas {

    /** Tarjeta, pasillos y márgenes, en px sin escalar. */
    static final double W = 150, H = 84, GX = 46, GY = 30, X0 = 16, Y0 = 52;
    private static final double CARRIL = 7, DESFASE = 9, HOLGURA = 14;

    /** Una flecha: de la materia requisito a la que la necesita, como polilínea de puntos {x, y}. */
    record Flecha(String desde, String hacia, List<double[]> puntos) {}

    private Flechas() {
    }

    static double x(int semestre) {
        return X0 + (semestre - 1) * (W + GX);
    }

    static double y(int fila) {
        return Y0 + (fila - 1) * (H + GY);
    }

    static double ancho(int columnas) {
        return columnas * W + (columnas - 1) * GX;
    }

    /** Pasillo vertical a la derecha de la columna. */
    private static double pasillo(int semestre) {
        return x(semestre) + W + GX / 2;
    }

    /** Pasillo horizontal justo arriba de la fila. */
    private static double calle(int fila) {
        return y(fila) - GY / 2;
    }

    private static final class Ruta {
        final String desde, hacia;
        final double xs, ys, xt, ye;
        final List<Tramo> tramos = new ArrayList<>();

        Ruta(String desde, String hacia, double xs, double ys, double xt, double ye) {
            this.desde = desde;
            this.hacia = hacia;
            this.xs = xs;
            this.ys = ys;
            this.xt = xt;
            this.ye = ye;
        }
    }

    /** Pedazo de ruta dentro de un pasillo: vertical (canal = x) u horizontal (canal = y). */
    private static final class Tramo {
        final Ruta ruta;
        final boolean vertical;
        final double canal, lo, hi;
        double carril;

        Tramo(Ruta ruta, boolean vertical, double canal, double a, double b) {
            this.ruta = ruta;
            this.vertical = vertical;
            this.canal = canal;
            this.lo = Math.min(a, b);
            this.hi = Math.max(a, b);
        }
    }

    static List<Flecha> trazar(List<Kardex.Materia> plan) {
        Map<String, Kardex.Materia> porClave = new HashMap<>();
        Set<Long> ocupadas = new HashSet<>();
        for (Kardex.Materia m : plan) {
            porClave.put(m.clave(), m);
            for (int c = 0; c < m.ancho(); c++) ocupadas.add(celda(m.semestre() + c, m.fila()));
        }

        List<Ruta> rutas = new ArrayList<>();
        for (Kardex.Materia t : plan) {
            for (String requisito : t.requisitos()) {
                Kardex.Materia s = porClave.get(requisito);
                // Como en la retícula oficial: servicio social y complementarias van como nota, sin flecha.
                if (s == null || s.seAcredita()) continue;
                int cs = s.semestre() + s.ancho() - 1, ct = t.semestre();
                if (ct <= cs) continue; // requisito en el mismo semestre o después: no se dibuja
                double xs = x(s.semestre()) + ancho(s.ancho()), ys = y(s.fila()) + H / 2;
                double xt = x(ct), yt = y(t.fila()) + H / 2;
                boolean recta = s.fila() == t.fila()
                        && IntStream.range(cs + 1, ct).noneMatch(c -> ocupadas.contains(celda(c, s.fila())));
                if (recta) {
                    rutas.add(new Ruta(s.clave(), t.clave(), xs, ys, xt, yt));
                } else if (ct == cs + 1) {
                    // Columnas vecinas: baja o sube por el pasillo entre las dos.
                    Ruta r = new Ruta(s.clave(), t.clave(), xs, ys, xt, yt + (ys < yt ? -DESFASE : DESFASE));
                    r.tramos.add(new Tramo(r, true, pasillo(cs), ys, r.ye));
                    rutas.add(r);
                } else if (s.fila() == t.fila()) {
                    // Misma fila con tarjetas en medio: por la calle de arriba o la de abajo, la que
                    // menos se encime con las flechas ya trazadas.
                    Ruta arriba = porCalle(s, t, cs, ct, xs, ys, xt, yt, calle(t.fila()));
                    Ruta abajo = porCalle(s, t, cs, ct, xs, ys, xt, yt, calle(t.fila() + 1));
                    rutas.add(choques(abajo, rutas) < choques(arriba, rutas) ? abajo : arriba);
                } else {
                    // Más lejos: por la calle junto a la fila destino, del lado de donde viene.
                    rutas.add(porCalle(s, t, cs, ct, xs, ys, xt, yt, t.fila() < s.fila() ? calle(t.fila() + 1) : calle(t.fila())));
                }
            }
        }
        repartirCarriles(rutas);

        List<Flecha> flechas = new ArrayList<>();
        for (Ruta r : rutas) {
            List<double[]> p = new ArrayList<>();
            p.add(new double[]{r.xs, r.ys});
            if (r.tramos.size() == 1) {
                double x = r.tramos.get(0).canal + r.tramos.get(0).carril;
                p.add(new double[]{x, r.ys});
                p.add(new double[]{x, r.ye});
            } else if (r.tramos.size() == 3) {
                double x1 = r.tramos.get(0).canal + r.tramos.get(0).carril;
                double yc = r.tramos.get(1).canal + r.tramos.get(1).carril;
                double x2 = r.tramos.get(2).canal + r.tramos.get(2).carril;
                p.add(new double[]{x1, r.ys});
                p.add(new double[]{x1, yc});
                p.add(new double[]{x2, yc});
                p.add(new double[]{x2, r.ye});
            }
            p.add(new double[]{r.xt, r.ye});
            flechas.add(new Flecha(r.desde, r.hacia, p));
        }
        return flechas;
    }

    /** Por el pasillo de salida hasta la calle, por la calle hasta el pasillo de llegada y de ahí a la tarjeta. */
    private static Ruta porCalle(Kardex.Materia s, Kardex.Materia t, int cs, int ct,
                                 double xs, double ys, double xt, double yt, double calle) {
        Ruta r = new Ruta(s.clave(), t.clave(), xs, ys, xt, yt + (calle < yt ? -DESFASE : DESFASE));
        r.tramos.add(new Tramo(r, true, pasillo(cs), ys, calle));
        r.tramos.add(new Tramo(r, false, calle, pasillo(cs), pasillo(ct - 1)));
        r.tramos.add(new Tramo(r, true, pasillo(ct - 1), calle, r.ye));
        return r;
    }

    /** Cuántos tramos de otras flechas (ni del mismo origen ni al mismo destino) caen en el mismo pasillo. */
    private static int choques(Ruta r, List<Ruta> trazadas) {
        int n = 0;
        for (Ruta o : trazadas) {
            if (o.desde.equals(r.desde) || o.hacia.equals(r.hacia)) continue;
            for (Tramo a : r.tramos)
                for (Tramo b : o.tramos)
                    if (a.vertical == b.vertical && a.canal == b.canal && a.lo < b.hi + HOLGURA && b.lo < a.hi + HOLGURA) n++;
        }
        return n;
    }

    /** Carriles 0, +1, -1, +2… dentro de cada pasillo, para que dos flechas distintas no se encimen. */
    private static void repartirCarriles(List<Ruta> rutas) {
        Map<String, List<Tramo>> porCanal = new LinkedHashMap<>();
        for (Ruta r : rutas)
            for (Tramo t : r.tramos) porCanal.computeIfAbsent((t.vertical ? "V" : "H") + t.canal, k -> new ArrayList<>()).add(t);
        for (List<Tramo> canal : porCanal.values()) {
            canal.sort(Comparator.comparingDouble(t -> t.lo));
            List<List<Tramo>> carriles = new ArrayList<>();
            for (Tramo t : canal) {
                int k = 0;
                while (k < carriles.size() && chocan(t, carriles.get(k))) k++;
                if (k == carriles.size()) carriles.add(new ArrayList<>());
                carriles.get(k).add(t);
                t.carril = (k % 2 == 1 ? 1 : -1) * ((k + 1) / 2) * CARRIL;
            }
        }
    }

    private static boolean chocan(Tramo t, List<Tramo> carril) {
        for (Tramo o : carril) {
            boolean juntas = t.ruta.desde.equals(o.ruta.desde) || t.ruta.hacia.equals(o.ruta.hacia);
            if (!juntas && t.lo < o.hi + HOLGURA && o.lo < t.hi + HOLGURA) return true;
        }
        return false;
    }

    private static long celda(int semestre, int fila) {
        return semestre * 1000L + fila;
    }
}
