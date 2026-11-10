# Política de seguridad

## Comunicar una vulnerabilidad

No abras una incidencia pública ni adjuntes listas, URLs, usuarios, contraseñas o diagnósticos con datos privados.

Usa el botón **Report a vulnerability** de la pestaña **Security** de GitHub para enviar el informe de forma privada. Indica la versión afectada, los pasos mínimos para reproducirlo y el impacto esperado. Recibirás confirmación de recepción y se coordinará una corrección antes de publicar detalles.

## Versiones de distribución

Las versiones distribuidas se publican en [Releases](https://github.com/rafaelgarciavalcarcelamo/OpenStreamTV/releases) y deben estar firmadas con la clave de producción de OpenStream TV. Las APK debug o sin firmar no son versiones de distribución.

El SHA-256 y el certificado de firma de cada versión se incluyen en sus notas de publicación.

## Control móvil local

El panel móvil requiere un código de seis cifras generado en la televisión, compara el código sin diferencias de tiempo, limita los intentos fallidos y no devuelve credenciales guardadas. No expongas el puerto `8765` en el router ni lo redirijas hacia Internet. Usa el control únicamente en una red doméstica de confianza.
