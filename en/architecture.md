# Architecture

data analiza M3U/XMLTV y consulta Xtream; CredentialVault protege credenciales con Android Keystore; player aplica Media3 y políticas de recuperación; ui muestra catálogo, favoritos, ajustes y reproducción; las actualizaciones conservan la última copia válida.

## Flow

1. The interface captures user intent.
2. Domain code validates and transforms data.
3. External adapters handle storage, networking, playback, or packaging.
4. The result is presented and only minimal diagnostics are retained.
