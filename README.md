# MetaLima AI V3 Pro · 0.5.1

MetaLima AI V3 Pro reúne conversación por IA, traducción, notas, grabación y emisión en directo en Android. Puede utilizar la cámara de unas Ray-Ban Meta compatibles con Meta Wearables Device Access Toolkit (DAT) o trabajar con imágenes de la galería del móvil. Es una aplicación independiente.

## Qué puedes hacer

| Función | Qué incluye |
| --- | --- |
| **Gafas y diagnóstico** | Conexión, batería, estado de carga, posición y firmware según los datos que expongan las gafas. El panel muestra la foto de catálogo elegida para cada montura. El diagnóstico distingue la conexión de Meta de la ruta Bluetooth de llamadas y permite probar el audio del móvil y de las gafas por separado. |
| **Conversación y visión con IA** | Conversación por texto, dictado y análisis de imágenes con la API de OpenAI, Google Gemini, Grok, Alibaba Qwen o Muse AI, según el modelo configurado. Lectura en voz alta y turnos de audio con las gafas mediante modelos Gemini compatibles. |
| **Conversación en directo** | Sesiones Gemini Live y OpenAI con el micrófono y la salida de sonido del móvil o con la ruta de voz de las gafas. Los modelos Gemini Live compatibles permiten pedir una imagen de las gafas y guardar notas durante la conversación. |
| **Idiomas** | Interfaz en español, inglés, alemán, italiano y chino simplificado. El idioma del asistente se elige por separado y se aplica a las respuestas de IA, el dictado, las frases de voz y la lectura con las voces de Android. La traducción de voz y de imágenes tiene sus propios selectores de idioma. |
| **Notas e historial** | Notas de texto o dictadas, archivos de imagen adjuntos, historial local con búsqueda y exportación de conversaciones a PDF. |
| **Foto, vídeo y modo creador** | Fotos y vídeo de las gafas, grabación interactiva del visor con HUD, filtros y reacciones, audio opcional, marcas de momentos, guía de encuadre vertical y exportación de fragmentos a la galería. |
| **Emisión en directo** | Destinos con iconos de YouTube, Twitch, TikTok, Kick, Facebook Live e Instagram Live. También admite OBS con servidor de ingestión y un destino RTMP personalizado. Se configura una URL y una clave por destino. |
| **Apariencia, web y rutas** | Cuatro apariencias, búsquedas en Google y rutas o búsquedas cercanas en Maps desde la ubicación aproximada del teléfono, bajo permiso. |
| **Donaciones** | Pestaña al final de la barra inferior con un botón para aportar el importe que elijas en PayPal. |

## Instalar la versión de prueba

1. Descarga `MetaLima-Ai-0.5.1.apk` desde [la versión 0.5.1 en GitHub](https://github.com/yandelima2020/MetaLima-Ai/releases/tag/v0.5.1) y ábrelo en un teléfono con **Android 12 o posterior**. Si Android lo solicita, permite la instalación desde la aplicación con la que abriste el archivo.
2. Si ya tienes la compilación de prueba 0.4.0 con la misma firma, instala el APK encima para conservar historial, notas, claves y ajustes.
3. Para usar la cámara de las gafas, empareja las Ray-Ban Meta en **Meta AI**, activa el modo desarrollador de la integración y pulsa **Vincular** en MetaLima Ai. Acepta los permisos de Android y Meta que correspondan. Si el diagnóstico solicita actualizar el firmware o revisar **Conexiones de aplicaciones**, completa el paso en Meta AI y vuelve al visor.
4. En **Ajustes → Inteligencia artificial**, elige un proveedor, configura un modelo compatible y guarda tu clave API. Puedes usar **Probar conexión** para verificar la configuración. Alibaba Qwen también usa una región y un espacio de trabajo. Las funciones de las gafas, la lectura local y el almacenamiento de notas tienen requisitos independientes de la clave de IA.
5. Cuando GitHub publique un APK más reciente, MetaLima te avisa al abrir. Pulsa **Descargar APK** en el aviso o **Actualizar** en Laboratorio. La descarga se realiza dentro de MetaLima. Android te pedirá confirmar la instalación.

## Color de las monturas

En **Inicio → Mis gafas → Elegir foto de la montura**, elige el modelo, la generación y el acabado de montura y lentes. Hay 51 fotos para ocho combinaciones de modelo y generación del catálogo español de Meta. MetaLima guarda la elección en el móvil para el identificador de esas gafas y cada par empieza con una ilustración neutra. El SDK DAT 1.0.1 facilita el nombre, la familia y datos como la batería. El acabado de la montura queda fuera de los datos de vinculación. [Catálogo de Meta](https://www.meta.com/es/ai-glasses/shop-all/) · [Meta Wearables para Android](https://github.com/meta/meta-wearables-dat-android).

## Muse AI

- **Inicio → Muse AI → Abrir Muse en el navegador** abre [muse.ai](https://muse.ai/) para trabajar con tus vídeos.
- Para conversar dentro de MetaLima, guarda una clave de **Meta Model API** para el modelo Muse Spark o un modelo compatible. También puedes configurar la URL HTTPS completa de un servicio compatible con Chat Completions. MetaLima guarda la clave cifrada y asociada a esa URL. [Documentación de Meta Model API](https://dev.meta.ai/docs/quickstart).
- El acceso al agente de vídeo, su biblioteca y sus cuotas se gestiona en el sitio de Muse. La clave de Meta Model API corresponde al servicio de modelos de Meta.

## Voz, idiomas y micrófono

- En **Ajustes → Idioma y voz → Idioma de la aplicación**, elige un idioma para los menús, botones, avisos y pantallas. **Idioma del teléfono** sigue la configuración de Android y usa el español si el idioma del teléfono no está disponible. En Android 13 o posterior también puedes cambiarlo en los ajustes de idioma de MetaLima Ai en el sistema. Los diálogos que abre otra aplicación, como Meta AI, Maps o PayPal, dependen de esa aplicación.
- En **Ajustes → Idioma y voz**, selecciona el idioma del asistente y la frase de activación. Para usar la frase, pulsa **Activar frase de voz** en **Inicio** y di la frase seguida de tu pregunta. La escucha termina con el resultado, un error, al salir o al cumplirse 10 segundos. Android tiene que ofrecer reconocimiento local para el idioma elegido.
- En **Inicio**, desliza hacia la derecha o toca el icono de chat de la cabecera. Se abre la conversación con el nombre y el icono del asistente seleccionado. Desde ahí puedes cambiar de proveedor o dictar la pregunta.
- En **Inicio → OK Lima por Bluetooth**, enciende el interruptor con las gafas conectadas. La primera vez se descarga un modelo local de español de unos 39 MB. Cuando aparezca **Las gafas escuchan «OK Lima»**, di la frase, espera el tono y haz tu pregunta. También puedes decir «OK Lima, cámbiame la grabación a vertical» seguido. El modo puede seguir oyendo con la pantalla bloqueada mientras Android mantenga activa la notificación y las gafas estén disponibles para llamadas.
- La escucha por Bluetooth detecta «OK Lima» en el móvil y por defecto envía la pregunta como texto. Con Gemini seleccionado se puede activar **Audio directo con Gemini**. Espera el tono antes de preguntar para que Gemini reciba el audio de esa pregunta y haga su propia transcripción. El interruptor y la acción **Desactivar OK Lima** de la notificación liberan el micrófono. El modo arranca apagado después de reinstalar o reiniciar el móvil. Las gafas conservan su activación de fábrica. [Respuesta del equipo de Meta sobre la frase de activación](https://github.com/meta/meta-wearables-dat-android/discussions/85).
- **Inicio → Conversación en directo** permite elegir modelo y usar el audio del teléfono. Esta opción sirve con el Bluetooth apagado. Si quieres usar el micrófono de las gafas, Android debe ofrecerlas como dispositivo de llamadas. En una sesión Live, pulsa **Iniciar conversación**, espera a **Escuchando** y pulsa **Terminar conversación** al acabar.
- Para hablar por turnos con las gafas en **Conversación**, selecciona Google Gemini y un modelo compatible con audio de entrada y respuestas estructuradas. Pulsa **Hablar con las gafas** y luego **Enviar audio**. El turno dispone de hasta 12 segundos. La opción **Repetir respuesta en las gafas** usa la síntesis de voz de Android.
- Para guardar notas durante una conversación Gemini Live compatible, pide explícitamente «Guarda una nota». Para adjuntar una foto o preguntar por lo que ves, pulsa **Activar visión**, completa el permiso de cámara si aparece y pide una imagen o usa **Enviar imagen**.
- **Traducción por voz** interpreta entre dos idiomas y **Traducir una imagen** lee y traduce una foto de la galería. Puedes seleccionar chino mandarín en ambos modos.

### Qué cambió en la versión 0.3.0

En la 0.2.0, si la activación por voz estaba guardada, la pantalla inicial ejecutaba el reconocimiento de Android y lo volvía a iniciar tras resultados y algunos errores. Esto podía solicitar el micrófono incluso con el Bluetooth apagado. La 0.3.0 conserva la frase configurada y solo empieza a escuchar cuando pulsas el botón. El punto verde de Android sigue indicando que la cámara o el micrófono se están usando.

En Live, la reproducción espera a que llegue audio cuando está en silencio, lo que elimina las comprobaciones periódicas de la cola vacía. Durante una conversación reanudable se permiten hasta dos reconexiones de red con pausa progresiva. Si se pierde la ruta de voz, vuelve a iniciar la sesión después de recuperar el dispositivo de audio.

## Cámara, clips y emisión

- **Rendimiento** guarda el vídeo de las gafas con micrófono opcional. **Interactivo** graba el visor mediante el permiso de pantalla de Android, con HUD, filtros o reacciones cuando se habilitan. Las fotos y los vídeos guardados aparecen en la galería.
- En **Modo creador**, pulsa **Marcar momento** mientras grabas. Para marcar por voz, graba con el micrófono del vídeo apagado, pulsa **Marcar con la voz** y di la frase que muestra la pantalla. La guía 9:16 ayuda a encuadrar. Al exportar las marcas puedes mantener el formato original o aplicar el formato de grabación elegido.
- En **Grabar vídeo**, elige **Original**, **Short vertical (9:16)** u **Horizontal (16:9)**. Para los formatos vertical y horizontal puedes mostrar la imagen completa con un fondo desenfocado del mismo vídeo, o llenar todo el encuadre con un recorte centrado. En grabación directa de las gafas se aplica al guardar el MP4 en la galería. También se puede elegir en la ventana que aparece al terminar la grabación.
- En **Emitir en directo**, elige 9:16 o 16:9 y el encuadre antes de iniciar. Se usa ese formato para la señal RTMP que reciben las plataformas. Seleccionar **Como el visor** conserva el comportamiento de la captura anterior. En Android 14 o posterior, selecciona compartir solo MetaLima cuando el diálogo del sistema lo ofrezca.
- **Cambiar formato por voz** aplica la orientación a la próxima grabación o emisión. También funciona tras la frase «OK Lima» usando un comando como «cámbiame la grabación a 16:9» o «cámbiame el directo a vertical».
- Para ver la imagen con menos retardo, usa **Ajustes → Ajustes avanzados de vídeo → Calidad de las gafas → Fluida · Automática**. El visor arranca en calidad media a 24 FPS y puede reiniciarse una vez en baja a 15 FPS si acumula retardo y la cámara está libre para el cambio. Reabre la cámara para aplicar una nueva preferencia de calidad.
- Para emitir, guarda la **URL de emisión** y la **clave de emisión** en **Ajustes → Emisión en directo**. Después abre el visor de las gafas, pulsa **Iniciar directo** y concede el permiso de Android. Si Android lo ofrece, elige compartir solo MetaLima Ai. El acceso a RTMP/RTMPS y la publicación del directo dependen de las condiciones y del panel de tu plataforma. La URL y la clave de cada directo vienen de allí. OBS necesita un servidor de ingestión alcanzable desde el móvil.

## Donaciones

El botón **Donar con PayPal** abre la página de PayPal para la cuenta configurada en la aplicación, con importe libre en euros. La confirmación se realiza en PayPal. La dirección receptora debe estar confirmada en una cuenta habilitada para recibir donaciones. Consulta la [documentación de PayPal para donaciones](https://developer.paypal.com/sdk/donate/).

## Compilar el proyecto

1. Descomprime el ZIP y abre la carpeta `MetaLima Ai` en Android Studio. Usa **JDK 17 o 21**, Android SDK Platform **36** y Android SDK Build-Tools **36.0.0**.
2. Sincroniza Gradle. La primera compilación descarga dependencias de Google Maven, Maven Central, Gradle Plugin Portal y JitPack.
3. En la raíz del proyecto ejecuta `gradlew.bat :app:assembleDebug` en Windows o `./gradlew :app:assembleDebug` en macOS y Linux. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
4. El identificador de la app es `com.lima.metalima`. Tu APK de Android Studio usará tu firma de depuración. Para utilizar la integración de Meta fuera del modo desarrollador, registra tu aplicación en Meta Wearables Developer Center y configura `mwdat_application_id` y `mwdat_client_token` en el `local.properties` de tu equipo, siguiendo la documentación de Meta.

## Privacidad y límites

- Las claves de IA y de emisión se guardan cifradas con Android Keystore. Las notas y el historial se guardan en el almacenamiento de la aplicación. Las consultas se envían al proveedor elegido con el texto, audio o imagen que hayas aportado. La conversación Live transmite el audio mientras escucha y guarda transcripciones en el historial. El dictado manual depende del servicio de Android.
- La latencia de audio y vídeo depende del móvil, las gafas, sus rutas Bluetooth, la red y el servidor del proveedor. Los modelos y las voces disponibles dependen de la cuenta, la región y el motor instalado. MetaLima Ai no requiere membresía. Los proveedores pueden pedir claves, cobrar por uso o aplicar cuotas.
- Si Gemini devuelve 503, el chat realiza hasta dos reintentos breves. Si el servicio sigue saturado, aparece un aviso específico. [Guía de Google sobre respuestas 503](https://ai.google.dev/gemini-api/docs/generate-content/api-errors).
- Por OK Lima, la consulta empieza al sonar el tono. Se pide una respuesta breve y, si el proveedor tarda más de unos 11 segundos, la consulta se cancela, se anuncia el aviso y se vuelve a escuchar. Para instalaciones nuevas, Gemini usa Flash-Lite de forma predeterminada. El modelo guardado se conserva al actualizar.
- En **Inicio → OK Lima por Bluetooth**, se muestra la última pregunta reconocida. Con audio directo, se muestra la transcripción de Gemini cuando llega su respuesta.
- Para confirmar el sonido que oyes, comprobar la cámara y la latencia de las Ray-Ban Meta y probar la publicación RTMP, se necesitan las gafas, el móvil y las cuentas que se usarán. Para verificar el destinatario en PayPal, abre el botón y comprueba lo que muestra la página antes de confirmar.

## Actualizaciones desde la aplicación

Al abrir MetaLima aparece un aviso si GitHub publica un APK más reciente. En **Laboratorio**, también se destaca el botón **Actualizar**. La app descarga el APK, verifica que corresponda a MetaLima y comprueba su firma. Android pide confirmar la instalación y, la primera vez, puede pedir permiso para que MetaLima instale actualizaciones.

En el emulador se comprobó el aviso con una publicación real de GitHub, la descarga desde Laboratorio, el permiso de instalación y el cambio de versión confirmado por Android.

## Pruebas de la 0.5.0

En un HONOR con Android 13 se instaló la actualización sobre la 0.4.0 y se conservó la configuración. Con unas Ray-Ban Meta se verificaron la vista previa, el micrófono y el tono por Bluetooth SCO, el reconocimiento de «OK Lima» seguido de una petición real y la liberación del micrófono al apagar el interruptor. En un emulador se comprobó el gesto para abrir el chat, la elección de montura por dispositivo, la selección del APK de actualización y la exportación vertical y horizontal con audio. Los comandos de formato se probaron con audio de muestra. Quedan por confirmar la escucha continua con el móvil bloqueado, las sesiones completas con los servicios de IA y las emisiones a plataformas públicas.

## Pruebas de la 0.4.0

Se verificó en el emulador que la 0.4.0 se instala encima de la 0.3.0. Se probaron los cinco idiomas de la interfaz, la persistencia al cerrar y abrir, el regreso al idioma del teléfono y la sincronización con Android. También se verificó que el idioma del asistente se conserva al cambiar la interfaz y que la actualización no solicita el micrófono al abrir, con el Bluetooth apagado.

### Pruebas de la 0.3.0

Se compiló y verificó el APK de prueba. En el emulador se instaló encima de la 0.2.0 con el Bluetooth apagado y la activación por voz habilitada en la configuración anterior. Android registró un acceso al micrófono al abrir la versión anterior y no registró un nuevo acceso al abrir la 0.3.0. También se probó el selector de cinco idiomas, la persistencia del idioma al reiniciar y la apertura de PayPal desde Donaciones.

## Dependencias y créditos

- Android, Kotlin, Jetpack Compose y OkHttp.
- AndroidX Media3 Transformer procesa la exportación de vídeos. [Documentación de Media3](https://developer.android.com/media/media3/transformer).
- OK Lima usa [Vosk y su modelo ligero de español](https://alphacephei.com/vosk/models) con licencia Apache 2.0.
- La integración de gafas y partes de la cámara y grabación derivan de CameraAccess de [Meta Wearables DAT para Android](https://github.com/meta/meta-wearables-dat-android/tree/main/samples/CameraAccess).
- La grabación interactiva y el transporte RTMP usan [RootEncoder](https://github.com/pedroSG94/RootEncoder).
- La tipografía DM Sans se distribuye bajo OFL. Los iconos de plataformas se adaptaron de [Simple Icons 15.0.0](https://github.com/simple-icons/simple-icons/tree/15.0.0) y representan marcas de sus titulares.
- Los iconos de asistentes identifican sus respectivos servicios y proceden de [ChatGPT](https://chatgpt.com/), [Gemini](https://gemini.google.com/), [Muse](https://muse.ai/), [Grok](https://grok.com/) y [Qwen](https://chat.qwen.ai/).
- Las licencias y avisos de terceros están en `avisos/`.
