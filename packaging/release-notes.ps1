<#
.SYNOPSIS
    Arma el texto del release de GitHub con los archivos construidos en cada plataforma.

.DESCRIPTION
    Busca los archivos Devava-Kardex-<versión>-* en -Dir y sus subcarpetas (el workflow baja una
    carpeta por plataforma), calcula sus SHA-256 y escribe en -Dir:
      SHA256SUMS.txt      una línea por archivo, en formato sha256sum
      RELEASE_NOTES.md    la plantilla release-notes.md con la tabla de descargas por sistema,
                          la sección "## [<versión>]" de CHANGELOG.md y las sumas
    Corre en Windows PowerShell 5.1 y en PowerShell 7 en cualquier sistema.

.EXAMPLE
    .\packaging\release-notes.ps1 -Dir dist\release
#>
param(
    [string]$Dir = (Join-Path (Split-Path $PSScriptRoot -Parent) "dist\release"),
    [string]$Version = ([xml](Get-Content (Join-Path (Split-Path $PSScriptRoot -Parent) "pom.xml"))).project.version
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path $PSScriptRoot -Parent
$repo = "HideOnVava/devava-kardex"
$base = "https://github.com/$repo/releases/download/v$Version"

# --- Sección del CHANGELOG de esta versión ---------------------------------------------
$changelog = Get-Content (Join-Path $projectDir "CHANGELOG.md") -Raw -Encoding UTF8
$seccion = [regex]::Match($changelog, "(?s)## \[" + [regex]::Escape($Version) + "\][^\r\n]*\r?\n(.*?)(?=\r?\n## \[|\z)")
if (-not $seccion.Success) { throw "CHANGELOG.md no tiene la sección '## [$Version]'." }

# --- Archivos: qué es cada uno, en el orden de la tabla ------------------------------------
$tipos = @(
    @{ Sufijo = '-windows-x64-setup.exe';    So = 'Windows'; Texto = '**Recomendado.** Instalador: doble clic y listo. No pide permisos de administrador; se desinstala desde *Configuración > Aplicaciones*.' },
    @{ Sufijo = '-windows-x64-portable.zip'; So = 'Windows'; Texto = 'Sin instalar: descomprime donde quieras y abre `Devava Kardex.exe`.' },
    @{ Sufijo = '-macos-arm64.dmg';          So = 'macOS';   Texto = 'Mac con **Apple Silicon** (M1 o posterior). Abre la imagen y arrastra *Devava Kardex* a *Aplicaciones*.' },
    @{ Sufijo = '-macos-x64.dmg';            So = 'macOS';   Texto = 'Mac con **Intel**. Abre la imagen y arrastra *Devava Kardex* a *Aplicaciones*.' },
    @{ Sufijo = '-linux-x64.deb';            So = 'Linux';   Texto = 'Ubuntu, Debian, Linux Mint y otros sistemas .deb: doble clic o `sudo apt install ./<archivo>`. Agrega la app al menú.' },
    @{ Sufijo = '-linux-x64.rpm';            So = 'Linux';   Texto = 'Fedora, openSUSE y otros sistemas .rpm: `sudo dnf install ./<archivo>` (o `zypper`).' },
    @{ Sufijo = '-linux-x64.tar.gz';         So = 'Linux';   Texto = 'Cualquier distribución de 64 bits, sin instalar: `tar xf <archivo>` y abre `"Devava Kardex/bin/Devava Kardex"`.' }
)
$encontrados = Get-ChildItem $Dir -Recurse -File -Filter "Devava-Kardex-$Version-*"
if (-not $encontrados) { throw "No hay archivos Devava-Kardex-$Version-* en $Dir." }

$filas = @()
$sumas = @()
foreach ($tipo in $tipos) {
    $archivo = $encontrados | Where-Object { $_.Name -eq "Devava-Kardex-$Version$($tipo.Sufijo)" } | Select-Object -First 1
    if (-not $archivo) { continue }
    $texto = $tipo.Texto.Replace("<archivo>", $archivo.Name)
    $filas += "| $($tipo.So) | [$($archivo.Name)]($base/$($archivo.Name)) | $texto |"
    $sumas += "{0}  {1}" -f (Get-FileHash $archivo.FullName -Algorithm SHA256).Hash.ToLower(), $archivo.Name
}
$conocidos = $tipos | ForEach-Object { "Devava-Kardex-$Version$($_.Sufijo)" }
$extranos = $encontrados | Where-Object { $conocidos -notcontains $_.Name }
if ($extranos) { throw "Archivos inesperados: $($extranos.Name -join ', ')" }

$tabla = @("| Sistema | Archivo | Notas |", "| --- | --- | --- |") + $filas

# --- Salida ----------------------------------------------------------------------------------
$utf8 = New-Object System.Text.UTF8Encoding $false   # sin BOM, en cualquier PowerShell
[System.IO.File]::WriteAllText((Join-Path $Dir "SHA256SUMS.txt"), ($sumas -join "`n") + "`n", $utf8)

$notas = Get-Content (Join-Path $PSScriptRoot "release-notes.md") -Raw -Encoding UTF8
$notas = $notas.Replace("{{VERSION}}", $Version).Replace("{{DESCARGAS}}", ($tabla -join "`n"))
$notas = $notas.Replace("{{CAMBIOS}}", $seccion.Groups[1].Value.Trim()).Replace("{{SUMAS}}", ($sumas -join "`n"))
[System.IO.File]::WriteAllText((Join-Path $Dir "RELEASE_NOTES.md"), $notas, $utf8)

Write-Host "Notas de $Version con $($filas.Count) archivo(s):"
$filas | ForEach-Object { Write-Host "  $_" }
