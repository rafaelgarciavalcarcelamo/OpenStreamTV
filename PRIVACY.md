# Privacidad de OpenStream TV

OpenStream TV no requiere una cuenta y no incorpora analítica, publicidad ni servidores propios de seguimiento.

## Datos en el dispositivo

- Las listas, favoritos, historial y ajustes se guardan localmente.
- La contraseña de Xtream se cifra con una clave no exportable de Android Keystore.
- Las copias de seguridad automáticas de Android están desactivadas para evitar trasladar credenciales o datos cifrados a otro dispositivo.

## Conexiones de red

La aplicación se conecta exclusivamente a las direcciones que introduzca la persona usuaria para sus listas, guía o proveedor Xtream. Si están activadas las comprobaciones de actualización, consulta una vez al día el manifiesto público de GitHub. GitHub puede recibir la dirección IP y el identificador técnico de versión usados para esa conexión.

El control móvil abre un servidor web en el puerto local `8765` mientras el proceso de OpenStream TV está en ejecución. Solo acepta dispositivos de la misma red doméstica y exige el código mostrado en la televisión. El navegador recibe los nombres, tipos y servidores abreviados de las listas, pero no las URLs completas, usuarios completos ni contraseñas almacenadas. No interviene ningún servidor en la nube de OpenStream TV.

Las fuentes IPTV pueden usar HTTP o HTTPS. HTTPS es preferible; HTTP permite que la red intercepte o modifique el tráfico.

## Soporte

No publiques URLs privadas, usuarios, contraseñas ni diagnósticos que los contengan al abrir una incidencia.
