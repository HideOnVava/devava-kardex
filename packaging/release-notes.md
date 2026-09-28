## Descargar

{{DESCARGAS}}

Sistemas de 64 bits: Windows 10 u 11, macOS 12 o posterior, o un Linux de escritorio con GTK 3
(cualquier Ubuntu, Debian, Mint, Fedora, openSUSE o Arch actual). **No necesitas Java**: la app
trae el suyo. Todo lo construye GitHub Actions a partir de esta etiqueta, así que el registro del
build muestra qué lleva.

<details>
<summary><b>Windows: "Windows protegió tu PC"</b></summary>

Los archivos no están firmados (eso requiere un certificado de pago), así que SmartScreen puede
avisar la primera vez. Haz clic en **Más información → Ejecutar de todas formas**.
</details>

<details>
<summary><b>macOS: "No se puede abrir Devava Kardex" / "Apple no pudo verificar…"</b></summary>

La app no está notarizada por Apple (eso requiere una cuenta de desarrollador de pago), así que
macOS la bloquea una vez. Después del primer intento de abrirla, ve a **Configuración del
Sistema → Privacidad y seguridad**, baja hasta el aviso sobre *Devava Kardex*, haz clic en
**Abrir igualmente** y confirma. En macOS 13 y 14 también puedes hacer clic derecho sobre la app
y elegir **Abrir**. Solo hace falta la primera vez.
</details>

<details>
<summary><b>Linux: dónde queda</b></summary>

Los paquetes `.deb` y `.rpm` se instalan en `/opt/devava-kardex` y agregan *Devava Kardex* al
menú de aplicaciones; se quitan con `sudo apt remove devava-kardex` (o `dnf`). El `.tar.gz` no
se instala: descomprímelo y abre `Devava Kardex/bin/Devava Kardex`.
</details>

Tu avance se guarda en tu carpeta de Documentos (`Devava Kardex`) y se conserva al actualizar o
desinstalar.

## Novedades en {{VERSION}}

{{CAMBIOS}}

## Verificar la descarga (opcional)

Compara el SHA-256 del archivo (`certutil -hashfile <archivo> SHA256` en Windows,
`shasum -a 256 <archivo>` en macOS, `sha256sum <archivo>` en Linux) con:

```
{{SUMAS}}
```
