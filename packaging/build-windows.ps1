<#
.SYNOPSIS
    Construye Devava Kardex como aplicación de Windows que no necesita Java instalado, con
    jlink y jpackage del JDK: una imagen de la app (carpeta con Devava Kardex.exe y su propio
    runtime) o, a partir de ella, un instalador (.exe / .msi).

.DESCRIPTION
    1. Compila con el wrapper de Maven y copia las dependencias.
    2. Arma con jlink un runtime recortado: los módulos del JDK que usa la app y JavaFX.
    3. jpackage crea la imagen en target\package\image\Devava Kardex (la app corre desde el
       class path; JavaFX va como módulos dentro del runtime).
    4. -Type app-image (por defecto): copia la imagen a <Destination>\Devava Kardex.
       -Type exe / msi: instalador por usuario desde la imagen, en <Destination>. Necesita el
       WiX Toolset 3.x (https://github.com/wixtoolset/wix3/releases), en el PATH o en WIX.

    Requiere un JDK 21 completo (con la carpeta jmods). La versión se lee de pom.xml.

.EXAMPLE
    .\packaging\build-windows.ps1
    .\packaging\build-windows.ps1 -Type exe
    .\packaging\build-windows.ps1 -Type exe -ReuseImage     # instalador de la última imagen
#>
param(
    [string]$Destination = (Join-Path (Split-Path $PSScriptRoot -Parent) "dist"),
    [ValidateSet("app-image", "exe", "msi")]
    [string]$Type = "app-image",
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$ReuseImage
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path $PSScriptRoot -Parent
$appName = "Devava Kardex"
$vendor = "devava XP Studios"
$description = "Kardex, retícula y horario del ITL"
$appVersion = ([xml](Get-Content (Join-Path $projectDir "pom.xml"))).project.version
# Identifica el producto entre versiones: un instalador nuevo reemplaza la instalación
# anterior en lugar de quedar a un lado. No cambiarlo nunca.
$upgradeUuid = "34d69c5d-32a7-4b8e-90f7-f588d5bd7f31"

$jdkModules = @(Get-Content (Join-Path $PSScriptRoot "jdk-modules.txt") |
    ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith("#") })
$javafxModules = @("javafx.base", "javafx.graphics", "javafx.controls", "javafx.swing")

$work = Join-Path $projectDir "target\package"
$image = Join-Path $work "image\$appName"

# --- JDK -----------------------------------------------------------------------------
if (-not $JdkHome) {
    # "java" en el PATH suele ser un acceso directo de Oracle (javapath) sin jlink: buscar el JDK.
    $JdkHome = Get-ChildItem "C:\Program Files\Java\jdk-21*", "C:\Program Files\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "jmods") } | Select-Object -Last 1 -ExpandProperty FullName
}
$jlink = Join-Path "$JdkHome" "bin\jlink.exe"
$jpackage = Join-Path "$JdkHome" "bin\jpackage.exe"
$jmods = Join-Path "$JdkHome" "jmods"
if (-not $JdkHome -or -not (Test-Path $jpackage) -or -not (Test-Path $jlink) -or -not (Test-Path $jmods)) {
    throw "No encontré jlink/jpackage/jmods. Define JAVA_HOME con un JDK 21 completo (o usa -JdkHome)."
}
$env:JAVA_HOME = $JdkHome

# --- WiX (solo para instaladores) ------------------------------------------------------
if ($Type -ne "app-image") {
    if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
        $wixBin = if ($env:WIX) { Join-Path $env:WIX "bin" } else { "C:\Program Files (x86)\WiX Toolset v3.14\bin" }
        if (Test-Path (Join-Path $wixBin "candle.exe")) {
            $env:PATH = "$wixBin;$env:PATH"
        } else {
            throw "Para el instalador -Type $Type hace falta el WiX Toolset 3.x (no encontré candle.exe). Instálalo desde https://github.com/wixtoolset/wix3/releases o construye solo la imagen."
        }
    }
}

Push-Location $projectDir
try {
    if ($ReuseImage -and (Test-Path (Join-Path $image "$appName.exe"))) {
        Write-Host "Uso la imagen de la app en $image"
    } else {
        # --- Compilar ------------------------------------------------------------------
        & .\mvnw.cmd -q -B clean package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Falló la compilación con Maven." }
        & .\mvnw.cmd -q -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target\dependency
        if ($LASTEXITCODE -ne 0) { throw "No pude copiar las dependencias." }

        $appJar = Get-ChildItem "target\devava-kardex-*.jar" | Select-Object -First 1
        if (-not $appJar) { throw "No encontré el jar de la app en target\." }

        # Los jars de JavaFX van al runtime; el resto de las librerías, al class path.
        $javafxJars = Get-ChildItem "target\dependency\javafx-*-win.jar"
        $libraryJars = Get-ChildItem "target\dependency\*.jar" | Where-Object { $_.Name -notlike "javafx-*" }
        if ($javafxJars.Count -ne $javafxModules.Count) {
            throw "Esperaba $($javafxModules.Count) jars de JavaFX en target\dependency y hay $($javafxJars.Count)."
        }

        if (Test-Path $work) { Remove-Item -Recurse -Force $work }
        $inputDir = Join-Path $work "input"
        $runtime = Join-Path $work "runtime"
        New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
        Copy-Item $appJar.FullName $inputDir
        foreach ($jar in $libraryJars) { Copy-Item $jar.FullName $inputDir }

        # --- Runtime: módulos del JDK + JavaFX ---------------------------------------------
        $jlinkModulePath = @($jmods) + ($javafxJars | ForEach-Object { $_.FullName })
        & $jlink `
            --module-path ($jlinkModulePath -join ";") `
            --add-modules (($jdkModules + $javafxModules) -join ",") `
            --output $runtime `
            --strip-debug --no-header-files --no-man-pages --compress zip-6
        if ($LASTEXITCODE -ne 0) { throw "Falló jlink." }

        # --- Imagen de la app ------------------------------------------------------------
        & $jpackage `
            --type app-image `
            --name $appName `
            --app-version $appVersion `
            --vendor $vendor `
            --copyright "Copyright (c) 2026 $vendor" `
            --description $description `
            --icon (Join-Path $PSScriptRoot "icon.ico") `
            --runtime-image $runtime `
            --input $inputDir `
            --main-jar $appJar.Name `
            --main-class com.devavaxp.kardex.KardexApp `
            --dest (Split-Path $image -Parent) `
            --java-options "-Dfile.encoding=UTF-8"
        if ($LASTEXITCODE -ne 0) { throw "Falló jpackage." }
    }

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null

    if ($Type -eq "app-image") {
        $appFolder = Join-Path $Destination $appName
        if (Test-Path $appFolder) { Remove-Item -Recurse -Force $appFolder }
        Copy-Item -Recurse $image $appFolder
        Write-Host "Listo: $appFolder"
    } else {
        # --- Instalador por usuario: sin permisos de administrador, en %LOCALAPPDATA% ---
        & $jpackage `
            --type $Type `
            --app-image $image `
            --name $appName `
            --app-version $appVersion `
            --vendor $vendor `
            --copyright "Copyright (c) 2026 $vendor" `
            --description $description `
            --about-url "https://github.com/HideOnVava/devava-kardex" `
            --win-per-user-install `
            --win-dir-chooser `
            --win-menu --win-menu-group $vendor `
            --win-shortcut --win-shortcut-prompt `
            --win-upgrade-uuid $upgradeUuid `
            --dest $Destination
        if ($LASTEXITCODE -ne 0) { throw "Falló jpackage." }
        $installer = Get-ChildItem (Join-Path $Destination "$appName-$appVersion.$Type")
        Write-Host "Listo: $($installer.FullName)"
    }
} finally {
    Pop-Location
}
