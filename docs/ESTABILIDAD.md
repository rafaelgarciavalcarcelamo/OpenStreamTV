# Plan de estabilidad

OpenStream TV intenta recuperar un canal mientras la pantalla de reproducción sigue abierta. Ningún reproductor puede evitar una pausa cuando el servidor deja de entregar datos o la red no tiene ancho de banda suficiente.

## Ya implementado

- Retraso objetivo de 15 s para directos HLS, con margen adicional después de un rebuffer. El retraso no se aplica de la misma forma a flujos MPEG-TS continuos.
- Reintentos de carga y reconexión continua con espera limitada, recuperación al volver la red y reinicio de una conexión congelada.
- Perfil de búfer estable y límite predeterminado de 1080p, con ajuste de calidad por canal y alternativa de decodificación de audio/vídeo.
- Copia funcional de lista y guía conservada si falla una actualización.
- Carga de la guía en segundo plano y límites de 64 MiB para listas y 128 MiB para guías descomprimidas.
- Diagnóstico local mediante INFO: estado de red, búfer, retraso real HLS, códec, resolución y último error de carga.

## Riesgos que todavía requieren trabajo

| Prioridad | Riesgo | Próxima solución |
| --- | --- | --- |
| Alta | Un canal con una sola URL no tiene alternativa si su servidor cae. | Permitir varias fuentes por canal y cambiar a un espejo válido sin cambiar de programa. |
| Alta | Algunas listas usan enlaces temporales que caducan durante la reproducción. | Renovar la lista en segundo plano y actualizar el enlace del canal antes del siguiente intento, con límite de frecuencia. |
| Alta | Las listas M3U de ciertos proveedores necesitan cabeceras HTTP por canal. | Admitir User-Agent y Referer por entrada y heredarlos en los segmentos HLS. |
| Media | Una guía enorme puede ocupar demasiada memoria pese al límite de descarga. | Indexar la guía por canal y franja horaria en almacenamiento local; cargar solo la ventana visible. |
| Media | La calidad aprendida por canal no distingue aún una fuente adaptable de un TS de calidad fija. | Reducir resolución solo cuando existan variantes seleccionables; conservar métricas de bitrate localmente. |
| Media | Los fallos intermitentes no se pueden reproducir sin un flujo controlado. | Servidor de pruebas local con cortes, HTTP 429/503, segmentos HLS ausentes y cambios de red simulados. |

## Funciones útiles después de estabilizar

1. Guía de programación completa con búsqueda por programa y recordatorios locales.
2. Elección rápida de pista de audio y subtítulos desde el mando.
3. Orden personalizable de canales y grupos, con favoritos en la primera fila.
4. Importación/exportación cifrada de configuración para cambiar de televisor.
5. Control parental opcional con PIN local.

## Cómo medir la mejora

- Minutos reproducidos sin rebuffer por canal y por tipo de flujo.
- Tiempo desde un corte de red hasta volver a mostrar vídeo.
- Número de reconexiones por hora y errores HTTP, sin guardar URLs ni credenciales en el diagnóstico.
- Uso máximo de memoria al cargar listas y guías grandes.

La documentación de Media3 explica el [ajuste del retraso en directo](https://developer.android.com/media/media3/exoplayer/live-streaming), la [política de errores de carga](https://developer.android.com/media/media3/exoplayer/customization) y las [causas de tirones por capacidad de decodificación](https://developer.android.com/media/media3/exoplayer/troubleshooting).
