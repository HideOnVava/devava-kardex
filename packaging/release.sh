#!/usr/bin/env bash
#
# Arma en dist/release los archivos de una versión para esta plataforma (la contraparte de
# release.ps1 en Linux y macOS):
#   Linux:  Devava-Kardex-<versión>-linux-<arq>.tar.gz   la app sin instalar
#           Devava-Kardex-<versión>-linux-<arq>.deb      paquete para Debian/Ubuntu
#           Devava-Kardex-<versión>-linux-<arq>.rpm      paquete para Fedora/openSUSE (si hay rpmbuild)
#   macOS:  Devava-Kardex-<versión>-macos-<arq>.dmg      imagen de disco con "Devava Kardex.app"
#   ambos:  SHA256SUMS.txt                                sumas SHA-256 de los archivos
#
# Las notas del release las arma release-notes.ps1 cuando ya están los archivos de todas las
# plataformas (lo hace el workflow .github/workflows/release.yml).
#
# Uso: packaging/release.sh [--jdk-home CARPETA] [--output CARPETA]

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
output="$project_dir/dist/release"
jdk_args=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jdk-home) jdk_args=(--jdk-home "$2"); shift 2 ;;
        --output) output="$2"; shift 2 ;;
        *) echo "Opción desconocida: $1" >&2; exit 2 ;;
    esac
done

case "$(uname -s)" in
    Linux) os="linux" ;;
    Darwin) os="macos" ;;
    *) echo "Sistema no soportado: $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) arch="x64" ;;
    arm64|aarch64) arch="arm64" ;;
    *) echo "Arquitectura no soportada: $(uname -m)" >&2; exit 2 ;;
esac

version="$(sed -n 's/^    <version>\(.*\)<\/version>$/\1/p' "$project_dir/pom.xml" | head -1)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "La versión '$version' de pom.xml no es de release (x.y.z)" >&2; exit 1; }
grep -q "^## \[$version\]" "$project_dir/CHANGELOG.md" || { echo "CHANGELOG.md no tiene la sección '## [$version]'. Agrégala antes de publicar." >&2; exit 1; }

build="$script_dir/build-unix.sh"
stage="$project_dir/target/package/dist"
base="Devava-Kardex-$version-$os-$arch"

rm -rf "$output" "$stage"
mkdir -p "$output"

"$build" ${jdk_args[@]+"${jdk_args[@]}"} --type app-image --dest "$stage"

if [[ "$os" == "linux" ]]; then
    # Sin instalar: una carpeta raíz; se abre con "Devava Kardex/bin/Devava Kardex".
    tar -C "$stage" -czf "$output/$base.tar.gz" "Devava Kardex"
    echo "Tar: $output/$base.tar.gz"

    "$build" ${jdk_args[@]+"${jdk_args[@]}"} --type deb --reuse-image --dest "$stage"
    mv "$stage"/*.deb "$output/$base.deb"
    echo "Paquete: $output/$base.deb"

    if command -v rpmbuild >/dev/null 2>&1; then
        "$build" ${jdk_args[@]+"${jdk_args[@]}"} --type rpm --reuse-image --dest "$stage"
        mv "$stage"/*.rpm "$output/$base.rpm"
        echo "Paquete: $output/$base.rpm"
    else
        echo "No hay rpmbuild: no armo el .rpm"
    fi
else
    "$build" ${jdk_args[@]+"${jdk_args[@]}"} --type dmg --reuse-image --dest "$stage"
    mv "$stage"/*.dmg "$output/$base.dmg"
    echo "Imagen de disco: $output/$base.dmg"
fi

# --- Sumas (mismo formato que sha256sum) ---------------------------------------------------
cd "$output"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum Devava-Kardex-* > SHA256SUMS.txt
else
    shasum -a 256 Devava-Kardex-* > SHA256SUMS.txt
fi

echo
echo "Archivos de Devava Kardex $version en $output:"
ls -l "$output"
