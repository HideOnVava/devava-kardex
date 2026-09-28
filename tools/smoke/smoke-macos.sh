#!/usr/bin/env bash
#
# Prueba de humo del build de macOS: abre la app, entra a Principal con el teclado (System
# Events necesita el permiso de Accesibilidad; los runners de GitHub lo dan), guarda capturas y
# el registro de la app, y la cierra con Cmd+Q. Falla si la app se cae, no termina bien o
# registra una excepción.
#
# Uso: tools/smoke/smoke-macos.sh <app> <carpeta-de-salida>
#   <app>  p. ej. "/Applications/Devava Kardex.app"

set -euo pipefail
app="$1"; out="$2"
mkdir -p "$out"

# Los runners tienen una pantalla chica (1024x768): se esconde el Dock para que quepa la ventana.
defaults write com.apple.dock autohide -bool true && killall Dock 2>/dev/null || true
sleep 2

"$app/Contents/MacOS/Devava Kardex" >"$out/app.log" 2>&1 &
app_pid=$!

# Espera la ventana (hasta ~60 s).
for _ in $(seq 1 60); do
    if osascript -e 'tell application "System Events" to (exists window 1 of process "Devava Kardex")' 2>/dev/null | grep -q true; then
        break
    fi
    kill -0 "$app_pid" 2>/dev/null || { echo "La app se cerró antes de tiempo"; cat "$out/app.log"; exit 1; }
    sleep 1
done
sleep 6

se()   { osascript -e 'tell application "System Events" to tell process "Devava Kardex"' "$@" -e 'end tell'; }
shot() { screencapture -x "$out/$1.png"; echo "captura: $1"; }

shot 01-menu
if se -e 'set frontmost to true' >/dev/null 2>&1; then
    se -e 'set frontmost to true' -e 'key code 36' >/dev/null   # Return: el menú abre con "Principal" enfocado
    sleep 4
    shot 02-principal
else
    echo "Sin automatización de la interfaz en esta sesión (Accesibilidad): solo reviso que abra."
fi

se -e 'set frontmost to true' -e 'keystroke "q" using command down' >/dev/null 2>&1 \
    || osascript -e 'tell application "Devava Kardex" to quit' >/dev/null 2>&1 || true
status=""
for _ in $(seq 1 30); do
    if ! kill -0 "$app_pid" 2>/dev/null; then
        wait "$app_pid" && status=0 || status=$?
        break
    fi
    sleep 1
done
if [[ -z "$status" ]]; then
    echo "La app no se cerró"; kill "$app_pid" || true; cat "$out/app.log"; exit 1
fi
echo "la app terminó con código $status"
[[ $status -eq 0 ]] || { cat "$out/app.log"; exit 1; }
# JavaFX en macOS a veces escribe solo el encabezado 'Exception in thread "InvokeLaterDispatcher"'
# (sin traza) mientras ya se está cerrando tras Cmd+Q; cualquier otra excepción es una falla.
if grep -i "exception" "$out/app.log" | grep -qv '^Exception in thread "InvokeLaterDispatcher" *$'; then
    echo "El registro tiene una excepción:"; cat "$out/app.log"; exit 1
fi
echo "Prueba de humo superada"
