#!/usr/bin/env bash
#
# Prueba de humo del build de Linux: abre la app en una pantalla virtual (Xvfb + openbox), entra
# a Principal con el teclado, guarda capturas y el registro de la app, y la cierra como lo haría
# alguien (pidiéndole a la ventana que se cierre). Falla si la app se cae, no muestra su ventana,
# no termina bien o registra una excepción.
#
# Uso: tools/smoke/smoke-linux.sh <lanzador> <carpeta-de-salida>
#   <lanzador>  p. ej. "/opt/devava-kardex/bin/Devava Kardex"
# Necesita: xvfb, openbox, xdotool, wmctrl, imagemagick

set -euo pipefail
launcher="$1"; out="$2"
mkdir -p "$out"

export DISPLAY=:99
Xvfb :99 -screen 0 1600x1000x24 >/dev/null 2>&1 &
sleep 2
openbox >/dev/null 2>&1 &
sleep 1

"$launcher" >"$out/app.log" 2>&1 &
app_pid=$!

# Espera la ventana (hasta ~60 s).
win() { xdotool search --onlyvisible --name '^Devava Kardex$' 2>/dev/null | head -1 || true; }
for _ in $(seq 1 60); do
    [[ -n "$(win)" ]] && break
    kill -0 "$app_pid" 2>/dev/null || { echo "La app se cerró antes de mostrar su ventana"; cat "$out/app.log"; exit 1; }
    sleep 1
done
[[ -n "$(win)" ]] || { echo "No apareció la ventana"; cat "$out/app.log"; exit 1; }
sleep 4

shot() { import -window root "$out/$1.png"; echo "captura: $1"; }
key()  { xdotool windowactivate --sync "$(win)" key --delay 120 "$@"; sleep "${WAIT:-1}"; }

shot 01-menu
WAIT=4 key Return               # el menú abre con "Principal" enfocado
shot 02-principal

wmctrl -i -c "$(win)"
status=""
for _ in $(seq 1 30); do
    if ! kill -0 "$app_pid" 2>/dev/null; then
        wait "$app_pid" && status=0 || status=$?
        break
    fi
    sleep 1
done
if [[ -z "$status" ]]; then
    echo "La app no terminó al cerrar la ventana. Ventanas:"; wmctrl -l || true
    kill "$app_pid" || true
    cat "$out/app.log"; exit 1
fi
echo "la app terminó con código $status"
[[ $status -eq 0 ]] || { cat "$out/app.log"; exit 1; }
if grep -qi "exception" "$out/app.log"; then
    echo "El registro tiene una excepción:"; cat "$out/app.log"; exit 1
fi
echo "Prueba de humo superada"
