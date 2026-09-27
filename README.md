# Devava Kardex

Tu kardex, la retícula de la carrera y tu horario en una sola ventana. Hecha por
**devava XP Studios** para Ingeniería en Sistemas Computacionales del ITL (Tecnológico de La Laguna).

- **Dos modos.** *Historia* es tu avance real: una materia solo queda aprobada al escribir su
  calificación (70 a 100). *Patio de juegos* es una copia para probar lo que quieras sin tocar
  tu historia: apruebas al instante, armas horarios de prueba y planeas tu **ruta**.
- **Ruta hasta titularte (patio).** Eliges en qué semestre cursarías cada materia que falta, o
  *Armar ruta* la acomoda sola: lo antes posible o con la carga más ligera que todavía termina
  en el 10°, 11° o 12°. Al ingresar, el 1er semestre lleva sí o sí las 6 materias de 1er semestre.
  Cada semestre muestra sus créditos y lo que rompa una regla sale en rojo.
- **Retícula completa con sus flechas de seriación.** Cada materia muestra si está aprobada
  (con su calificación), en curso, reprobada, disponible o bloqueada. Al pasar el mouse sobre
  una materia se resaltan las flechas que llegan a ella y las que salen de ella.
- **Promedio, créditos y carga en tiempo real**, con la meta del 60 % marcada en la barra.
- **Reglas del TecNM:** seriación, 60 % de créditos para especialidad, 70 % para servicio
  social, 80 % + servicio social + complementarias para residencia, residencia sola o con hasta
  2 materias más, carga máxima de 36/30/20/10 créditos según repeticiones y cursos especiales, y
  ningún cruce de horario. Servicio social y complementarias no se cursan ni son carga: solo se
  marcan aprobadas al terminarlas.
- **Varios horarios** (para comparar opciones). Lo que está en el horario activo es lo que
  cursas.
- **Grupos del ITL:** menú ⋯ → *Actualizar grupos del ITL* baja la oferta vigente de la
  consulta pública de horarios.
- **Exportar** la retícula o el horario como PNG.

## Uso

Haz clic en una materia para marcarla como *Pendiente*, *Cursando*, *Aprobada* (con
calificación) o *Reprobada*, o para elegir un grupo. En *Horario* ves la semana del horario
activo y desde el selector creas, renombras o eliminas horarios. Arriba a la derecha indicas en
qué semestre vas: la ruta del patio empieza en el siguiente.

En el patio, *Copiar mi historia* vuelve a empezar desde tu avance real.

## Tus datos

Se guardan en `Documentos/Devava Kardex` (menú ⋯ → *Abrir carpeta de datos*):

| Archivo | Qué guarda |
| --- | --- |
| `kardex.json` | Tu historia: materias aprobadas y reprobadas, semestre y horarios. |
| `patio.json` | El patio de juegos, con su ruta. |
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
