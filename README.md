# OpenStream TV

Reproductor IPTV de código abierto para Android TV y Amazon Fire TV. Rápido de usar con mando, pensado para directos estables y creado desde cero.

[![Última versión](https://img.shields.io/github/v/release/rafaelgarciavalcarcelamo/OpenStreamTV?label=versi%C3%B3n)](https://github.com/rafaelgarciavalcarcelamo/OpenStreamTV/releases/latest)
[![Android](https://img.shields.io/badge/Android%20TV-6.0%2B-3DDC84)](https://github.com/rafaelgarciavalcarcelamo/OpenStreamTV/releases/latest)
[![Licencia](https://img.shields.io/badge/licencia-MIT-blue)](LICENSE)

> No incluye canales, listas, cuentas ni contenido. Usa únicamente fuentes M3U/XMLTV que tengas derecho a reproducir.

**[Descargar la última versión](https://github.com/rafaelgarciavalcarcelamo/OpenStreamTV/releases/latest)** · [Instalación](#instalación-en-android-tv-y-fire-tv) · [Privacidad](PRIVACY.md) · [Seguridad](SECURITY.md) · [English](docs/en/README.md)

## Instalación en Android TV y Fire TV

1. Abre la página de [Releases](https://github.com/rafaelgarciavalcarcelamo/OpenStreamTV/releases/latest) desde el televisor o descarga el APK en otro dispositivo y pásalo por USB.
2. Descarga el archivo `OpenStreamTV-<versión>-release.apk` de la versión más reciente.
3. Si Android lo solicita, permite la instalación desde la aplicación con la que abriste el APK (navegador o gestor de archivos).
4. Instala, abre OpenStream TV y elige **Añadir lista**.
5. Añade una lista M3U, una cuenta Xtream autorizada y, opcionalmente, una guía XMLTV.

Las actualizaciones se avisan automáticamente una vez al día. También puedes buscarlas manualmente desde **Ajustes → Buscar actualización ahora**.

> Si Android indica que la APK de pruebas instalada tiene otra firma, guarda primero los datos de tus fuentes. Desinstalar la app borra sus listas y ajustes locales.

## Control desde iPhone o Android

En la televisión, abre **Mis listas → Gestionar listas desde otro dispositivo** y escanea el QR. El teléfono recordará la televisión. Desde el panel web local puedes añadir, modificar, activar, actualizar, desactivar, reactivar y eliminar listas.

Las credenciales permanecen cifradas en la televisión: la web solo muestra el servidor y un usuario oculto. El acceso requiere el código de seis cifras mostrado en la TV, limita los intentos fallidos y rechaza conexiones que no procedan de la misma red local.

> Algunas VPN bloquean por completo la red doméstica. Con Proton VPN hay que activar **Ajustes → LAN → Permitir conexiones LAN** (función Plus) o desconectarla únicamente mientras se administran las listas. En otras VPN, activa la opción equivalente de descubrimiento o acceso LAN.

## Capturas

Las capturas se hicieron en Android TV con una lista de demostración que solo muestra canales públicos de [RTVE](https://www.rtve.es/play/videos/directo/). Las imágenes ilustran la interfaz de la app.

![Inicio de OpenStream TV con canales públicos de RTVE](docs/screenshots/inicio.png)

| Canales | Gestionar categorías |
| --- | --- |
| ![Catálogo con imágenes de los canales](docs/screenshots/canales.png) | ![Selector de categorías visibles](docs/screenshots/categorias.png) |

![Ajustes de OpenStream TV con interruptor para la guía EPG](docs/screenshots/settings.png)

## Funciones incluidas

- Portada de estilo cinematográfico para televisor: canal destacado, filas horizontales de canales y categorías, logos y foco claro al usar el mando.
- Orden inteligente de Inicio: canales fijados, favoritos y más vistos según el uso local del usuario.
- Pantalla de personalización para añadir, quitar, subir y bajar canales; el orden elegido también se aplica al catálogo y al cambio de canal.
- Acceso superior a Inicio, Canales, Favoritos, Buscar y Mis listas. Ajustes y Diagnóstico automático están en el menú lateral.
- Panel web local para iPhone y Android con QR, vinculación recordada y controles grandes pensados para usuarios sin experiencia técnica.
- Gestión de varias listas con una sola activa. Puedes desactivar y recuperar una lista desde la TV o el panel móvil.
- Descubrimiento de otros dispositivos OpenStream TV dentro de la misma red doméstica.
- Aviso superior discreto cuando Android no detecta una VPN activa; abre los ajustes de VPN y puede desactivarse desde Ajustes.
- Catálogo de dos paneles con logos, números de canal y programación cuando hay guía. **Canales → Explorar → Gestionar categorías** permite ocultar grupos enteros por lista y recuperarlos después.
- Guía rápida sobre la imagen con OK o MENÚ, cambio de canal con arriba/abajo y selección por número cuando el mando tiene teclado numérico.
- Importación M3U/M3U8 por URL o selector de archivo.
- Acceso Xtream Codes mediante servidor, usuario y contraseña; importa categorías y canales en directo.
- Credenciales y catálogo Xtream cifrados con una clave no exportable de Android Keystore.
- Guía XMLTV por URL, compatible con gzip y con un interruptor de EPG en Ajustes.
- Búsqueda, grupos y favoritos persistentes (pulsación larga sobre un canal).
- Historial local de canales reproducidos, reiniciable desde Ajustes y usado para ordenar la portada sin enviar datos fuera del dispositivo.
- Reproducción en directo con AndroidX Media3/ExoPlayer, HLS y flujos progresivos compatibles.
- Cambio rápido de canal con arriba/abajo o Channel Up/Down.
- Perfiles de búfer Rápido, Equilibrado, Estable y Personalizado.
- Ajustes independientes de búfer mínimo, máximo, arranque y reanudación.
- Perfil predeterminado para instalaciones nuevas: 60–180 s de búfer, 10 s antes de iniciar, 15 s para reanudar y 30 s de retraso del directo.
- Resolución máxima, modo de imagen, idiomas preferidos y fallback de decodificador.
- Preferencia independiente de decodificador de vídeo y audio: automático, hardware o software.
- Perfil estable con 15 segundos de retraso objetivo en HLS, margen de directo adaptativo y recuperación al borde de la emisión.
- Reintentos HTTP con espera progresiva y reconexión continua mientras la pantalla de reproducción esté abierta.
- Detector de reproducción congelada y de búfer prolongado.
- Reconexión automática cuando vuelve la red.
- OkHttp con reutilización de conexiones y sin dependencia de Google Play Services, adecuado para Fire TV.
- Actualización transaccional de listas y EPG: una descarga incorrecta no sustituye la última copia válida.
- Cola asíncrona opcional del códec para dispositivos Android/Fire OS antiguos.
- Perfil de compatibilidad persistente por canal: recuerda límites de calidad y fallback de software.
- Recuperación de fallos de decodificación de vídeo y audio por separado.
- Espera máxima de 60 segundos entre intentos ordinarios, y de cinco minutos cuando el servidor rechaza las credenciales.
- Calidad adaptativa: reduce 2160p → 1080p → 720p → 480p → 360p tras rebuffers repetidos.
- Panel técnico durante la reproducción con el botón INFO o MENÚ del mando.
- El panel muestra códecs, decodificadores, resolución, FPS, red, búfer, retraso, frames perdidos y último error.
- Liberación de la guía EPG cuando Fire TV solicita memoria y restauración asíncrona al volver.
- Inicio automático opcional al recibir `BOOT_COMPLETED` en Fire TV/Android TV compatibles.
- Inicio configurable en la portada, favoritos o último canal reproducido.
- Actualización silenciosa de lista y EPG al abrir, conservando siempre la copia funcional.
- Confirmación de salida y memoria del último canal.
- Comprobación diaria opcional de nuevas versiones publicadas en GitHub, aviso en pantalla y acceso directo a la descarga del APK.
- Botón manual «Buscar actualización ahora» en Ajustes.
- Perfil recomendado predeterminado: búfer Personalizado de 60–180 s, 10 s antes de iniciar, 15 s tras un corte, 30 s de retraso, 1080p, reconexión continua y fallback activo.
- Reconexión automática configurable, timeouts de red, retraso del directo, User-Agent y reproducción automática.
- Funciona sin registro: la configuración permanece en el dispositivo.
- La guía se carga fuera del hilo de la pantalla; las descargas tienen límites de tamaño para evitar agotar el almacenamiento.

La reconexión automática actúa mientras la pantalla de reproducción esté abierta. Si el servidor deja de emitir, la cuenta caduca o la conexión carece de capacidad suficiente, puede haber pausas pese a los reintentos. El retraso de 30 s se aplica a directos HLS que anuncian una ventana temporal; en flujos TS continuos la reserva depende del búfer acumulado.

## Licencia

Código disponible bajo licencia MIT. Consulta `LICENSE`.

Consulta también la [política de privacidad](PRIVACY.md) y las indicaciones de [seguridad](SECURITY.md).
