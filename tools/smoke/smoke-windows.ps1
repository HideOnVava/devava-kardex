<#
.SYNOPSIS
    Prueba de humo del build de Windows: instala el setup.exe en silencio (o usa una imagen de la
    app), la abre, entra a Principal con el teclado, guarda capturas y el registro, la cierra y
    revisa que haya terminado bien y sin excepciones.

.EXAMPLE
    .\tools\smoke\smoke-windows.ps1 -Setup dist\release\Devava-Kardex-1.1.0-windows-x64-setup.exe -Out target\smoke\out
    .\tools\smoke\smoke-windows.ps1 -Exe "dist\Devava Kardex\Devava Kardex.exe" -Out target\smoke\out
#>
param(
    [string]$Setup = "",
    [string]$Exe = "",
    [Parameter(Mandatory = $true)][string]$Out
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Out = (Resolve-Path $Out).Path

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Humo {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hwnd, int attr, out RECT rect, int size);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

if ($Setup) {
    $p = Start-Process -FilePath (Resolve-Path $Setup).Path -ArgumentList "/qn" -PassThru -Wait
    if ($p.ExitCode -ne 0) { throw "El instalador terminó con código $($p.ExitCode)" }
    $Exe = Join-Path $env:LOCALAPPDATA "Devava Kardex\Devava Kardex.exe"
    Write-Host "Instalada: $Exe"
}
if (-not (Test-Path $Exe)) { throw "No encontré el lanzador: $Exe" }

$app = Start-Process -FilePath $Exe -PassThru -RedirectStandardOutput "$Out\app.log" -RedirectStandardError "$Out\app.err.log"
$app.Handle | Out-Null   # guarda el handle para poder leer ExitCode cuando termine

# El lanzador de jpackage se vuelve a ejecutar como proceso hijo, así que la ventana es de otro
# proceso: se busca por nombre y título.
function Ventana {
    for ($i = 0; $i -lt 60; $i++) {
        if (-not (Get-Process -Id $app.Id -ErrorAction SilentlyContinue)) { throw "La app se cerró antes de mostrar su ventana" }
        $proc = Get-Process -Name "Devava Kardex" -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq "Devava Kardex" } | Select-Object -First 1
        if ($proc) { return $proc.MainWindowHandle }
        Start-Sleep -Seconds 1
    }
    throw "No apareció la ventana"
}
function Captura($nombre) {
    $h = Ventana; [Humo]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 400
    $r = New-Object Humo+RECT
    [Humo]::DwmGetWindowAttribute($h, 9, [ref]$r, [System.Runtime.InteropServices.Marshal]::SizeOf($r)) | Out-Null
    $bmp = New-Object System.Drawing.Bitmap ($r.Right - $r.Left), ($r.Bottom - $r.Top)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
    $g.Dispose(); $bmp.Save("$Out\$nombre.png"); $bmp.Dispose()
    Write-Host "captura: $nombre"
}
function Tecla($teclas, $espera = 1) {
    $h = Ventana; [Humo]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait($teclas); Start-Sleep -Seconds $espera
}

Ventana | Out-Null; Start-Sleep -Seconds 4
Captura "01-menu"
Tecla "{ENTER}" 4                   # el menú abre con "Principal" enfocado
Captura "02-principal"

$proc = Get-Process -Name "Devava Kardex" | Where-Object { $_.MainWindowTitle -eq "Devava Kardex" } | Select-Object -First 1
$proc.CloseMainWindow() | Out-Null
if (-not $app.WaitForExit(30000)) { Stop-Process -Name "Devava Kardex" -Force; throw "La app no terminó al cerrar la ventana" }
Write-Host "la app terminó con código $($app.ExitCode)"
if ($app.ExitCode -ne 0) { Get-Content "$Out\app.err.log"; throw "Código de salida distinto de 0" }
$registro = (Get-Content "$Out\app.log" -Raw -ErrorAction SilentlyContinue) + (Get-Content "$Out\app.err.log" -Raw -ErrorAction SilentlyContinue)
if ($registro -match "(?i)exception") { Write-Host $registro; throw "El registro tiene una excepción" }
Write-Host "Prueba de humo superada"
