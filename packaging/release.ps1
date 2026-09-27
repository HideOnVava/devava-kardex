<#
.SYNOPSIS
    Arma en dist\release los archivos de una versión para Windows:
      Devava-Kardex-<versión>-windows-x64-setup.exe      instalador (necesita el WiX Toolset 3.x)
      Devava-Kardex-<versión>-windows-x64-portable.zip   la app sin instalar
      SHA256SUMS.txt                                      sumas SHA-256 (formato sha256sum)
      RELEASE_NOTES.md                                    texto del release de GitHub

.DESCRIPTION
    La versión sale de pom.xml y debe tener su sección "## [<versión>]" en CHANGELOG.md.
    El workflow .github/workflows/release.yml lo corre al empujar la etiqueta v<versión>.

.EXAMPLE
    .\packaging\release.ps1
    .\packaging\release.ps1 -SkipInstaller      # solo el zip (sin WiX)
#>
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$SkipInstaller,
    [string]$Output = (Join-Path (Split-Path $PSScriptRoot -Parent) "dist\release")
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path $PSScriptRoot -Parent
$appName = "Devava Kardex"
$repo = "HideOnVava/devava-kardex"
$version = ([xml](Get-Content (Join-Path $projectDir "pom.xml"))).project.version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "La versión '$version' de pom.xml no es de release (x.y.z)." }
$changelog = Get-Content (Join-Path $projectDir "CHANGELOG.md") -Raw -Encoding UTF8
$seccion = [regex]::Match($changelog, "(?s)## \[" + [regex]::Escape($version) + "\][^\r\n]*\r?\n(.*?)(?=\r?\n## \[|\z)")
if (-not $seccion.Success) { throw "CHANGELOG.md no tiene la sección '## [$version]'. Agrégala antes de publicar." }

# --- Construir -------------------------------------------------------------------------
$build = Join-Path $PSScriptRoot "build-windows.ps1"
$stage = Join-Path $projectDir "target\package\dist"
$zipName = "Devava-Kardex-$version-windows-x64-portable.zip"
$setupName = "Devava-Kardex-$version-windows-x64-setup.exe"

if (Test-Path $Output) { Remove-Item -Recurse -Force $Output }
New-Item -ItemType Directory -Force -Path $Output | Out-Null

& $build -JdkHome $JdkHome -Type app-image -Destination $stage

# Zip de la imagen: una carpeta raíz y "/" en los nombres (CreateFromDirectory usaría "\"
# en Windows PowerShell 5.1).
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zipPath = Join-Path $Output $zipName
$root = (Get-Item (Join-Path $stage $appName)).FullName
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem $root -Recurse -File) {
        $entry = "$appName/" + $file.FullName.Substring($root.Length + 1).Replace("\", "/")
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entry,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Host "Zip: $zipPath"

if (-not $SkipInstaller) {
    & $build -JdkHome $JdkHome -Type exe -ReuseImage -Destination $stage
    Move-Item (Join-Path $stage "$appName-$version.exe") (Join-Path $Output $setupName) -Force
    Write-Host "Instalador: $(Join-Path $Output $setupName)"
}

# --- Sumas y notas del release -------------------------------------------------------------
$utf8 = New-Object System.Text.UTF8Encoding $false   # sin BOM, en cualquier PowerShell
$base = "https://github.com/$repo/releases/download/v$version"
$archivos = @(
    @{ Nombre = $setupName; Texto = "**Recomendado.** Instalador: doble clic y listo. No pide permisos de administrador; se desinstala desde *Configuración > Aplicaciones*." },
    @{ Nombre = $zipName;   Texto = "Sin instalar: descomprime donde quieras y abre ``Devava Kardex.exe``." }
) | Where-Object { Test-Path (Join-Path $Output $_.Nombre) }
$filas = $archivos | ForEach-Object { "| [$($_.Nombre)]($base/$($_.Nombre)) | $($_.Texto) |" }
$sumas = $archivos | ForEach-Object {
    "{0}  {1}" -f (Get-FileHash (Join-Path $Output $_.Nombre) -Algorithm SHA256).Hash.ToLower(), $_.Nombre
}
[System.IO.File]::WriteAllText((Join-Path $Output "SHA256SUMS.txt"), ($sumas -join "`n") + "`n", $utf8)

$notas = Get-Content (Join-Path $PSScriptRoot "release-notes.md") -Raw -Encoding UTF8
$notas = $notas.Replace("{{VERSION}}", $version).Replace("{{DESCARGAS}}", ($filas -join "`n"))
$notas = $notas.Replace("{{CAMBIOS}}", $seccion.Groups[1].Value.Trim()).Replace("{{SUMAS}}", ($sumas -join "`n"))
[System.IO.File]::WriteAllText((Join-Path $Output "RELEASE_NOTES.md"), $notas, $utf8)

Write-Host ""
Write-Host "Archivos de $appName $version en $Output`:"
Get-ChildItem $Output | ForEach-Object { Write-Host ("  {0,-50} {1,10:N0} KB" -f $_.Name, ($_.Length / 1KB)) }
