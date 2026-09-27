# Devava Kardex

Tu kardex, la retícula de la carrera y tu horario en una sola ventana. Hecha por
**devava XP Studios** para Ingeniería en Sistemas Computacionales del ITL (Tecnológico de La Laguna).

- **Retícula completa con sus flechas de seriación.** Cada materia muestra si está aprobada
  (con su calificación), en curso, reprobada, disponible o bloqueada. Al pasar el mouse sobre
  una materia se resaltan las flechas que llegan a ella y las que salen de ella.
- **Promedio, créditos y carga en tiempo real**, con la meta del 60 % marcada en la barra.
- **Reglas del TecNM:** seriación, 60 % de créditos para especialidad, 70 % para servicio
  social, 80 % + servicio social + complementarias para residencia, carga máxima de 36/30/20/10
  créditos según repeticiones y cursos especiales, y ningún cruce de horario.
- **Varios horarios** (para comparar opciones). Lo que está en el horario activo es lo que
  cursas.
- **Grupos del ITL:** menú ⋯ → *Actualizar grupos del ITL* baja la oferta vigente de la
  consulta pública de horarios.
- **Exportar** la retícula o el horario como PNG.

## Uso

Haz clic en una materia para marcarla como *Pendiente*, *Cursando*, *Aprobada* (con
calificación) o *Reprobada*, o para elegir un grupo. En *Horario* ves la semana del horario
activo y desde el selector creas, renombras o eliminas horarios.

## Tus datos

Se guardan en `Documentos/Devava Kardex` (menú ⋯ → *Abrir carpeta de datos*):

| Archivo | Qué guarda |
| --- | --- |
| `kardex.json` | Materias aprobadas y reprobadas, y tus horarios. |
| `oferta.json` | Los grupos descargados del ITL. |

La primera vez importa el avance de la beta (*KardexByVava*, en `Documents/KardexITL_Data`);
esa carpeta no se modifica.

El plan de estudios está en
[`plan-isc.json`](src/main/resources/com/devavaxp/kardex/plan-isc.json): por materia, su
semestre, créditos, `requisitos`, `porcentaje` de créditos requerido, y `fila` (el renglón donde
se dibuja, elegido para que ninguna flecha cruce una tarjeta).

## Compilar y ejecutar

Requiere JDK 21.

```bash
./mvnw javafx:run
```

```bash
./mvnw test
```

## Licencia

[MIT](LICENSE)
