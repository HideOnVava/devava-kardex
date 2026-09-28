#!/usr/bin/env bash
#
# Construye Devava Kardex para Linux o macOS con jlink y jpackage del JDK (la contraparte de
# build-windows.ps1):
#
#   1. Compila con el wrapper de Maven y copia las dependencias.
#   2. Arma con jlink un runtime recortado: los módulos del JDK de jdk-modules.txt y JavaFX.
#   3. jpackage crea la imagen de la app en target/package/image ("Devava Kardex" en Linux,
#      "Devava Kardex.app" en macOS). La app corre desde el class path; JavaFX va como módulos
#      dentro del runtime.
#   4. --type app-image (por defecto): copia la imagen a <dest>.
#      --type deb | rpm (Linux) o dmg (macOS): arma el instalador desde la imagen, en <dest>.
#      deb necesita fakeroot y rpm, rpmbuild; dmg no necesita nada más.
#
# Requiere un JDK 21 completo (con la carpeta jmods): --jdk-home, JAVA_HOME o el java del PATH.
# La versión se lee de pom.xml.
#
# Uso: packaging/build-unix.sh [--type TIPO] [--dest CARPETA] [--jdk-home CARPETA] [--reuse-image]

set -euo pipefail

app_name="Devava Kardex"
vendor="devava XP Studios"
description="Kardex, retícula y horario del ITL"
about_url="https://github.com/HideOnVava/devava-kardex"
maintainer="HideOnVava <HideOnVava@users.noreply.github.com>"
javafx_modules="javafx.base,javafx.graphics,javafx.controls,javafx.swing"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
type="app-image"
dest="$project_dir/dist"
jdk_home="${JAVA_HOME:-}"
reuse_image=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --type) type="$2"; shift 2 ;;
        --dest) dest="$2"; shift 2 ;;
        --jdk-home) jdk_home="$2"; shift 2 ;;
        --reuse-image) reuse_image=true; shift ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "Opción desconocida: $1" >&2; exit 2 ;;
    esac
done

# --- Sistema --------------------------------------------------------------------------
case "$(uname -s)" in
    Linux) os="linux" ;;
    Darwin) os="mac" ;;
    *) echo "Sistema no soportado: $(uname -s). En Windows usa build-windows.ps1." >&2; exit 2 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) classifier="$os" ;;
    arm64|aarch64) classifier="$os-aarch64" ;;
    *) echo "Arquitectura no soportada: $(uname -m)" >&2; exit 2 ;;
esac
case "$type" in
    app-image) ;;
    deb|rpm) [[ "$os" == "linux" ]] || { echo "--type $type solo existe en Linux" >&2; exit 2; } ;;
    dmg) [[ "$os" == "mac" ]] || { echo "--type $type solo existe en macOS" >&2; exit 2; } ;;
    *) echo "--type $type no existe (app-image, deb, rpm, dmg)" >&2; exit 2 ;;
esac

# --- JDK --------------------------------------------------------------------------------
if [[ -z "$jdk_home" ]]; then
    if [[ "$os" == "mac" ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
        jdk_home="$(/usr/libexec/java_home -v 21)"
    elif command -v java >/dev/null 2>&1; then
        java_bin="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
        jdk_home="$(cd "$(dirname "$java_bin")/.." && pwd)"
    fi
fi
for tool in jlink jpackage; do
    [[ -x "$jdk_home/bin/$tool" ]] || { echo "No encontré $tool en $jdk_home/bin. Define JAVA_HOME con un JDK 21 completo (o usa --jdk-home)." >&2; exit 1; }
done
[[ -d "$jdk_home/jmods" ]] || { echo "$jdk_home no tiene la carpeta jmods: hace falta un JDK 21 completo." >&2; exit 1; }
export JAVA_HOME="$jdk_home"

app_version="$(sed -n 's/^    <version>\(.*\)<\/version>$/\1/p' "$project_dir/pom.xml" | head -1)"
[[ -n "$app_version" ]] || { echo "No pude leer la versión de pom.xml" >&2; exit 1; }
jdk_modules="$(grep -v '^#' "$script_dir/jdk-modules.txt" | grep -v '^\s*$' | paste -sd, -)"

work="$project_dir/target/package"
image_dir="$work/image"
if [[ "$os" == "mac" ]]; then image="$image_dir/$app_name.app"; icon="$script_dir/icon.icns"; else image="$image_dir/$app_name"; icon="$script_dir/icon.png"; fi

cd "$project_dir"

if $reuse_image && [[ -d "$image" ]]; then
    echo "Uso la imagen de la app en $image"
else
    # --- Compilar ---------------------------------------------------------------------
    ./mvnw -q -B clean package -DskipTests
    ./mvnw -q -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

    app_jar="$(ls target/devava-kardex-*.jar | head -1)"
    [[ -f "$app_jar" ]] || { echo "No encontré el jar de la app en target/" >&2; exit 1; }

    # Los jars de JavaFX de esta plataforma van al runtime; el resto de las librerías, al class path.
    javafx_jars=(target/dependency/javafx-*-"$classifier".jar)
    [[ ${#javafx_jars[@]} -eq 4 ]] || { echo "Esperaba 4 jars de JavaFX '$classifier' en target/dependency y hay ${#javafx_jars[@]}" >&2; exit 1; }

    rm -rf "$work"
    mkdir -p "$work/input" "$image_dir"
    cp "$app_jar" "$work/input/"
    for jar in target/dependency/*.jar; do
        [[ "$(basename "$jar")" == javafx-* ]] || cp "$jar" "$work/input/"
    done

    # --- Runtime: módulos del JDK + JavaFX -------------------------------------------------
    module_path="$jdk_home/jmods"
    for jar in "${javafx_jars[@]}"; do module_path="$module_path:$jar"; done
    "$jdk_home/bin/jlink" \
        --module-path "$module_path" \
        --add-modules "$jdk_modules,$javafx_modules" \
        --output "$work/runtime" \
        --strip-debug --no-header-files --no-man-pages --compress zip-6

    # --- Imagen de la app ------------------------------------------------------------------
    mac_opts=()
    if [[ "$os" == "mac" ]]; then
        mac_opts=(--mac-package-identifier com.devavaxp.kardex --mac-package-name "$app_name")
    fi
    "$jdk_home/bin/jpackage" \
        --type app-image \
        --name "$app_name" \
        --app-version "$app_version" \
        --vendor "$vendor" \
        --copyright "Copyright (c) 2026 $vendor" \
        --description "$description" \
        --icon "$icon" \
        --runtime-image "$work/runtime" \
        --input "$work/input" \
        --main-jar "$(basename "$app_jar")" \
        --main-class com.devavaxp.kardex.KardexApp \
        --dest "$image_dir" \
        --java-options "-Dfile.encoding=UTF-8" \
        ${mac_opts[@]+"${mac_opts[@]}"}
fi

mkdir -p "$dest"

if [[ "$type" == "app-image" ]]; then
    target="$dest/$(basename "$image")"
    [[ -e "$target" ]] && rm -rf "$target"
    cp -R "$image" "$target"
    echo "Listo: $target"
else
    # --- Instalador desde la imagen --------------------------------------------------------
    if [[ "$os" == "linux" ]]; then
        extra=(--linux-package-name devava-kardex
               --linux-shortcut
               --linux-menu-group "Education;"
               --linux-app-category education
               --license-file "$project_dir/LICENSE")
        [[ "$type" == "deb" ]] && extra+=(--linux-deb-maintainer "$maintainer")
        [[ "$type" == "rpm" ]] && extra+=(--linux-rpm-license-type MIT)
    else
        extra=(--mac-package-identifier com.devavaxp.kardex)
    fi
    "$jdk_home/bin/jpackage" \
        --type "$type" \
        --app-image "$image" \
        --name "$app_name" \
        --app-version "$app_version" \
        --vendor "$vendor" \
        --copyright "Copyright (c) 2026 $vendor" \
        --description "$description" \
        --about-url "$about_url" \
        --dest "$dest" \
        "${extra[@]}"
    echo "Listo: $(ls -t "$dest"/*."$type" | head -1)"
fi
