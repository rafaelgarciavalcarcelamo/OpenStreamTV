# Arquitectura

data analiza M3U/XMLTV y consulta Xtream; CredentialVault protege credenciales con Android Keystore; player aplica Media3 y políticas de recuperación; ui muestra catálogo, favoritos, ajustes y reproducción; las actualizaciones conservan la última copia válida.

## Flujo

1. La interfaz recoge la intención del usuario.
2. La capa de dominio valida y transforma los datos.
3. Los adaptadores externos ejecutan almacenamiento, red, reproducción o empaquetado.
4. Se muestra el resultado y se conservan diagnósticos mínimos.
