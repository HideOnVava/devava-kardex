## Descargar

| Archivo | Notas |
| --- | --- |
{{DESCARGAS}}

Windows 10 u 11 de 64 bits. **No necesitas Java**: la app trae el suyo. Todo lo construye
GitHub Actions a partir de esta etiqueta, así que el registro del build muestra qué lleva.

<details>
<summary><b>Windows: "Windows protegió tu PC"</b></summary>

Los archivos no están firmados (eso requiere un certificado de pago), así que SmartScreen puede
avisar la primera vez. Haz clic en **Más información → Ejecutar de todas formas**.
</details>

Tu avance se guarda en `Documentos\Devava Kardex` y se conserva al actualizar o desinstalar.

## Novedades en {{VERSION}}

{{CAMBIOS}}

## Verificar la descarga (opcional)

Compara el SHA-256 del archivo (`certutil -hashfile <archivo> SHA256`) con:

```
{{SUMAS}}
```
