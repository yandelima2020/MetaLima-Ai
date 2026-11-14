package com.lima.metalima

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.TextureView
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lima.metalima.camera.CameraViewModel
import com.lima.metalima.camera.CapturePreview
import com.lima.metalima.livemode.LiveConversationScreen
import com.lima.metalima.livemode.LiveConversationViewModel
import com.lima.metalima.livemode.LiveModel
import com.lima.metalima.livemode.LivePhase
import com.lima.metalima.livemode.LiveAudio
import com.lima.metalima.livemode.LiveSocket
import com.lima.metalima.livemode.Translation
import com.lima.metalima.wearables.WearablesViewModel
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DESCRIBE_IMAGE = "Describe lo que se ve en esta imagen."

@Composable internal fun MetaLimaApp(activity: MainActivity, glasses: WearablesViewModel, camera: CameraViewModel, assistant: AssistantViewModel,
    conversation: LiveConversationViewModel) {
    val prefs = remember { StudioPrefs(activity) }
    var skin by remember { mutableStateOf(prefs.skin) }
    var language by remember { mutableStateOf(prefs.language) }
    SideEffect {
        androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = skin.light
            isAppearanceLightNavigationBars = skin.light
        }
    }
    StudioTheme(skin) {
    val hardware by glasses.uiState.collectAsStateWithLifecycle()
    val capture by camera.uiState.collectAsStateWithLifecycle()
    val ai by assistant.state.collectAsStateWithLifecycle()
    val live by BroadcastService.state.collectAsStateWithLifecycle()
    val conversationState by conversation.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val noteStore = remember { NoteStore(activity) }
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var page by rememberSaveable { mutableStateOf("") }
    var liveProviderToConfigure by rememberSaveable { mutableStateOf<Provider?>(null) }
    var liveModel by rememberSaveable { mutableStateOf(LiveModel.entries.firstOrNull { it.id == prefs.liveModel } ?: LiveModel.GEMINI_31) }
    var liveOnPhone by rememberSaveable { mutableStateOf(false) }
    var languageFrom by rememberSaveable { mutableStateOf("Español") }
    var languageTo by rememberSaveable { mutableStateOf("Inglés") }
    var imageLanguage by rememberSaveable { mutableStateOf("Español") }
    val isLivePage = page == "conversation-live" || page == "translation"
    var liveVision by remember { mutableStateOf(false) }
    var liveOwnsCamera by remember { mutableStateOf(false) }
    var liveRequestedCamera by remember { mutableStateOf(false) }
    var noteDraft by rememberSaveable { mutableStateOf("") }
    var noteAttachment by remember { mutableStateOf<String?>(null) }
    var savingNote by remember { mutableStateOf(false) }
    var creatorVoice by remember { mutableStateOf(false) }
    var audioResult by remember { mutableStateOf<String?>(null) }
    var audioTest by remember { mutableStateOf<Job?>(null) }
    var audioTesting by remember { mutableStateOf(false) }
    var apiResult by remember { mutableStateOf<String?>(null) }
    var apiTest by remember { mutableStateOf<Job?>(null) }
    var apiTesting by remember { mutableStateOf(false) }
    var responseSpoken by rememberSaveable { mutableStateOf(prefs.speak) }
    var wake by remember { mutableStateOf(false) }
    var voiceCommandPending by remember { mutableStateOf(false) }
    var wakePhrase by remember(language) { mutableStateOf(prefs.wakePhrase(language)) }
    var dictating by remember { mutableStateOf(false) }
    var voiceReady by remember { mutableStateOf(false) }
    val glassesVoice = remember { GlassesVoice(activity) }
    var voiceJob by remember { mutableStateOf<Job?>(null) }
    var finishVoice by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var playbackJob by remember { mutableStateOf<Job?>(null) }
    var lastVoiceAnswer by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var renderedPreview by remember { mutableStateOf<TextureView?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var outgoingUrl by remember { mutableStateOf<String?>(null) }
    var pendingRecording by rememberSaveable { mutableStateOf(false) }
    var audio by rememberSaveable { mutableStateOf(false) }
    var quickOverlay by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("Natural") }
    var reaction by rememberSaveable { mutableStateOf("") }
    var help by rememberSaveable { mutableStateOf(false) }
    var location by rememberSaveable { mutableStateOf<String?>(null) }
    val speaker = remember { TextToSpeech(activity) { voiceReady = it == TextToSpeech.SUCCESS } }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voiceJob?.cancel()
                playbackJob?.cancel()
                audioTest?.cancel()
                apiTest?.cancel()
                speaker.stop()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    fun notify(message: String) { scope.launch { snack.showSnackbar(message) } }
    fun go(route: String) { wake = false; creatorVoice = false; page = route; fullscreen = false }
    fun stopBroadcast() {
        activity.startService(Intent(activity, BroadcastService::class.java).setAction(BroadcastService.ACTION_STOP))
    }
    fun disableLiveVision() {
        liveVision = false
        liveRequestedCamera = false
        if (liveOwnsCamera) {
            camera.cancelCameraPermissionRedirect()
            camera.stopStreaming()
        }
        liveOwnsCamera = false
    }
    fun finishPage() {
        wake = false
        creatorVoice = false
        if (fullscreen) {
            if (live.active) stopBroadcast()
            fullscreen = false
        } else {
            if (page == "conversation-live" || page == "translation") {
                disableLiveVision()
                conversation.stop()
            }
            audioTest?.cancel()
            apiTest?.cancel()
            voiceJob?.cancel()
            playbackJob?.cancel()
            page = ""
        }
    }
    fun prepareSpeaker(voiceLanguage: AppLanguage = language) {
        check(voiceReady && speaker.setLanguage(voiceLanguage.locale) >= TextToSpeech.LANG_AVAILABLE) {
            "Activa una voz para ${voiceLanguage.label} en los ajustes de síntesis de voz de Android."
        }
        speaker.setSpeechRate(prefs.voiceRate)
        speaker.setPitch(prefs.pitch)
    }
    fun submit(question: String, photo: Bitmap? = null, speak: Boolean = responseSpoken, contextual: Boolean = true,
        voiceLanguage: AppLanguage = language, replyLanguage: String = voiceLanguage.promptName) {
        assistant.ask(question, photo, contextual, replyLanguage) {
            if (speak && page !in listOf("conversation-live", "translation") && !conversation.state.value.active &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                runCatching {
                    prepareSpeaker(voiceLanguage)
                    check(speaker.speak(it.take(3900), TextToSpeech.QUEUE_FLUSH, null, "answer") == TextToSpeech.SUCCESS) {
                        "No se pudo leer la respuesta. Está disponible en la conversación."
                    }
                }.onFailure { error -> notify(error.message ?: "No se pudo leer la respuesta.") }
            }
        }
    }
    fun speakOnGlasses(answer: String, headset: android.media.AudioDeviceInfo) {
        if (page != "ai" || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        speaking = true
        playbackJob = scope.launch {
            try {
                prepareSpeaker()
                glassesVoice.speak(speaker, answer, headset)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                notify(error.message ?: "No se pudo reproducir la respuesta en las gafas.")
            } catch (_: Exception) {
                notify("No se pudo reproducir la respuesta en las gafas. Puedes leerla en la conversación.")
            } finally {
                speaking = false
            }
        }
    }
    fun startGlassesTurn() {
        if (finishVoice != null || speaking || assistant.state.value.busy || capture.isRecording || live.active) return
        val finish = CompletableDeferred<Unit>()
        finishVoice = finish
        speaker.stop()
        voiceJob = scope.launch {
            try {
                check(activity.requestMicrophone()) { "Permite el micrófono de MetaLima Ai para hablar con las gafas." }
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || page != "ai") return@launch
                val headset = glassesVoice.selectHeadset(hardware.deviceDetails.values.map { it.name })
                val photo = image
                listening = false
                val wav = glassesVoice.record(headset, finish) { listening = true }
                lastVoiceAnswer = null
                assistant.askAudio(wav, photo, language) { answer ->
                    lastVoiceAnswer = answer
                    if (photo != null && image === photo) {
                        image = null
                        if (draft == DESCRIBE_IMAGE) draft = ""
                    }
                    if (responseSpoken) speakOnGlasses(answer, headset)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                notify(error.message ?: "No se pudo usar el micrófono de las gafas.")
            } catch (_: Exception) {
                notify("No se pudo usar el micrófono de las gafas. Revisa los permisos y la conexión Bluetooth.")
            } finally {
                finishVoice = null
                listening = false
            }
        }
    }
    val voiceBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && page == "ai") startGlassesTurn() else if (!it) notify("Permite Dispositivos cercanos para usar el micrófono Bluetooth.")
    }
    LaunchedEffect(page) {
        if (page != "ai") {
            voiceJob?.cancel()
            playbackJob?.cancel()
        }
    }
    fun startLiveConversation() {
        if (!isLivePage || conversation.state.value.active || capture.isRecording || live.active) return
        scope.launch {
            if (!activity.requestMicrophone()) {
                notify("Permite el micrófono para usar la conversación en directo.")
                return@launch
            }
            if (page !in listOf("conversation-live", "translation") || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            speaker.stop()
            val model = liveModel
            val translation = if (page == "translation") Translation(languageFrom, languageTo) else null
            conversation.start(model, { assistant.liveKey(model.provider) }, hardware.deviceDetails.values.map { it.name },
                liveOnPhone, assistant::saveLiveTurn, language, translation,
                if (model.supportsVisionTools && translation == null) studioLiveTools else emptyList()) { name, args ->
                if (name == "ver_gafas") {
                    check(liveVision) { "Activa Visión en la pantalla de la conversación primero." }
                    val bitmap = withTimeoutOrNull(6000) { camera.takeSnapshot() }
                        ?: error("Las gafas tardaron demasiado en entregar la foto.")
                    val jpeg = withContext(Dispatchers.Default) { try { jpegBase64(bitmap, 1024) } finally { bitmap.recycle() } }
                    liveActionResult("Foto de las gafas tomada ahora.", jpeg)
                } else {
                    val text = args.optString("texto").trim()
                    require(text.isNotBlank()) { "No se recibió ningún texto para guardar." }
                    val jpeg = if (args.optBoolean("adjuntar_foto")) {
                        check(liveVision) { "Activa Visión para guardar una nota con foto." }
                        val bitmap = withTimeoutOrNull(6000) { camera.takeSnapshot() } ?: error("No se pudo obtener la foto.")
                        withContext(Dispatchers.Default) { try { jpegBase64(bitmap, 1280) } finally { bitmap.recycle() } }
                    } else null
                    noteStore.save(text, jpeg)
                    notify("Nota guardada.")
                    liveActionResult("Nota guardada${if (jpeg != null) " con foto" else ""}.")
                }
            }
        }
    }
    val liveBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startLiveConversation() else notify("Permite Dispositivos cercanos para usar las gafas.")
    }
    LaunchedEffect(liveVision, capture.isSessionActive, capture.hasStream, capture.needsGlassesAppUpdate) {
        if (liveVision && capture.isSessionActive && !liveRequestedCamera && !capture.needsGlassesAppUpdate) {
            liveRequestedCamera = true
            if (!capture.hasStream) {
                liveOwnsCamera = true
                camera.startStreaming()
            }
        }
    }
    LaunchedEffect(conversationState.phase, page) {
        if (!isLivePage || conversationState.phase == LivePhase.STOPPING ||
            conversationState.phase == LivePhase.IDLE && conversationState.error != null) disableLiveVision()
    }
    val markingVoice = page == "creator" && creatorVoice && capture.isRecording && !capture.includeAudioInStream
    val canArmVoice = !isLivePage && page != "diagnostics" && !conversationState.active && !capture.isRecording &&
        !live.active && !ai.busy && !dictating && finishVoice == null && !speaking && !audioTesting
    LaunchedEffect(canArmVoice, tab) { if (!canArmVoice || tab != 0) wake = false }
    LaunchedEffect(capture.isRecording, capture.includeAudioInStream) {
        if (!capture.isRecording || capture.includeAudioInStream) creatorVoice = false
    }
    VoiceCommand(activity, markingVoice || (wake && canArmVoice && tab == 0 && page.isEmpty()),
        if (markingVoice) language.markCommand else wakePhrase, language) { question, message ->
        wake = false
        creatorVoice = false
        message?.let(::notify)
        if (question == null) return@VoiceCommand
        if (markingVoice) {
            notify(if (camera.markRecording()) "Momento marcado." else "Espera al primer fotograma o revisa el límite de 30 marcas.")
            return@VoiceCommand
        }
        go("ai")
        if (question.isNotBlank() && ai.hasKey) submit(question)
        else if (question.isNotBlank()) draft = question
    }
    fun armVoiceCommand(mark: Boolean) {
        if (voiceCommandPending) return
        if (!mark && wake) { wake = false; return }
        if (mark && creatorVoice) { creatorVoice = false; return }
        if (!mark && !canArmVoice) return
        voiceCommandPending = true
        val selectedPage = page
        scope.launch {
            try {
                if (!activity.requestMicrophone()) notify("Permite el micrófono para usar los comandos de voz.")
                else if (page == selectedPage && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    speaker.stop()
                    if (mark && camera.uiState.value.let { it.isRecording && !it.includeAudioInStream }) creatorVoice = true
                    if (!mark && page.isEmpty() && tab == 0) wake = true
                }
            } finally { voiceCommandPending = false }
        }
    }
    val noteDictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        dictating = false
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.let { noteDraft = it }
    }
    fun dictateNote() {
        dictating = true
        runCatching {
            noteDictation.launch(dictationRequest(language))
        }.onFailure { dictating = false; notify("Android no ofrece un servicio de dictado compatible.") }
    }
    fun runAudioTest() {
        if (audioTesting) return
        if (capture.isRecording || live.active) { notify("Detén la grabación o emisión antes de probar el sonido."); return }
        audioTesting = true
        audioTest = scope.launch {
            val audio = LiveAudio(activity, 16_000)
            audioResult = null
            try {
                check(activity.requestMicrophone()) { "Permite el micrófono para realizar la prueba." }
                check(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    "Vincula las gafas o concede Dispositivos cercanos en Ajustes."
                }
                val route = audio.open(hardware.deviceDetails.values.map { it.name }, false)
                audioResult = "Habla ahora. Después sonará un tono breve."
                val signal = audio.testSignal()
                audioResult = "${if (signal) "Se detectó señal del micrófono." else "No se detectó voz suficiente."} Salida: $route. Confirma si oíste el tono en las gafas."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) { audioResult = error.message ?: "No se pudo probar el audio." }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { audio.close() }
                audioTesting = false
            }
        }
    }
    fun runApiTest() {
        if (apiTesting) return
        apiTesting = true
        apiTest = scope.launch {
            apiResult = null
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val socket = LiveSocket(assistant.liveKey(liveModel.provider), liveModel, null, language)
                try {
                    check(withTimeoutOrNull(12_000) { socket.ready.await(); true } == true) { "El modelo no respondió a tiempo." }
                    apiResult = "Sesión aceptada en ${android.os.SystemClock.elapsedRealtime() - started} ms."
                } finally { socket.close() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { apiResult = error.message ?: "No se pudo conectar." }
            finally { apiTesting = false }
        }
    }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        dictating = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                if (assistant.state.value.let { it.hasKey && !it.busy }) {
                    submit(spoken, image)
                    draft = ""
                    image = null
                } else draft = spoken
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val destination = page
        if (uri != null) scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching {
                val source = android.graphics.ImageDecoder.createSource(activity.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val scale = (1280f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull() }
            if (loaded == null) notify("No se pudo abrir la imagen.")
            else if (destination == "notes") {
                noteAttachment = withContext(Dispatchers.Default) { try { jpegBase64(loaded) } finally { loaded.recycle() } }
            } else if (destination == "vision-translate") {
                image = loaded
            } else {
                image = loaded
                if (draft.isBlank()) draft = DESCRIBE_IMAGE
            }
        }
    }
    val screenShare = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val endpoint = outgoingUrl
        val record = pendingRecording
        pendingRecording = false
        outgoingUrl = null
        val token = result.data
        if (result.resultCode == Activity.RESULT_OK && token != null && (endpoint != null || record)) {
            runCatching {
                activity.startForegroundService(Intent(activity, BroadcastService::class.java).putExtra("consent", token)
                    .putExtra("endpoint", endpoint).putExtra("record", record).putExtra("audio", audio).putExtra("landscape", prefs.landscape).putExtra("fps", if (record) prefs.fps else prefs.streamFps).putExtra("route", if (record) "Automática" else prefs.streamAudio))
                go("cast")
                fullscreen = true
            }.onFailure { notify("No se pudo iniciar el servicio de emisión.") }
        }
    }
    fun startStream() {
        runCatching {
            val endpoint = prefs.streamAddress(prefs.platform)
            val url = URI(endpoint)
            require(url.scheme in listOf("rtmp", "rtmps") && !url.host.isNullOrBlank() && prefs.secret(prefs.platform))
            scope.launch {
                if (audio && !activity.requestMicrophone()) { notify("Permite el micrófono o desactiva el audio del directo."); return@launch }
                outgoingUrl = endpoint
                val manager = activity.getSystemService(MediaProjectionManager::class.java)
                runCatching { screenShare.launch(manager.createScreenCaptureIntent()) }.onFailure {
                    outgoingUrl = null
                    notify("No se pudo solicitar el permiso de pantalla.")
                }
            }
        }.onFailure { notify("Guarda una dirección RTMP o RTMPS y una clave de emisión en Ajustes.") }
    }
    fun startInteractiveRecording() {
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        scope.launch {
            if (audio && !activity.requestMicrophone()) { notify("Permite el micrófono o desactiva el audio."); return@launch }
            pendingRecording = true
            runCatching { screenShare.launch(manager.createScreenCaptureIntent()) }.onFailure {
                pendingRecording = false
                notify("No se pudo solicitar el permiso de pantalla.")
            }
        }
    }
    fun readLocation() {
        val manager = activity.getSystemService(android.location.LocationManager::class.java)
        runCatching {
            val providers = manager.getProviders(true)
            val fix = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            fun update(value: android.location.Location?) {
                if (value == null) notify("No se encontró la ubicación. Comprueba que esté activada en Android.")
                else location = String.format(Locale.US, "%.5f,%.5f", value.latitude, value.longitude)
            }
            if (fix != null && System.currentTimeMillis() - fix.time < 120_000) update(fix)
            else {
                val source = providers.firstOrNull { it == android.location.LocationManager.FUSED_PROVIDER }
                    ?: providers.firstOrNull { it == android.location.LocationManager.NETWORK_PROVIDER }
                if (source == null) update(fix)
                else manager.getCurrentLocation(source, null, activity.mainExecutor) { update(it ?: fix) }
            }
        }.onFailure { notify("No se pudo leer la ubicación del móvil.") }
    }
    val gps = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) readLocation() else notify("Permiso de ubicación denegado.") }
    LaunchedEffect(hardware.recentError?.id) { hardware.recentError?.let { snack.showSnackbar(it.message); glasses.clearRecentError(it.id) } }
    LaunchedEffect(ai.notice) { ai.notice?.let { snack.showSnackbar(it) } }
    LaunchedEffect(capture.hasStream) { if (!capture.hasStream) renderedPreview = null }
    LaunchedEffect(live.message, live.active) { if (!live.active && live.message.isNotBlank()) snack.showSnackbar(live.message) }
    BackHandler(page.isNotEmpty(), onBack = ::finishPage)

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (!fullscreen) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (page.isNotEmpty()) IconButton(onClick = ::finishPage) { Icon(Icons.Outlined.ArrowBack, "Volver") }
                else Box(Modifier.size(38.dp).background(Color(0xFF111A20), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Image(androidx.compose.ui.res.painterResource(R.drawable.ic_metalima_foreground), null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(10.dp))
                Text("MetaLima Ai", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { help = true }) { Icon(Icons.Outlined.HelpOutline, "Ayuda", tint = Muted) }
            }
        },
        bottomBar = {
            if (page.isEmpty()) NavigationBar(containerColor = SurfaceTone) {
                listOf("Inicio" to Icons.Outlined.Home, "Historial" to Icons.Outlined.History,
                    "Laboratorio" to Icons.Outlined.Science, "Ajustes" to Icons.Outlined.Settings,
                    "Donaciones" to Icons.Outlined.FavoriteBorder).forEachIndexed { index, item ->
                    NavigationBarItem(selected = index == tab, onClick = { liveProviderToConfigure = null; tab = index }, icon = { Icon(item.second, null) },
                        label = { Text(item.first, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Lime,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary, selectedTextColor = Lime))
                }
            }
        }
    ) { padding ->
        if (fullscreen && page == "cast") {
            Box(Modifier.fillMaxSize().background(Ink)) {
                Viewfinder(capture.isStreaming, capture.hasStream, surface = camera::setSurface,
                    modifier = Modifier.fillMaxSize(), hud = quickOverlay, reaction = reaction, filter = filter)
                if (!live.active) IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) { Icon(Icons.Outlined.Close, "Salir de pantalla completa") }
                else TextButton(onClick = ::finishPage,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text(if (live.recording) "Detener grabación" else "Detener directo") }
            }
        } else if (isLivePage) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                val canSee = liveModel.supportsVisionTools && page == "conversation-live"
                LiveConversationScreen(conversationState, liveModel, liveOnPhone,
                    if (liveModel.provider == Provider.GEMINI) ai.hasGeminiKey else ai.hasOpenAiKey,
                    capture.isRecording || live.active || finishVoice != null || speaking || ai.busy || audioTesting,
                    select = { liveModel = it; prefs.liveModel = it.id },
                    choosePhone = { liveOnPhone = it },
                    configure = { liveProviderToConfigure = liveModel.provider; page = ""; tab = 3 },
                    start = {
                        if (liveOnPhone || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            startLiveConversation()
                        } else liveBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }, stop = { disableLiveVision(); conversation.stop() },
                    translation = if (page == "translation") Translation(languageFrom, languageTo) else null,
                    chooseLanguages = { languageFrom = it.from; languageTo = it.to },
                    language = language,
                    enableVision = if (canSee) ({
                        if (liveVision) disableLiveVision()
                        else if (capture.needsGlassesAppUpdate) notify("Revisa Conexiones de aplicaciones en Meta AI desde Diagnóstico.")
                        else if (!hardware.hasActiveDevice) notify("Conecta las gafas en Meta AI para usar Visión.")
                        else {
                            liveVision = true
                            if (!capture.hasSession) camera.startSession()
                        }
                    }) else null,
                    takeFrame = if (canSee && liveVision && capture.isStreaming && conversationState.phase == LivePhase.LISTENING) ({
                        scope.launch {
                            try {
                                val bitmap = withTimeoutOrNull(6000) { camera.takeSnapshot() } ?: error("La foto no llegó a tiempo.")
                                val jpeg = withContext(Dispatchers.Default) { try { jpegBase64(bitmap, 1024) } finally { bitmap.recycle() } }
                                check(conversation.sendFrame(jpeg)) { "Gemini Live aún no está conectado. Inténtalo de nuevo." }
                                notify("Imagen enviada. Haz tu pregunta por voz.")
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { notify(error.message ?: "No se pudo enviar la imagen.") }
                        }
                    }) else null,
                    visionBusy = capture.isBusy || capture.isCapturingPhoto,
                    visualPreview = if (liveVision) ({
                        Viewfinder(capture.isStreaming, capture.hasStream, camera::setSurface,
                            Modifier.fillMaxWidth().height(112.dp), false, "", "Natural")
                    }) else null)
            }
        } else key(page, tab) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    "diagnostics" -> DiagnosticsScreen(hardware, capture, conversationState, liveModel,
                        activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                        audioResult, audioTesting, apiResult, apiTesting,
                        ::runAudioTest, ::runApiTest, { go("camera") }, { glasses.openGlassesAppUpdate(activity) })
                    "vision-translate" -> {
                        Heading("Traducir una imagen", "Elige una foto de la galería y el idioma de destino.")
                        Choice("Traducir a", imageLanguage, translationLanguages) { imageLanguage = it }
                        OutlinedButton(onClick = { photoPicker.launch("image/*") }) { Text("Elegir foto") }
                        image?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), "Imagen para traducir", Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Fit)
                            Button(enabled = ai.hasKey && !ai.busy, onClick = {
                                val request = "Lee el texto de la imagen y tradúcelo a $imageLanguage. Indica qué partes no se pueden leer."
                                go("ai")
                                val translatedVoice = AppLanguage.entries.firstOrNull {
                                    it.promptName.startsWith(imageLanguage.lowercase(Locale.ROOT))
                                }
                                submit(request, bitmap, speak = responseSpoken && translatedVoice != null, contextual = false,
                                    voiceLanguage = translatedVoice ?: language, replyLanguage = imageLanguage)
                                image = null
                            }) { Text("Traducir") }
                        }
                        if (!ai.hasKey) Text("Configura un proveedor de IA compatible con imágenes en Ajustes.", color = Muted)
                    }
                    "notes" -> NotesScreen(noteStore, noteDraft, { noteDraft = it }, ::dictateNote,
                        { photoPicker.launch("image/*") }, noteAttachment, { noteAttachment = null }, savingNote) {
                        val text = noteDraft
                        val attachment = noteAttachment
                        savingNote = true
                        scope.launch {
                            try {
                                noteStore.save(text, attachment)
                                if (noteDraft == text) noteDraft = ""
                                if (noteAttachment == attachment) noteAttachment = null
                                notify("Nota guardada.")
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { notify(error.message ?: "No se pudo guardar la nota.") }
                            finally { savingNote = false }
                        }
                    }
                    "ai" -> {
                        Heading("Conversación", "Pregunta por voz, por escrito o con una foto.")
                        ActionTile(Icons.Outlined.Headphones, "Conversación en directo", liveModel.label) { go("conversation-live") }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            AssistChip(onClick = { go("gps") }, label = { Text("GPS") }, leadingIcon = { Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = { go("vision") }, label = { Text("Visión") }, leadingIcon = { Icon(Icons.Outlined.Visibility, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = {
                                val query = draft.ifBlank { ai.turns.lastOrNull()?.user.orEmpty() }
                                if (query.isBlank()) notify("Escribe una consulta para buscar en la web.")
                                else runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))) }
                                    .onFailure { notify("No se pudo abrir el navegador.") }
                            }, label = { Text("Web") }, leadingIcon = { Icon(Icons.Outlined.Language, null, Modifier.size(16.dp)) })
                        }
                        if (!ai.hasKey) ActionTile(Icons.Outlined.Key, "Configurar ${ai.provider.label}", "Añade una clave para empezar") { page = ""; tab = 3 }
                        if (ai.provider == Provider.GEMINI && ai.hasKey) Block {
                            val recording = finishVoice
                            Button(
                                enabled = if (recording != null) listening && !recording.isCompleted
                                    else !ai.busy && !speaking && !dictating && !capture.isRecording && !live.active,
                                onClick = {
                                    if (recording != null) {
                                        listening = false
                                        recording.complete(Unit)
                                    } else if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                        startGlassesTurn()
                                    } else voiceBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                }
                            ) {
                                Icon(Icons.Outlined.Headphones, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(when {
                                    listening -> "Enviar audio"
                                    recording != null && recording.isCompleted -> "Procesando audio…"
                                    recording != null -> "Conectando micrófono…"
                                    ai.busy -> "Consultando Gemini…"
                                    speaking -> "Reproduciendo respuesta…"
                                    else -> "Hablar con las gafas"
                                })
                            }
                            Text(if (listening) "Escuchando. Haz tu pregunta." else "Pulsa y habla cuando el micrófono esté listo. El turno se envía en un máximo de 12 segundos.", color = Muted)
                            if (recording != null) TextButton(onClick = { voiceJob?.cancel() }) { Text("Cancelar audio") }
                            lastVoiceAnswer?.takeIf { it == ai.turns.lastOrNull { turn -> turn.session == ai.session }?.answer }?.let { answer ->
                                TextButton(enabled = !speaking && recording == null && !ai.busy, onClick = {
                                    runCatching { glassesVoice.selectHeadset(hardware.deviceDetails.values.map { it.name }) }
                                        .onSuccess { speakOnGlasses(answer, it) }
                                        .onFailure { notify(it.message ?: "No se encontró la salida Bluetooth.") }
                                }) { Text("Repetir respuesta en las gafas") }
                            }
                        }
                        val canType = finishVoice == null && !speaking
                        TextButton(enabled = !ai.busy && canType, onClick = { assistant.newSession(); draft = ""; image = null }) { Text("Nueva conversación") }
                        ai.turns.filter { it.session == ai.session }.takeLast(6).forEach { Msg(it) }
                        image?.let { attached ->
                            Image(attached.asImageBitmap(), "Imagen adjunta", Modifier.fillMaxWidth().height(160.dp).clip(Panel), contentScale = ContentScale.Fit)
                            TextButton(enabled = canType, onClick = { image = null }) { Text("Quitar foto") }
                        }
                        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text("Escribe tu pregunta") }, maxLines = 5)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(enabled = canType, onClick = { photoPicker.launch("image/*") }) { Icon(Icons.Outlined.AttachFile, "Adjuntar imagen") }
                            IconButton(enabled = canType && !ai.busy, onClick = { dictating = true; runCatching { dictation.launch(dictationRequest(language)) }
                                .onFailure { dictating = false; notify("Android no dispone de un servicio de dictado compatible.") } }) { Icon(Icons.Outlined.Mic, "Dictar pregunta") }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { submit(draft, image); draft = ""; image = null }, enabled = canType && ai.hasKey && !ai.busy && draft.isNotBlank()) { Text(if (ai.busy) "Enviando…" else "Enviar") }
                        }
                        SwitchRow("Leer respuestas en voz alta", responseSpoken) { responseSpoken = it; prefs.speak = it }
                    }
                    "camera", "vision", "cast", "photo", "creator" -> {
                        val mode = page
                        Heading(when (mode) { "creator" -> "Modo creador"; "vision" -> "Visión con IA"; "cast" -> "Compartir cámara"; "photo" -> "Análisis de fotos"; else -> "Cámara y grabación" },
                            when (mode) { "creator" -> "Usa la guía 9:16 y marca las tomas que quieras exportar."; "vision" -> "Analiza un fotograma cuando tú lo pidas."; "cast" -> "Abre el visor a pantalla completa para compartirlo desde otra app."; "photo" -> "Haz una foto desde las gafas o usa una de tu galería."; else -> "Captura y guarda fotos o vídeo en tu galería." })
                        if (capture.needsGlassesAppUpdate) ActionTile(Icons.Outlined.Link, "Revisar conexión en Meta AI",
                            "Meta solicita revisar Conexiones de aplicaciones. Este paso es independiente del firmware.") {
                            glasses.openGlassesAppUpdate(activity)
                        }
                        if (mode in listOf("photo", "vision")) OutlinedButton(onClick = { go("ai"); photoPicker.launch("image/*") }) { Text("Elegir foto de la galería") }
                        if (hardware.isFirmwareUpdateRequired) ActionTile(Icons.Outlined.SystemUpdate, "Actualizar gafas", "Meta AI abrirá la actualización") { glasses.openFirmwareUpdate(activity) }
                        if (mode == "creator" && prefs.quality == "FLUIDA") {
                            Text("Para fijar la resolución del archivo, elige Baja, Media o Alta en Ajustes avanzados de vídeo antes de abrir la cámara.", color = Muted)
                        }
                        if (!hardware.isRegistered) ActionTile(Icons.Outlined.Bluetooth, "Vincular mis gafas", "Confirma el acceso en Meta AI", onClick = activity::connectGlasses)
                        Viewfinder(capture.isStreaming, capture.hasStream, camera::setSurface, Modifier.fillMaxWidth().aspectRatio(if (prefs.landscape) 16 / 9f else 4 / 5f),
                            quickOverlay, reaction, filter, onFrame = { renderedPreview = it }, creatorGuide = mode == "creator")
                        capture.adaptiveLabel?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelMedium) }
                        if (hardware.isRegistered) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = !capture.isBusy && (capture.hasSession || hardware.hasActiveDevice), onClick = {
                                    if (capture.hasSession) camera.endSession() else camera.startSession()
                                }) { Text(if (capture.hasSession) "Cerrar sesión" else if (capture.needsGlassesAppUpdate) "Reintentar sesión" else "Iniciar sesión") }
                                Button(enabled = !capture.isBusy && capture.isSessionActive, onClick = {
                                    if (capture.hasStream) camera.stopStreaming() else camera.startStreaming()
                                }) { Text(if (capture.hasStream) "Cerrar cámara" else "Abrir cámara") }
                            }
                        }
                        if (capture.hasStream) {
                            if (mode == "vision") {
                                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text("¿Qué quieres saber de la imagen?") })
                                Button(enabled = capture.isStreaming && renderedPreview != null && !ai.busy && ai.hasKey, onClick = {
                                    val frame = renderedPreview?.bitmap
                                    if (frame == null) notify("Espera a que aparezca el vídeo de las gafas.")
                                    else submit(draft.ifBlank { DESCRIBE_IMAGE }, frame, contextual = false)
                                }) { Text(if (ai.busy) "Analizando…" else "Analizar fotograma") }
                                ai.turns.lastOrNull()?.let { Msg(it) }
                            } else if (mode == "cast") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { fullscreen = true }) { Text("Pantalla completa") }
                                    if (!live.active) OutlinedButton(onClick = ::startInteractiveRecording) { Text("Grabar visor") }
                                    if (live.active) OutlinedButton(onClick = ::stopBroadcast) { Text(if (live.recording) "Detener grabación" else "Detener directo") }
                                }
                                SwitchRow("Micrófono del móvil al grabar el visor", audio, !live.active) { audio = it }
                                SwitchRow("Mostrar HUD", quickOverlay) { quickOverlay = it }
                                Choice("Filtro del visor", filter, listOf("Natural", "Blanco y negro", "Cálido", "Frío")) { filter = it }
                                Choice("Reacción visible", reaction.ifBlank { "Ninguna" }, listOf("Ninguna", "¡Bravo!", "En directo", "¡Gol!")) { reaction = if (it == "Ninguna") "" else it }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = capture.isStreaming && !capture.isCapturingPhoto, onClick = camera::capturePhoto) { Text("Hacer foto") }
                                    Button(enabled = capture.isStreaming || capture.isRecording, onClick = { camera.toggleRecording(activity::requestMicrophone) }) { Text(if (capture.isRecording) "Detener grabación" else "Grabar vídeo") }
                                }
                                if (capture.isRecording) Text("Grabando · ${capture.recordingElapsedSeconds} s", color = Lime)
                                SwitchRow("Micrófono de las gafas", capture.includeAudioInStream, !capture.isRecording && capture.isStreaming) { camera.toggleMic() }
                                if (mode == "creator") {
                                    if (capture.isRecording && !capture.includeAudioInStream) {
                                        OutlinedButton(onClick = { armVoiceCommand(mark = true) }, enabled = !voiceCommandPending) {
                                            Text(if (creatorVoice) "Cancelar escucha" else "Marcar con la voz")
                                        }
                                        Text(if (creatorVoice) "Di «${language.markCommand}»." else "Puedes decir «${language.markCommand}» tras pulsar.", color = Muted)
                                    }
                                    if (capture.isRecording) OutlinedButton(onClick = {
                                        if (camera.markRecording()) notify("Momento marcado.")
                                        else notify("Espera al primer fotograma o revisa el límite de 30 marcas.")
                                    }) { Text("Marcar momento · ${capture.recordingMarks.size}") }
                                }
                            }
                        }
                    }
                    "modes" -> {
                        Heading("Modo de grabación", "Elige qué quieres guardar en el móvil.")
                        ActionTile(Icons.Outlined.Speed, "Rendimiento", "Vídeo directo de la cámara de las gafas, con micrófono opcional") { go("camera") }
                        ActionTile(Icons.Outlined.AutoAwesome, "Interactivo", "Graba el visor con HUD y reacciones") { go("cast") }
                    }
                    "live" -> {
                        Heading("Emitir en directo", "Emite el visor de las gafas mediante captura de pantalla de Android.")
                        Block {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlatformIcon(prefs.platform)
                                Text(prefs.platform, style = MaterialTheme.typography.titleLarge)
                            }
                            Text(if (prefs.secret(prefs.platform)) "Clave de emisión guardada" else "Falta configurar la clave de emisión", color = Muted)
                            SwitchRow("Incluir micrófono del móvil", audio, !live.active) { audio = it }
                            if (prefs.platform in listOf("TikTok", "Instagram Live"))
                                Text("El directo requiere acceso a la emisión por RTMP en tu cuenta.", color = Muted)
                            if (live.message.isNotBlank()) Text(live.message, color = Lime)
                            Button(onClick = { if (live.active) stopBroadcast() else startStream() },
                                enabled = live.active || capture.isStreaming) { Text(if (live.active) "Detener directo" else "Iniciar directo") }
                        }
                        if (!capture.isStreaming) ActionTile(Icons.Outlined.Videocam, "Abrir cámara de las gafas", "Inicia una sesión y abre el visor") { go("camera") }
                        ActionTile(Icons.Outlined.Settings, "Configurar destino", "Plataforma, URL y clave de emisión") { page = ""; tab = 3 }
                    }
                    "gps" -> {
                        Heading("Ubicación y rutas", "Consulta la ubicación del móvil y abre una ruta o una búsqueda cercana en Maps.")
                        Button(onClick = { if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) readLocation() else gps.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Consultar ubicación") }
                        location?.let { Text(it, color = Lime) }
                        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text("Lugar o destino") })
                        Button(enabled = draft.isNotBlank(), onClick = {
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(draft)}"))) }
                                .onFailure { notify("No se pudo abrir Maps.") }
                        }) { Text("Abrir ruta en Maps") }
                        OutlinedButton(enabled = draft.isNotBlank(), onClick = {
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(draft)}"))) }
                                .onFailure { notify("No se pudo abrir Maps.") }
                        }) { Text("Buscar lugares") }
                    }
                    else -> when (tab) {
                        0 -> {
                            GlassesDashboard(hardware, capture, diagnose = { go("diagnostics") }, connect = activity::connectGlasses)
                            LiveLaunchCard(liveModel.label) { go("conversation-live") }
                            Section("Asistente")
                            OutlinedButton(onClick = { armVoiceCommand(mark = false) }, enabled = canArmVoice && !voiceCommandPending) {
                                Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (wake) "Cancelar escucha" else "Activar frase de voz")
                            }
                            if (wake) Text("Di «$wakePhrase» y tu pregunta.", color = Muted)
                            ActionTile(Icons.Outlined.Forum, "Conversación", ai.provider.label + " · Texto y dictado") { go("ai") }
                            ActionTile(Icons.Outlined.Visibility, "Visión con IA", "Pregunta sobre lo que ves con las gafas") { go("vision") }
                            ActionTile(Icons.Outlined.Translate, "Traducción por voz", "Traduce una conversación entre dos idiomas") { go("translation") }
                            ActionTile(Icons.Outlined.ImageSearch, "Traducir una imagen", "Lee y traduce carteles, cartas o documentos") { image = null; go("vision-translate") }
                            ActionTile(Icons.Outlined.EditNote, "Notas", "Ideas dictadas, texto y fotos") { go("notes") }
                            Section("Captura y directo")
                            ActionTile(Icons.Outlined.Movie, "Modo creador", "Guía vertical, marcas y exportación de fragmentos") { go("creator") }
                            ActionTile(Icons.Outlined.Videocam, "Grabar vídeo", "Elige Rendimiento o Interactivo") { go("modes") }
                            ActionTile(Icons.Outlined.CellTower, "Emitir en directo", prefs.platform) { go("live") }
                            ActionTile(Icons.Outlined.Cast, "Compartir cámara", "Visor de las gafas a pantalla completa") { go("cast") }
                            ActionTile(Icons.Outlined.PhotoCamera, "Análisis de fotos", "Haz una foto con las gafas o adjunta una imagen") { go("photo") }
                        }
                        1 -> History(ai.turns, assistant::clearHistory, assistant::deleteSessions, { assistant.selectSession(it); draft = ""; image = null; go("ai") }, ::notify)
                        2 -> {
                            Heading("Laboratorio")
                            ActionTile(Icons.Outlined.LocationOn, "Ubicación y rutas", "Localiza el móvil y abre rutas en Maps") { go("gps") }
                            ActionTile(Icons.Outlined.Cast, "HUD y reacciones", "Superposiciones para el visor compartido") { go("cast") }
                        }
                        3 -> StudioSettings(activity, glasses, assistant, prefs, responseSpoken, { responseSpoken = it; prefs.speak = it },
                            language, { language = it; prefs.language = it }, wakePhrase,
                            { wakePhrase = it; prefs.setWakePhrase(language, it) }, ::notify, liveProviderToConfigure,
                            skin, { skin = it; prefs.skin = it })
                        4 -> Donations(activity, ::notify)
                    }
                }
            }
        }
    }

    live.saved?.let { uri ->
        AlertDialog(onDismissRequest = BroadcastService::dismissSaved, title = { Text("Grabación guardada") },
            text = { Text("El vídeo está disponible en la galería de Android.") },
            confirmButton = { TextButton(onClick = { shareUri(activity, uri, "video/mp4"); BroadcastService.dismissSaved() }) { Text("Compartir") } },
            dismissButton = { TextButton(onClick = BroadcastService::dismissSaved) { Text("Cerrar") } })
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Ayuda y conexión") }, text = {
        Text("Vincula primero las gafas en Meta AI y activa el modo desarrollador para esta compilación. Después abre Ajustes en MetaLima Ai, elige un proveedor e introduce tu clave. Para grabar, inicia la sesión de las gafas y abre la cámara.")
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("Entendido") } })
    if (capture.showCameraPermissionRedirectConfirm) AlertDialog(onDismissRequest = camera::cancelCameraPermissionRedirect,
        title = { Text("Permitir cámara de las gafas") }, text = { Text("Meta AI te pedirá que autorices el acceso a la cámara.") },
        confirmButton = { TextButton(onClick = { camera.confirmCameraPermissionRedirect(activity::requestWearableCamera) }) { Text("Continuar") } },
        dismissButton = { TextButton(onClick = camera::cancelCameraPermissionRedirect) { Text("Cancelar") } })
    capture.activePreview?.let { file ->
        var writing by remember(file) { mutableStateOf(false) }
        var saved by remember(file) { mutableStateOf<Uri?>(null) }
        AlertDialog(onDismissRequest = { if (!writing) camera.dismissCapturePreview() }, title = { Text(if (file is CapturePreview.Photo) "Foto capturada" else "Grabación terminada") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                when (file) {
                    is CapturePreview.Photo -> Image(file.bitmap.asImageBitmap(), "Foto de las gafas", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    is CapturePreview.Video -> AndroidView(factory = { context -> VideoView(context).apply {
                        setVideoURI(file.uri); setMediaController(android.widget.MediaController(context).also { it.setAnchorView(this) }); seekTo(1)
                    } }, modifier = Modifier.fillMaxWidth().height(220.dp))
                }
                if (file is CapturePreview.Video && file.marks.isNotEmpty()) {
                    Text("Momentos marcados", style = MaterialTheme.typography.titleMedium)
                    file.marks.forEachIndexed { index, time ->
                        OutlinedButton(enabled = !writing, onClick = {
                            scope.launch {
                                writing = true
                                try {
                                    withContext(Dispatchers.IO) { exportMarkedClip(activity, file.uri, time) }
                                    notify("Fragmento ${index + 1} guardado en la galería.")
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { notify(error.message ?: "No se pudo exportar el fragmento.") }
                                finally { writing = false }
                            }
                        }) { Text("Exportar marca ${index + 1} · ${time / 1000} s") }
                    }
                }
                if (saved != null) Text("Guardado en la galería.", color = Lime)
            }
        }, confirmButton = {
            TextButton(enabled = !writing, onClick = {
                val destination = saved
                if (destination != null) shareUri(activity, destination, if (file is CapturePreview.Photo) "image/jpeg" else "video/mp4")
                else scope.launch {
                    writing = true
                    val result = withContext(Dispatchers.IO) { runCatching { storeCapture(activity, (file as? CapturePreview.Photo)?.bitmap, (file as? CapturePreview.Video)?.uri) } }
                    writing = false
                    result.onSuccess { saved = it }.onFailure { notify("No se pudo guardar la captura.") }
                }
            }) { Text(if (saved == null) "Guardar" else "Compartir") }
        }, dismissButton = {
            Row {
                if (file is CapturePreview.Photo) TextButton(enabled = !writing, onClick = {
                    image = file.bitmap
                    if (draft.isBlank()) draft = DESCRIBE_IMAGE
                    camera.dismissCapturePreview()
                    go("ai")
                }) { Text("Analizar") }
                TextButton(enabled = !writing, onClick = camera::dismissCapturePreview) { Text("Cerrar") }
            }
        })
    }
    }
}

@Composable private fun Viewfinder(streaming: Boolean, hasStream: Boolean, surface: (Surface?) -> Unit, modifier: Modifier, hud: Boolean, reaction: String, filter: String,
    onFrame: (TextureView?) -> Unit = {}, creatorGuide: Boolean = false) {
    val latestFrame by rememberUpdatedState(onFrame)
    val latestSurface by rememberUpdatedState(surface)
    var received by remember { mutableStateOf(false) }
    LaunchedEffect(hasStream) { if (!hasStream) received = false }
    Box(modifier.clip(Panel).background(Color(0xFF07090B)).border(1.dp, Line, Panel), contentAlignment = Alignment.Center) {
        AndroidView(factory = { context -> TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var output: Surface? = null
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    output = Surface(texture).also { latestSurface(it) }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    received = true
                    latestFrame(this@apply)
                }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    latestSurface(null)
                    received = false
                    latestFrame(null)
                    output?.release()
                    output = null
                    return true
                }
            }
        } }, modifier = Modifier.matchParentSize(), update = { view ->
            val matrix = android.graphics.ColorMatrix().apply {
                when (filter) {
                    "Blanco y negro" -> setSaturation(0f)
                    "Cálido" -> setScale(1.12f, 1.02f, .87f, 1f)
                    "Frío" -> setScale(.88f, 1.02f, 1.13f, 1f)
                }
            }
            val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(matrix) }
            view.setLayerPaint(if (filter == "Natural") null else paint)
        })
        if (!received) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DeviceArt(Modifier.width(155.dp).height(98.dp))
            Text(if (streaming) "Esperando imagen…" else "Cámara cerrada", color = Muted)
        }
        if (hud && received) {
            Box(Modifier.matchParentSize().padding(16.dp).border(1.dp, Lime.copy(alpha = .55f), RoundedCornerShape(12.dp)))
            Text("●  MetaLima Ai", color = Lime, modifier = Modifier.align(Alignment.TopStart).padding(26.dp))
        }
        if (creatorGuide && received) Box(Modifier.fillMaxHeight(.91f).aspectRatio(9 / 16f)
            .border(1.dp, Color.White.copy(alpha = .80f), RoundedCornerShape(4.dp)))
        if (reaction.isNotBlank() && received) Text(reaction, color = Lime, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(26.dp).background(Ink.copy(alpha = .8f), Panel).padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@Composable private fun Msg(turn: Turn) {
    val clipboard = LocalClipboardManager.current
    Block {
        Text(turn.user, style = MaterialTheme.typography.titleMedium, color = Lime)
        Text(turn.answer)
        TextButton(onClick = { clipboard.setText(AnnotatedString(turn.answer)) }) { Text("Copiar respuesta") }
    }
}

@Composable private fun History(turns: List<Turn>, clear: () -> Unit, delete: (Set<String>) -> Unit, resume: (String) -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val found = turns.groupBy { it.session }.values.filter { group ->
        query.isBlank() || group.any { it.user.contains(query, true) || it.answer.contains(query, true) }
    }.sortedByDescending { it.last().time }
    Heading("Historial", "Conversaciones guardadas en este dispositivo.")
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Buscar en el historial") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = found.isNotEmpty(), onClick = { scope.launch {
            val relevant = found.filter { selected.isEmpty() || it.first().session in selected }.flatten().sortedBy { it.time }
            if (relevant.isEmpty()) { notify("Selecciona conversaciones que aparezcan en la búsqueda."); return@launch }
            val exported = withContext(Dispatchers.IO) { runCatching { exportHistory(context, relevant) } }
            exported.onSuccess { shareUri(context, it, "application/pdf") }.onFailure { notify("No se pudo exportar el historial.") }
        } }) { Text("Exportar a PDF") }
        TextButton(enabled = turns.isNotEmpty(), onClick = { confirm = true }) { Text(if (selected.isEmpty()) "Borrar historial" else "Borrar selección") }
    }
    if (found.isEmpty()) Text(if (query.isBlank()) "Aún no hay conversaciones." else "No hay coincidencias.", color = Muted)
    found.forEach { group ->
        val first = group.first()
        val recent = group.last()
        Block {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = first.session in selected, onCheckedChange = { checked ->
                    selected = if (checked) selected + first.session else selected - first.session
                })
                Column(Modifier.weight(1f)) {
                    Text(first.user, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recent.time))} · ${group.size * 2} mensajes", color = Muted)
                }
            }
            TextButton(onClick = { resume(first.session) }) { Text("Continuar conversación") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (selected.isEmpty()) "¿Borrar todo el historial?" else "¿Borrar las conversaciones seleccionadas?") },
        confirmButton = { TextButton(onClick = { if (selected.isEmpty()) clear() else delete(selected); selected = emptySet(); confirm = false }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
}
