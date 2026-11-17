package com.lima.metalima

import androidx.compose.ui.res.stringResource
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.lima.metalima.livemode.displayLabel
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

@Composable internal fun MetaLimaApp(activity: MainActivity, glasses: WearablesViewModel, camera: CameraViewModel, assistant: AssistantViewModel,
    conversation: LiveConversationViewModel) {
    val prefs = remember { StudioPrefs(activity) }
    val describeImage = stringResource(R.string.describe_image)
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
    val (updates, updateAction) = rememberAppUpdates(activity)
    val handsFree by HandsFreeService.state.collectAsStateWithLifecycle()
    var handsFreeGeminiAudio by remember { mutableStateOf(prefs.handsFreeGeminiAudio) }
    val live by BroadcastService.state.collectAsStateWithLifecycle()
    val conversationState by conversation.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val noteStore = remember { NoteStore(activity) }
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var page by rememberSaveable { mutableStateOf("") }
    var sideChat by remember { mutableStateOf(false) }
    val chatSlide = remember { Animatable(1f) }
    var streamFormat by remember { mutableStateOf(prefs.streamFormat) }
    var recordFormat by remember { mutableStateOf(prefs.recordFormat) }
    var videoVoiceTarget by remember { mutableStateOf<VideoTarget?>(null) }
    DisposableEffect(prefs) {
        val formats = prefs.observeVideoFormats { recordFormat = prefs.recordFormat; streamFormat = prefs.streamFormat }
        onDispose { formats.close() }
    }
    var providerToConfigure by rememberSaveable { mutableStateOf<Provider?>(null) }
    var assistantSettingsReturn by rememberSaveable { mutableStateOf("") }
    var liveModel by rememberSaveable { mutableStateOf(LiveModel.entries.firstOrNull { it.id == prefs.liveModel } ?: LiveModel.GEMINI_38) }
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
    fun needsFreeMicrophone(): Boolean {
        if (!HandsFreeService.state.value.ownsMicrophone) return true
        notify(activity.getString(R.string.handsfree_stop_first))
        return false
    }
    fun go(route: String) {
        wake = false
        creatorVoice = false
        videoVoiceTarget = null
        if (route != "ai") sideChat = false
        page = route
        fullscreen = false
    }
    fun slideOpenChat() {
        if (page.isNotEmpty() || tab != 0) return
        scope.launch {
            chatSlide.snapTo(0f)
            sideChat = true
            go("ai")
            chatSlide.animateTo(1f, tween(225))
        }
    }
    fun saveStreamFormat(value: VideoFormat) { streamFormat = value; prefs.streamFormat = value }
    fun saveRecordFormat(value: VideoFormat) { recordFormat = value; prefs.recordFormat = value }
    fun applyVideoVoice(command: VideoVoiceCommand) {
        if (command.target == VideoTarget.STREAM) saveStreamFormat(streamFormat.copy(orientation = command.orientation))
        else saveRecordFormat(recordFormat.copy(orientation = command.orientation))
        notify(activity.getString(R.string.video_format_changed, activity.getString(command.orientation.label)))
    }
    fun configureAssistant(provider: Provider) {
        assistantSettingsReturn = page
        providerToConfigure = provider
        go("assistant-settings")
    }
    fun openAssistant(provider: Provider) {
        if (assistant.state.value.busy) return
        if (provider != assistant.state.value.provider) {
            draft = ""
            image = null
        }
        assistant.selectProvider(provider)
        go("ai")
    }
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
        videoVoiceTarget = null
        if (page == "assistant-settings") {
            go(assistantSettingsReturn)
            assistantSettingsReturn = ""
            providerToConfigure = null
        } else if (fullscreen) {
            if (live.active) stopBroadcast()
            fullscreen = false
        } else {
            sideChat = false
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
            activity.getString(R.string.activa_una_voz_para_en, voiceLanguage.label)
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
                        activity.getString(R.string.no_se_pudo_leer_la_3)
                    }
                }.onFailure { error -> notify(error.message ?: activity.getString(R.string.no_se_pudo_leer_la_2)) }
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
                notify(error.message ?: activity.getString(R.string.no_se_pudo_reproducir_la_2))
            } catch (_: Exception) {
                notify(activity.getString(R.string.no_se_pudo_reproducir_la))
            } finally {
                speaking = false
            }
        }
    }
    fun startGlassesTurn() {
        if (!needsFreeMicrophone()) return
        if (finishVoice != null || speaking || assistant.state.value.busy || capture.isRecording || live.active) return
        val finish = CompletableDeferred<Unit>()
        finishVoice = finish
        speaker.stop()
        voiceJob = scope.launch {
            try {
                check(activity.requestMicrophone()) { activity.getString(R.string.permite_el_microfono_de_metalima) }
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
                        if (draft == describeImage) draft = ""
                    }
                    if (responseSpoken) speakOnGlasses(answer, headset)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                notify(error.message ?: activity.getString(R.string.no_se_pudo_usar_el_2))
            } catch (_: Exception) {
                notify(activity.getString(R.string.no_se_pudo_usar_el))
            } finally {
                finishVoice = null
                listening = false
            }
        }
    }
    val voiceBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && page == "ai") startGlassesTurn() else if (!it) notify(activity.getString(R.string.permite_dispositivos_cercanos_para_usar_2))
    }
    LaunchedEffect(page) {
        if (page != "ai") {
            voiceJob?.cancel()
            playbackJob?.cancel()
        }
    }
    fun startLiveConversation() {
        if (!needsFreeMicrophone()) return
        if (!isLivePage || conversation.state.value.active || capture.isRecording || live.active) return
        scope.launch {
            if (!activity.requestMicrophone()) {
                notify(activity.getString(R.string.permite_el_microfono_para_usar_2))
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
                    check(liveVision) { activity.getString(R.string.activa_vision_en_la_pantalla) }
                    val bitmap = withTimeoutOrNull(6000) { camera.takeSnapshot() }
                        ?: error(activity.getString(R.string.las_gafas_tardaron_demasiado_en))
                    val jpeg = withContext(Dispatchers.Default) { try { jpegBase64(bitmap, 1024) } finally { bitmap.recycle() } }
                    liveActionResult("Foto de las gafas tomada ahora.", jpeg)
                } else {
                    val text = args.optString("texto").trim()
                    require(text.isNotBlank()) { activity.getString(R.string.no_se_recibio_ningun_texto) }
                    val jpeg = if (args.optBoolean("adjuntar_foto")) {
                        check(liveVision) { activity.getString(R.string.activa_vision_para_guardar_una) }
                        val bitmap = withTimeoutOrNull(6000) { camera.takeSnapshot() } ?: error(activity.getString(R.string.no_se_pudo_obtener_la))
                        withContext(Dispatchers.Default) { try { jpegBase64(bitmap, 1280) } finally { bitmap.recycle() } }
                    } else null
                    noteStore.save(text, jpeg)
                    notify(activity.getString(R.string.nota_guardada))
                    liveActionResult("Nota guardada${if (jpeg != null) " con foto" else ""}.")
                }
            }
        }
    }
    val liveBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startLiveConversation() else notify(activity.getString(R.string.permite_dispositivos_cercanos_para_usar))
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
        !live.active && !ai.busy && !dictating && finishVoice == null && !speaking && !audioTesting && !handsFree.ownsMicrophone
    LaunchedEffect(canArmVoice, tab) { if (!canArmVoice || tab != 0) wake = false }
    LaunchedEffect(capture.isRecording, capture.includeAudioInStream) {
        if (!capture.isRecording || capture.includeAudioInStream) creatorVoice = false
    }
    val formatVoice = videoVoiceTarget.takeIf { canArmVoice }
    LaunchedEffect(canArmVoice) { if (!canArmVoice) videoVoiceTarget = null }
    VoiceCommand(activity, markingVoice || formatVoice != null || (wake && canArmVoice && tab == 0 && page.isEmpty()),
        if (markingVoice) language.markCommand else if (formatVoice != null) null else wakePhrase, language) { question, message ->
        wake = false
        creatorVoice = false
        videoVoiceTarget = null
        message?.let(::notify)
        if (question == null) return@VoiceCommand
        if (markingVoice) {
            notify(if (camera.markRecording()) activity.getString(R.string.momento_marcado) else activity.getString(R.string.espera_al_primer_fotograma_o))
            return@VoiceCommand
        }
        val videoCommand = videoVoiceCommand(question, formatVoice)
        if (videoCommand != null) {
            applyVideoVoice(videoCommand)
            return@VoiceCommand
        }
        if (formatVoice != null) {
            notify(activity.getString(R.string.video_format_not_recognized))
            return@VoiceCommand
        }
        go("ai")
        if (question.isNotBlank() && ai.hasKey) submit(question)
        else if (question.isNotBlank()) draft = question
    }
    fun armVideoFormat(target: VideoTarget) {
        if (videoVoiceTarget != null) { videoVoiceTarget = null; return }
        if (!canArmVoice || voiceCommandPending) return
        wake = false
        voiceCommandPending = true
        val currentPage = page
        scope.launch {
            try {
                if (activity.requestMicrophone() && page == currentPage) videoVoiceTarget = target
            } finally { voiceCommandPending = false }
        }
    }
    fun startHandsFree() {
        val currentPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        if (currentPermissions.any { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            notify(activity.getString(R.string.handsfree_permissions))
            return
        }
        if (!canArmVoice || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        wake = false
        videoVoiceTarget = null
        runCatching {
            activity.startForegroundService(Intent(activity, HandsFreeService::class.java).putStringArrayListExtra(
                HandsFreeService.GLASSES, ArrayList(hardware.deviceDetails.values.map { it.name })))
        }.onFailure { notify(activity.getString(R.string.handsfree_error)) }
    }
    val handsFreePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startHandsFree() }
    fun toggleHandsFree() {
        if (HandsFreeService.state.value.enabled) {
            HandsFreeService.disable(activity)
            return
        }
        if (!canArmVoice || voiceCommandPending) return
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) startHandsFree() else handsFreePermissions.launch(permissions.toTypedArray())
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
                if (!activity.requestMicrophone()) notify(activity.getString(R.string.permite_el_microfono_para_usar))
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
        }.onFailure { dictating = false; notify(activity.getString(R.string.android_no_ofrece_un_servicio)) }
    }
    fun runAudioTest(usePhone: Boolean) {
        if (audioTesting) return
        if (!needsFreeMicrophone()) return
        if (capture.isRecording || live.active || conversationState.active || listening || speaking) {
            notify(activity.getString(R.string.deten_la_grabacion_o_emision))
            return
        }
        audioTesting = true
        audioTest = scope.launch {
            val audio = LiveAudio(activity, 16_000)
            audioResult = null
            try {
                check(activity.requestMicrophone()) { activity.getString(R.string.permite_el_microfono_para_realizar) }
                check(usePhone || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    activity.getString(R.string.vincula_las_gafas_o_concede)
                }
                audio.open(hardware.deviceDetails.values.map { it.name }, usePhone)
                audioResult = activity.getString(R.string.habla_ahora_despues_sonara_un)
                val result = audio.testSignal()
                val signal = activity.getString(if (result.hasSignal) R.string.se_detecto_senal_del_microfono else R.string.no_se_detecto_voz_suficiente)
                val input = if (usePhone) activity.getString(R.string.microfono) else result.input.productName.toString()
                val output = if (usePhone) activity.getString(R.string.route_phone) else result.output.productName.toString()
                audioResult = activity.getString(R.string.audio_test_result, signal, input, output)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) { audioResult = error.message ?: activity.getString(R.string.no_se_pudo_probar_el) }
            finally {
                withContext(NonCancellable) {
                    try { withContext(Dispatchers.IO) { audio.close() } }
                    finally { audioTesting = false }
                }
            }
        }
    }
    val diagnosisBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && page == "diagnostics") runAudioTest(false)
        else if (!it) notify(activity.getString(R.string.vincula_las_gafas_o_concede))
    }
    fun runApiTest() {
        if (apiTesting) return
        apiTesting = true
        apiTest = scope.launch {
            apiResult = null
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val socket = LiveSocket(activity.applicationContext, assistant.liveKey(liveModel.provider), liveModel, null, language)
                try {
                    check(withTimeoutOrNull(12_000) { socket.ready.await(); true } == true) { activity.getString(R.string.el_modelo_no_respondio_a) }
                    apiResult = activity.getString(R.string.sesion_aceptada_en_ms, android.os.SystemClock.elapsedRealtime() - started)
                } finally { socket.close() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { apiResult = error.message ?: activity.getString(R.string.no_se_pudo_conectar) }
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
            if (loaded == null) notify(activity.getString(R.string.no_se_pudo_abrir_la))
            else if (destination == "notes") {
                noteAttachment = withContext(Dispatchers.Default) { try { jpegBase64(loaded) } finally { loaded.recycle() } }
            } else if (destination == "vision-translate") {
                image = loaded
            } else {
                image = loaded
                if (draft.isBlank()) draft = describeImage
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
                val format = if (record) recordFormat else streamFormat
                val orientation = if (format.orientation == VideoOrientation.ORIGINAL) streamFormat.orientation else format.orientation
                val framing = if (format.orientation == VideoOrientation.ORIGINAL) VideoFraming.ORIGINAL else format.framing
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                activity.startForegroundService(Intent(activity, BroadcastService::class.java).putExtra("consent", token)
                    .putExtra("endpoint", endpoint).putExtra("record", record).putExtra("audio", audio)
                    .putExtra("landscape", orientation == VideoOrientation.HORIZONTAL).putExtra("framing", framing.name)
                    .putExtra("capturedAspect", bounds.width().toFloat() / bounds.height()).putExtra("videoAspect", capture.videoAspect)
                    .putExtra("fps", if (record) prefs.fps else prefs.streamFps).putExtra("route", if (record) "Automática" else prefs.streamAudio))
                go("cast")
                fullscreen = true
            }.onFailure { notify(activity.getString(R.string.no_se_pudo_iniciar_el)) }
        }
    }
    fun startStream() {
        if (audio && !needsFreeMicrophone()) return
        runCatching {
            val endpoint = prefs.streamAddress(prefs.platform)
            val url = URI(endpoint)
            require(url.scheme in listOf("rtmp", "rtmps") && !url.host.isNullOrBlank() && prefs.secret(prefs.platform))
            scope.launch {
                if (audio && !activity.requestMicrophone()) { notify(activity.getString(R.string.permite_el_microfono_o_desactiva_2)); return@launch }
                outgoingUrl = endpoint
                val manager = activity.getSystemService(MediaProjectionManager::class.java)
                runCatching { screenShare.launch(manager.createScreenCaptureIntent()) }.onFailure {
                    outgoingUrl = null
                    notify(activity.getString(R.string.no_se_pudo_solicitar_el))
                }
            }
        }.onFailure { notify(activity.getString(R.string.guarda_una_direccion_rtmp_o)) }
    }
    fun startInteractiveRecording() {
        if (audio && !needsFreeMicrophone()) return
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        scope.launch {
            if (audio && !activity.requestMicrophone()) { notify(activity.getString(R.string.permite_el_microfono_o_desactiva)); return@launch }
            pendingRecording = true
            runCatching { screenShare.launch(manager.createScreenCaptureIntent()) }.onFailure {
                pendingRecording = false
                notify(activity.getString(R.string.no_se_pudo_solicitar_el))
            }
        }
    }
    fun readLocation() {
        val manager = activity.getSystemService(android.location.LocationManager::class.java)
        runCatching {
            val providers = manager.getProviders(true)
            val fix = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            fun update(value: android.location.Location?) {
                if (value == null) notify(activity.getString(R.string.no_se_encontro_la_ubicacion))
                else location = String.format(Locale.US, "%.5f,%.5f", value.latitude, value.longitude)
            }
            if (fix != null && System.currentTimeMillis() - fix.time < 120_000) update(fix)
            else {
                val source = providers.firstOrNull { it == android.location.LocationManager.FUSED_PROVIDER }
                    ?: providers.firstOrNull { it == android.location.LocationManager.NETWORK_PROVIDER }
                if (source == null) update(fix)
                else manager.getCurrentLocation(source, null, activity.mainExecutor) { update(it ?: fix) }
            }
        }.onFailure { notify(activity.getString(R.string.no_se_pudo_leer_la)) }
    }
    val gps = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) readLocation() else notify(activity.getString(R.string.permiso_de_ubicacion_denegado)) }
    LaunchedEffect(hardware.recentError?.id) { hardware.recentError?.let { snack.showSnackbar(it.message); glasses.clearRecentError(it.id) } }
    LaunchedEffect(ai.notice) { ai.notice?.let { snack.showSnackbar(it) } }
    LaunchedEffect(capture.hasStream) { if (!capture.hasStream) renderedPreview = null }
    LaunchedEffect(live.message, live.active) { if (!live.active && live.message.isNotBlank()) snack.showSnackbar(live.message) }
    BackHandler(page.isNotEmpty(), onBack = ::finishPage)

    val homeSwipe = tab == 0 && page.isEmpty() && !capture.isRecording && !live.active
    val closeSwipe = page == "ai" && sideChat
    val swipeModifier = if (homeSwipe || closeSwipe) Modifier.pointerInput(homeSwipe, closeSwipe) {
        var traveled = 0f
        detectHorizontalDragGestures(onDragStart = { traveled = 0f }, onDragEnd = {
            val threshold = 68.dp.toPx()
            if (homeSwipe && traveled > threshold) slideOpenChat()
            if (closeSwipe && traveled < -threshold) finishPage()
        }) { change, distance ->
            change.consume()
            traveled += distance
        }
    } else Modifier
    Scaffold(
        modifier = swipeModifier,
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (!fullscreen) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (page.isNotEmpty()) IconButton(onClick = ::finishPage) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.volver)) }
                else Image(androidx.compose.ui.res.painterResource(R.drawable.metalima_brand), null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (homeSwipe) IconButton(onClick = ::slideOpenChat) { Icon(Icons.Outlined.Forum, stringResource(R.string.open_assistant_chat), tint = Lime) }
                IconButton(onClick = { help = true }) { Icon(Icons.Outlined.HelpOutline, stringResource(R.string.ayuda), tint = Muted) }
            }
        },
        bottomBar = {
            if (page.isEmpty()) NavigationBar(containerColor = SurfaceTone) {
                listOf(stringResource(R.string.inicio) to Icons.Outlined.Home, stringResource(R.string.historial) to Icons.Outlined.History,
                    stringResource(R.string.laboratorio) to Icons.Outlined.Science, stringResource(R.string.ajustes) to Icons.Outlined.Settings,
                    stringResource(R.string.donaciones) to Icons.Outlined.FavoriteBorder).forEachIndexed { index, item ->
                    NavigationBarItem(selected = index == tab, onClick = { tab = index }, icon = { Icon(item.second, null) },
                        label = { Text(item.first, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Lime,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary, selectedTextColor = Lime))
                }
            }
        }
    ) { padding ->
        if (fullscreen && page == "cast") {
            Box(Modifier.fillMaxSize().background(Ink)) {
                Viewfinder(capture.isStreaming, capture.hasStream, surface = camera::setSurface,
                    modifier = Modifier.fillMaxSize(), hud = quickOverlay, reaction = reaction, filter = filter, fullBleed = true)
                if (!live.active) IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) { Icon(Icons.Outlined.Close, stringResource(R.string.salir_de_pantalla_completa)) }
                else TextButton(onClick = ::finishPage,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text(if (live.recording) stringResource(R.string.detener_grabacion) else stringResource(R.string.detener_directo)) }
            }
        } else if (isLivePage) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                val canSee = liveModel.supportsVisionTools && page == "conversation-live"
                LiveConversationScreen(conversationState, liveModel, liveOnPhone,
                    liveModel.provider in ai.configuredProviders,
                    capture.isRecording || live.active || finishVoice != null || speaking || ai.busy || audioTesting,
                    select = { liveModel = it; prefs.liveModel = it.id },
                    choosePhone = { liveOnPhone = it },
                    configure = { configureAssistant(liveModel.provider) },
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
                        else if (capture.needsGlassesAppUpdate) notify(activity.getString(R.string.revisa_conexiones_de_aplicaciones_en))
                        else if (!hardware.hasActiveDevice) notify(activity.getString(R.string.conecta_las_gafas_en_meta))
                        else {
                            liveVision = true
                            if (!capture.hasSession) camera.startSession()
                        }
                    }) else null,
                    takeFrame = if (canSee && liveVision && capture.isStreaming && conversationState.phase == LivePhase.LISTENING) ({
                        scope.launch {
                            try {
                                val bitmap = withTimeoutOrNull(6000) { camera.takeSnapshot() } ?: error(activity.getString(R.string.la_foto_no_llego_a))
                                val jpeg = withContext(Dispatchers.Default) { try { jpegBase64(bitmap, 1024) } finally { bitmap.recycle() } }
                                check(conversation.sendFrame(jpeg)) { activity.getString(R.string.gemini_live_aun_no_esta) }
                                notify(activity.getString(R.string.imagen_enviada_haz_tu_pregunta))
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { notify(error.message ?: activity.getString(R.string.no_se_pudo_enviar_la)) }
                        }
                    }) else null,
                    visionBusy = capture.isBusy || capture.isCapturingPhoto,
                    visualPreview = if (liveVision) ({
                        Viewfinder(capture.isStreaming, capture.hasStream, camera::setSurface,
                            Modifier.fillMaxWidth().height(112.dp), false, "", "Natural")
                    }) else null)
            }
        } else key(page, tab) {
            Column(Modifier.fillMaxSize().padding(padding)
                .graphicsLayer { if (page == "ai" && sideChat) translationX = (chatSlide.value - 1f) * size.width }
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    "assistant-settings" -> {
                        Heading(stringResource(R.string.inteligencia_artificial))
                        AssistantSettings(activity, assistant, ::notify, providerToConfigure)
                    }
                    "diagnostics" -> DiagnosticsScreen(activity, hardware, capture, conversationState, liveModel,
                        audioResult, audioTesting, apiResult, apiTesting,
                        { usePhone ->
                            if (usePhone || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                runAudioTest(usePhone)
                            } else diagnosisBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }, ::runApiTest, { go("camera") }, { glasses.openGlassesAppUpdate(activity) })
                    "vision-translate" -> {
                        Heading(stringResource(R.string.traducir_una_imagen), stringResource(R.string.elige_una_foto_de_la))
                        Choice(stringResource(R.string.traducir_a), imageLanguage, translationLanguages, display = { translationLabel(it) }) { imageLanguage = it }
                        OutlinedButton(onClick = { photoPicker.launch("image/*") }) { Text(stringResource(R.string.elegir_foto)) }
                        image?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), stringResource(R.string.imagen_para_traducir), Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Fit)
                            Button(enabled = ai.hasKey && !ai.busy, onClick = {
                                val request = "Lee el texto de la imagen y tradúcelo a $imageLanguage. Indica qué partes no se pueden leer."
                                go("ai")
                                val translatedVoice = AppLanguage.entries.firstOrNull {
                                    it.promptName.startsWith(imageLanguage.lowercase(Locale.ROOT))
                                }
                                submit(request, bitmap, speak = responseSpoken && translatedVoice != null, contextual = false,
                                    voiceLanguage = translatedVoice ?: language, replyLanguage = imageLanguage)
                                image = null
                            }) { Text(stringResource(R.string.traducir)) }
                        }
                        if (!ai.hasKey) Text(stringResource(R.string.configura_un_proveedor_de_ia), color = Muted)
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
                                notify(activity.getString(R.string.nota_guardada))
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { notify(error.message ?: activity.getString(R.string.no_se_pudo_guardar_la_2)) }
                            finally { savingNote = false }
                        }
                    }
                    "ai" -> {
                        Heading(stringResource(R.string.conversacion), stringResource(R.string.pregunta_por_voz_por_escrito))
                        AssistantChoice(ai.provider, ai, enabled = !ai.busy && !dictating && finishVoice == null && !speaking, onSelect = ::openAssistant)
                        if (ai.provider == Provider.MUSE) {
                            Block {
                                Text(stringResource(R.string.muse_api_summary), color = Muted)
                                OutlinedButton(onClick = { openMuseWebsite(activity, ::notify) }) { Text(stringResource(R.string.muse_open_web)) }
                            }
                        }
                        ActionTile(Icons.Outlined.Headphones, stringResource(R.string.conversacion_en_directo), liveModel.displayLabel()) { go("conversation-live") }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            AssistChip(onClick = { go("gps") }, label = { Text("GPS") }, leadingIcon = { Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = { go("vision") }, label = { Text(stringResource(R.string.vision)) }, leadingIcon = { Icon(Icons.Outlined.Visibility, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = {
                                val query = draft.ifBlank { ai.turns.lastOrNull()?.user.orEmpty() }
                                if (query.isBlank()) notify(activity.getString(R.string.escribe_una_consulta_para_buscar))
                                else runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))) }
                                    .onFailure { notify(activity.getString(R.string.no_se_pudo_abrir_el)) }
                            }, label = { Text("Web") }, leadingIcon = { Icon(Icons.Outlined.Language, null, Modifier.size(16.dp)) })
                        }
                        if (!ai.hasKey) ActionTile(Icons.Outlined.Key, stringResource(R.string.configurar, ai.provider.label),
                            stringResource(R.string.anade_una_clave_para_empezar)) { configureAssistant(ai.provider) }
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
                                    listening -> stringResource(R.string.enviar_audio)
                                    recording != null && recording.isCompleted -> stringResource(R.string.procesando_audio)
                                    recording != null -> stringResource(R.string.conectando_microfono)
                                    ai.busy -> stringResource(R.string.consultando_gemini)
                                    speaking -> stringResource(R.string.reproduciendo_respuesta)
                                    else -> stringResource(R.string.hablar_con_las_gafas)
                                })
                            }
                            Text(if (listening) stringResource(R.string.escuchando_haz_tu_pregunta) else stringResource(R.string.pulsa_y_habla_cuando_el), color = Muted)
                            if (recording != null) TextButton(onClick = { voiceJob?.cancel() }) { Text(stringResource(R.string.cancelar_audio)) }
                            lastVoiceAnswer?.takeIf { it == ai.turns.lastOrNull { turn -> turn.session == ai.session }?.answer }?.let { answer ->
                                TextButton(enabled = !speaking && recording == null && !ai.busy, onClick = {
                                    runCatching { glassesVoice.selectHeadset(hardware.deviceDetails.values.map { it.name }) }
                                        .onSuccess { speakOnGlasses(answer, it) }
                                        .onFailure { notify(it.message ?: activity.getString(R.string.no_se_encontro_la_salida)) }
                                }) { Text(stringResource(R.string.repetir_respuesta_en_las_gafas)) }
                            }
                        }
                        val canType = finishVoice == null && !speaking
                        TextButton(enabled = !ai.busy && canType, onClick = { assistant.newSession(); draft = ""; image = null }) { Text(stringResource(R.string.nueva_conversacion)) }
                        ai.turns.filter { it.session == ai.session }.takeLast(6).forEach { Msg(it) }
                        image?.let { attached ->
                            Image(attached.asImageBitmap(), stringResource(R.string.imagen_adjunta), Modifier.fillMaxWidth().height(160.dp).clip(Panel), contentScale = ContentScale.Fit)
                            TextButton(enabled = canType, onClick = { image = null }) { Text(stringResource(R.string.quitar_foto)) }
                        }
                        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.escribe_tu_pregunta)) }, maxLines = 5)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(enabled = canType, onClick = { photoPicker.launch("image/*") }) { Icon(Icons.Outlined.AttachFile, stringResource(R.string.adjuntar_imagen)) }
                            IconButton(enabled = canType && !ai.busy && !handsFree.ownsMicrophone, onClick = { dictating = true; runCatching { dictation.launch(dictationRequest(language)) }
                                .onFailure { dictating = false; notify(activity.getString(R.string.android_no_dispone_de_un)) } }) { Icon(Icons.Outlined.Mic, activity.getString(R.string.dictar_pregunta)) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { submit(draft, image); draft = ""; image = null }, enabled = canType && ai.hasKey && !ai.busy && draft.isNotBlank()) { Text(if (ai.busy) stringResource(R.string.enviando) else stringResource(R.string.enviar)) }
                        }
                        SwitchRow(stringResource(R.string.leer_respuestas_en_voz_alta), responseSpoken) { responseSpoken = it; prefs.speak = it }
                    }
                    "camera", "vision", "cast", "photo", "creator" -> {
                        val mode = page
                        Heading(when (mode) { "creator" -> stringResource(R.string.modo_creador); "vision" -> stringResource(R.string.vision_con_ia); "cast" -> stringResource(R.string.compartir_camara); "photo" -> stringResource(R.string.analisis_de_fotos); else -> stringResource(R.string.camara_y_grabacion) },
                            when (mode) { "creator" -> stringResource(R.string.usa_la_guia_y_marca); "vision" -> stringResource(R.string.analiza_un_fotograma_cuando_tu); "cast" -> stringResource(R.string.abre_el_visor_a_pantalla); "photo" -> stringResource(R.string.haz_una_foto_desde_las); else -> stringResource(R.string.captura_y_guarda_fotos_o) })
                        if (capture.needsGlassesAppUpdate) ActionTile(Icons.Outlined.Link, stringResource(R.string.revisar_conexion_en_meta_ai),
                            stringResource(R.string.meta_solicita_revisar_conexiones_de)) {
                            glasses.openGlassesAppUpdate(activity)
                        }
                        if (mode in listOf("photo", "vision")) OutlinedButton(onClick = { go("ai"); photoPicker.launch("image/*") }) { Text(stringResource(R.string.elegir_foto_de_la_galeria)) }
                        if (hardware.isFirmwareUpdateRequired) ActionTile(Icons.Outlined.SystemUpdate, stringResource(R.string.actualizar_gafas), stringResource(R.string.meta_ai_abrira_la_actualizacion)) { glasses.openFirmwareUpdate(activity) }
                        if (mode == "creator" && prefs.quality == "FLUIDA") {
                            Text(stringResource(R.string.para_fijar_la_resolucion_del), color = Muted)
                        }
                        if (!hardware.isRegistered) ActionTile(Icons.Outlined.Bluetooth, stringResource(R.string.vincular_mis_gafas), stringResource(R.string.confirma_el_acceso_en_meta), onClick = activity::connectGlasses)
                        Viewfinder(capture.isStreaming, capture.hasStream, camera::setSurface, Modifier.fillMaxWidth().aspectRatio(if (prefs.landscape) 16 / 9f else 4 / 5f),
                            quickOverlay, reaction, filter, onFrame = { renderedPreview = it }, creatorGuide = mode == "creator")
                        capture.adaptiveLabel?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelMedium) }
                        if (hardware.isRegistered) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = !capture.isBusy && (capture.hasSession || hardware.hasActiveDevice), onClick = {
                                    if (capture.hasSession) camera.endSession() else camera.startSession()
                                }) { Text(if (capture.hasSession) stringResource(R.string.cerrar_sesion) else if (capture.needsGlassesAppUpdate) stringResource(R.string.reintentar_sesion) else stringResource(R.string.iniciar_sesion)) }
                                Button(enabled = !capture.isBusy && capture.isSessionActive, onClick = {
                                    if (capture.hasStream) camera.stopStreaming() else camera.startStreaming()
                                }) { Text(if (capture.hasStream) stringResource(R.string.cerrar_camara) else stringResource(R.string.abrir_camara)) }
                            }
                        }
                        if (capture.hasStream) {
                            if (mode == "vision") {
                                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.que_quieres_saber_de_la)) })
                                Button(enabled = capture.isStreaming && renderedPreview != null && !ai.busy && ai.hasKey, onClick = {
                                    val frame = renderedPreview?.bitmap
                                    if (frame == null) notify(activity.getString(R.string.espera_a_que_aparezca_el))
                                    else submit(draft.ifBlank { describeImage }, frame, contextual = false)
                                }) { Text(if (ai.busy) stringResource(R.string.analizando) else stringResource(R.string.analizar_fotograma)) }
                                ai.turns.lastOrNull()?.let { Msg(it) }
                            } else if (mode == "cast") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { fullscreen = true }) { Text(stringResource(R.string.pantalla_completa)) }
                                    if (!live.active) OutlinedButton(onClick = ::startInteractiveRecording) { Text(stringResource(R.string.grabar_visor)) }
                                    if (live.active) OutlinedButton(onClick = ::stopBroadcast) { Text(if (live.recording) stringResource(R.string.detener_grabacion) else stringResource(R.string.detener_directo)) }
                                }
                                SwitchRow(stringResource(R.string.microfono_del_movil_al_grabar), audio, !live.active) { audio = it }
                                SwitchRow(stringResource(R.string.mostrar_hud), quickOverlay) { quickOverlay = it }
                                Choice(stringResource(R.string.filtro_del_visor), filter, viewfinderFilters.keys.toList(),
                                    display = { stringResource(viewfinderFilters.getValue(it)) }) { filter = it }
                                Choice(stringResource(R.string.reaccion_visible), reaction, viewfinderReactions.keys.toList(),
                                    display = { stringResource(viewfinderReactions.getValue(it)) }) { reaction = it }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = capture.isStreaming && !capture.isCapturingPhoto, onClick = camera::capturePhoto) { Text(stringResource(R.string.hacer_foto)) }
                                    Button(enabled = capture.isStreaming || capture.isRecording, onClick = {
                                        if (capture.isRecording || !capture.includeAudioInStream || needsFreeMicrophone()) camera.toggleRecording(activity::requestMicrophone)
                                    }) { Text(if (capture.isRecording) stringResource(R.string.detener_grabacion) else stringResource(R.string.grabar_video)) }
                                }
                                if (capture.isRecording) Text(stringResource(R.string.grabando_s, capture.recordingElapsedSeconds), color = Lime)
                                SwitchRow(stringResource(R.string.microfono_de_las_gafas), capture.includeAudioInStream, !capture.isRecording && capture.isStreaming) { camera.toggleMic() }
                                if (mode == "creator") {
                                    if (capture.isRecording && !capture.includeAudioInStream) {
                                        OutlinedButton(onClick = { armVoiceCommand(mark = true) }, enabled = !voiceCommandPending) {
                                            Text(if (creatorVoice) stringResource(R.string.cancelar_escucha) else stringResource(R.string.marcar_con_la_voz))
                                        }
                                        Text(if (creatorVoice) stringResource(R.string.di, language.markCommand) else stringResource(R.string.puedes_decir_tras_pulsar, language.markCommand), color = Muted)
                                    }
                                    if (capture.isRecording) OutlinedButton(onClick = {
                                        if (camera.markRecording()) notify(activity.getString(R.string.momento_marcado))
                                        else notify(activity.getString(R.string.espera_al_primer_fotograma_o))
                                    }) { Text(stringResource(R.string.marcar_momento, capture.recordingMarks.size)) }
                                }
                            }
                        }
                    }
                    "modes" -> {
                        Heading(stringResource(R.string.modo_de_grabacion), stringResource(R.string.elige_que_quieres_guardar_en))
                        Block {
                            VideoFormatControls(recordFormat, false, ::saveRecordFormat,
                                voice = if (canArmVoice) ({ armVideoFormat(VideoTarget.RECORDING) }) else null,
                                listening = videoVoiceTarget == VideoTarget.RECORDING)
                        }
                        ActionTile(Icons.Outlined.Speed, stringResource(R.string.rendimiento), stringResource(R.string.video_directo_de_la_camara)) { go("camera") }
                        ActionTile(Icons.Outlined.AutoAwesome, stringResource(R.string.interactivo), stringResource(R.string.graba_el_visor_con_hud)) { go("cast") }
                    }
                    "live" -> {
                        Heading(stringResource(R.string.emitir_en_directo), stringResource(R.string.emite_el_visor_de_las))
                        Block {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlatformIcon(prefs.platform)
                                Text(platformLabel(prefs.platform), style = MaterialTheme.typography.titleLarge)
                            }
                            Text(if (prefs.secret(prefs.platform)) stringResource(R.string.clave_de_emision_guardada) else stringResource(R.string.falta_configurar_la_clave_de), color = Muted)
                            if (!live.active) VideoFormatControls(streamFormat, true, ::saveStreamFormat,
                                voice = if (canArmVoice) ({ armVideoFormat(VideoTarget.STREAM) }) else null,
                                listening = videoVoiceTarget == VideoTarget.STREAM)
                            SwitchRow(stringResource(R.string.incluir_microfono_del_movil), audio, !live.active) { audio = it }
                            if (prefs.platform in listOf("TikTok", "Instagram Live"))
                                Text(stringResource(R.string.el_directo_requiere_acceso_a), color = Muted)
                            if (live.message.isNotBlank()) Text(live.message, color = Lime)
                            Button(onClick = { if (live.active) stopBroadcast() else startStream() },
                                enabled = live.active || capture.isStreaming) { Text(if (live.active) stringResource(R.string.detener_directo) else stringResource(R.string.iniciar_directo)) }
                        }
                        if (!capture.isStreaming) ActionTile(Icons.Outlined.Videocam, stringResource(R.string.abrir_camara_de_las_gafas), stringResource(R.string.inicia_una_sesion_y_abre)) { go("camera") }
                        ActionTile(Icons.Outlined.Settings, stringResource(R.string.configurar_destino), stringResource(R.string.plataforma_url_y_clave_de)) { page = ""; tab = 3 }
                    }
                    "gps" -> {
                        Heading(stringResource(R.string.ubicacion_y_rutas), stringResource(R.string.consulta_la_ubicacion_del_movil))
                        Button(onClick = { if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) readLocation() else gps.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text(stringResource(R.string.consultar_ubicacion)) }
                        location?.let { Text(it, color = Lime) }
                        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lugar_o_destino)) })
                        Button(enabled = draft.isNotBlank(), onClick = {
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(draft)}"))) }
                                .onFailure { notify(activity.getString(R.string.no_se_pudo_abrir_maps)) }
                        }) { Text(stringResource(R.string.abrir_ruta_en_maps)) }
                        OutlinedButton(enabled = draft.isNotBlank(), onClick = {
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(draft)}"))) }
                                .onFailure { notify(activity.getString(R.string.no_se_pudo_abrir_maps)) }
                        }) { Text(stringResource(R.string.buscar_lugares)) }
                    }
                    else -> when (tab) {
                        0 -> {
                            GlassesDashboard(hardware, capture, prefs, diagnose = { go("diagnostics") }, connect = activity::connectGlasses)
                            AssistantHub(ai, openChat = { openAssistant(Provider.OPENAI) },
                                openGemini = {
                                    if (liveModel.provider != Provider.GEMINI) {
                                        liveModel = LiveModel.GEMINI_38
                                        prefs.liveModel = liveModel.id
                                    }
                                    go("conversation-live")
                                }, openMuse = { openAssistant(Provider.MUSE) })
                            LiveLaunchCard(liveModel.displayLabel()) { go("conversation-live") }
                            Section(stringResource(R.string.asistente))
                            Block {
                                SwitchRow(stringResource(R.string.handsfree_title), handsFree.enabled,
                                    enabled = handsFree.enabled || (canArmVoice && !voiceCommandPending)) { toggleHandsFree() }
                                Text(stringResource(handsFree.message ?: R.string.handsfree_summary), color = Muted)
                                handsFree.heard?.let { Text(stringResource(R.string.handsfree_last_heard, it), color = Muted) }
                                if (ai.provider == Provider.GEMINI) {
                                    SwitchRow(stringResource(R.string.handsfree_gemini_audio), handsFreeGeminiAudio) {
                                        prefs.handsFreeGeminiAudio = it
                                        handsFreeGeminiAudio = it
                                    }
                                    Text(stringResource(R.string.handsfree_gemini_audio_summary), color = Muted)
                                }
                            }
                            OutlinedButton(onClick = { armVoiceCommand(mark = false) }, enabled = canArmVoice && !voiceCommandPending) {
                                Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (wake) stringResource(R.string.cancelar_escucha) else stringResource(R.string.activar_frase_de_voz))
                            }
                            if (wake) Text(stringResource(R.string.di_y_tu_pregunta, wakePhrase), color = Muted)
                            ActionTile(Icons.Outlined.Forum, stringResource(R.string.conversacion), ai.provider.label + stringResource(R.string.texto_y_dictado)) { go("ai") }
                            ActionTile(Icons.Outlined.Visibility, stringResource(R.string.vision_con_ia), stringResource(R.string.pregunta_sobre_lo_que_ves)) { go("vision") }
                            ActionTile(Icons.Outlined.Translate, stringResource(R.string.traduccion_por_voz), stringResource(R.string.traduce_una_conversacion_entre_dos)) { go("translation") }
                            ActionTile(Icons.Outlined.ImageSearch, stringResource(R.string.traducir_una_imagen), stringResource(R.string.lee_y_traduce_carteles_cartas)) { image = null; go("vision-translate") }
                            ActionTile(Icons.Outlined.EditNote, stringResource(R.string.notas), stringResource(R.string.ideas_dictadas_texto_y_fotos)) { go("notes") }
                            Section(stringResource(R.string.captura_y_directo))
                            ActionTile(Icons.Outlined.Movie, stringResource(R.string.modo_creador), stringResource(R.string.guia_vertical_marcas_y_exportacion)) { go("creator") }
                            ActionTile(Icons.Outlined.Videocam, stringResource(R.string.grabar_video), stringResource(R.string.elige_rendimiento_o_interactivo)) { go("modes") }
                            ActionTile(Icons.Outlined.CellTower, stringResource(R.string.emitir_en_directo), platformLabel(prefs.platform)) { go("live") }
                            ActionTile(Icons.Outlined.Cast, stringResource(R.string.compartir_camara), stringResource(R.string.visor_de_las_gafas_a)) { go("cast") }
                            ActionTile(Icons.Outlined.PhotoCamera, stringResource(R.string.analisis_de_fotos), stringResource(R.string.haz_una_foto_con_las)) { go("photo") }
                        }
                        1 -> History(ai.turns, assistant::clearHistory, assistant::deleteSessions, { assistant.selectSession(it); draft = ""; image = null; go("ai") }, ::notify)
                        2 -> {
                            Heading(stringResource(R.string.laboratorio))
                            Block { UpdateControls(updates, updateAction) }
                            ActionTile(Icons.Outlined.LocationOn, stringResource(R.string.ubicacion_y_rutas), stringResource(R.string.localiza_el_movil_y_abre)) { go("gps") }
                            ActionTile(Icons.Outlined.Cast, stringResource(R.string.hud_y_reacciones), stringResource(R.string.superposiciones_para_el_visor_compartido)) { go("cast") }
                        }
                        3 -> StudioSettings(activity, glasses, assistant, prefs, responseSpoken, { responseSpoken = it; prefs.speak = it },
                            language, { language = it; prefs.language = it }, wakePhrase,
                            { wakePhrase = it; prefs.setWakePhrase(language, it) }, ::notify,
                            skin, { skin = it; prefs.skin = it }, streamFormat, ::saveStreamFormat)
                        4 -> Donations(activity, ::notify)
                    }
                }
            }
        }
    }

    live.saved?.let { uri ->
        AlertDialog(onDismissRequest = BroadcastService::dismissSaved, title = { Text(stringResource(R.string.grabacion_guardada)) },
            text = { Text(stringResource(R.string.el_video_esta_disponible_en)) },
            confirmButton = { TextButton(onClick = { shareUri(activity, uri, "video/mp4"); BroadcastService.dismissSaved() }) { Text(stringResource(R.string.compartir)) } },
            dismissButton = { TextButton(onClick = BroadcastService::dismissSaved) { Text(stringResource(R.string.cerrar)) } })
    }
    UpdatePrompt(updates) {
        tab = 2
        page = ""
        updateAction()
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.ayuda_y_conexion)) }, text = {
        Text(stringResource(R.string.vincula_primero_las_gafas_en))
    }, confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.entendido)) } })
    if (capture.showCameraPermissionRedirectConfirm) AlertDialog(onDismissRequest = camera::cancelCameraPermissionRedirect,
        title = { Text(stringResource(R.string.permitir_camara_de_las_gafas)) }, text = { Text(stringResource(R.string.meta_ai_te_pedira_que)) },
        confirmButton = { TextButton(onClick = { camera.confirmCameraPermissionRedirect(activity::requestWearableCamera) }) { Text(stringResource(R.string.continuar)) } },
        dismissButton = { TextButton(onClick = camera::cancelCameraPermissionRedirect) { Text(stringResource(R.string.cancelar)) } })
    capture.activePreview?.let { file ->
        var writing by remember(file) { mutableStateOf(false) }
        var saved by remember(file) { mutableStateOf<Uri?>(null) }
        AlertDialog(onDismissRequest = { if (!writing) camera.dismissCapturePreview() }, title = { Text(if (file is CapturePreview.Photo) stringResource(R.string.foto_capturada) else stringResource(R.string.grabacion_terminada)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                when (file) {
                    is CapturePreview.Photo -> Image(file.bitmap.asImageBitmap(), stringResource(R.string.foto_de_las_gafas), Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    is CapturePreview.Video -> AndroidView(factory = { context -> VideoView(context).apply {
                        setVideoURI(file.uri); setMediaController(android.widget.MediaController(context).also { it.setAnchorView(this) }); seekTo(1)
                    } }, modifier = Modifier.fillMaxWidth().height(220.dp))
                }
                if (file is CapturePreview.Video && saved == null && !writing) VideoFormatControls(recordFormat, false, ::saveRecordFormat)
                if (file is CapturePreview.Video && writing) Text(stringResource(R.string.video_exporting), color = Muted)
                if (file is CapturePreview.Video && file.marks.isNotEmpty()) {
                    Text(stringResource(R.string.momentos_marcados), style = MaterialTheme.typography.titleMedium)
                    file.marks.forEachIndexed { index, time ->
                        OutlinedButton(enabled = !writing, onClick = {
                            scope.launch {
                                writing = true
                                try {
                                    withContext(Dispatchers.IO) { exportMarkedClip(activity, file.uri, time, recordFormat) }
                                    notify(activity.getString(R.string.fragmento_guardado_en_la_galeria, index + 1))
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { notify(error.message ?: activity.getString(R.string.no_se_pudo_exportar_el_2)) }
                                finally { writing = false }
                            }
                        }) { Text(stringResource(R.string.exportar_marca_s, index + 1, time / 1000)) }
                    }
                }
                if (saved != null) Text(stringResource(R.string.guardado_en_la_galeria), color = Lime)
            }
        }, confirmButton = {
            TextButton(enabled = !writing, onClick = {
                val destination = saved
                if (destination != null) shareUri(activity, destination, if (file is CapturePreview.Photo) "image/jpeg" else "video/mp4")
                else scope.launch {
                    writing = true
                    try {
                        saved = when (file) {
                            is CapturePreview.Video -> saveRecording(activity, file.uri, recordFormat)
                            is CapturePreview.Photo -> withContext(Dispatchers.IO) { storeCapture(activity, file.bitmap, null) }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { notify(activity.getString(R.string.no_se_pudo_guardar_la)) }
                    finally { writing = false }
                }
            }) { Text(if (saved == null) stringResource(R.string.guardar) else stringResource(R.string.compartir)) }
        }, dismissButton = {
            Row {
                if (file is CapturePreview.Photo) TextButton(enabled = !writing, onClick = {
                    image = file.bitmap
                    if (draft.isBlank()) draft = describeImage
                    camera.dismissCapturePreview()
                    go("ai")
                }) { Text(stringResource(R.string.analizar)) }
                TextButton(enabled = !writing, onClick = camera::dismissCapturePreview) { Text(stringResource(R.string.cerrar)) }
            }
        })
    }
    }
}

private val viewfinderFilters = linkedMapOf(
    "Natural" to R.string.filter_natural, "Blanco y negro" to R.string.filter_mono,
    "Cálido" to R.string.filter_warm, "Frío" to R.string.filter_cool
)
private val viewfinderReactions = linkedMapOf(
    "" to R.string.reaction_none, "¡Bravo!" to R.string.reaction_bravo,
    "En directo" to R.string.reaction_live, "¡Gol!" to R.string.reaction_goal
)

@Composable private fun Viewfinder(streaming: Boolean, hasStream: Boolean, surface: (Surface?) -> Unit, modifier: Modifier, hud: Boolean, reaction: String, filter: String,
    onFrame: (TextureView?) -> Unit = {}, creatorGuide: Boolean = false, fullBleed: Boolean = false) {
    val latestFrame by rememberUpdatedState(onFrame)
    val latestSurface by rememberUpdatedState(surface)
    var received by remember { mutableStateOf(false) }
    LaunchedEffect(hasStream) { if (!hasStream) received = false }
    val frame = if (fullBleed) modifier else modifier.clip(Panel).border(1.dp, Line, Panel)
    Box(frame.background(Color(0xFF07090B)), contentAlignment = Alignment.Center) {
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
            Text(if (streaming) stringResource(R.string.esperando_imagen) else stringResource(R.string.camara_cerrada), color = Muted)
        }
        if (hud && received) {
            Box(Modifier.matchParentSize().padding(16.dp).border(1.dp, Lime.copy(alpha = .55f), RoundedCornerShape(12.dp)))
            Text("●  MetaLima Ai", color = Lime, modifier = Modifier.align(Alignment.TopStart).padding(26.dp))
        }
        if (creatorGuide && received) Box(Modifier.fillMaxHeight(.91f).aspectRatio(9 / 16f)
            .border(1.dp, Color.White.copy(alpha = .80f), RoundedCornerShape(4.dp)))
        if (reaction.isNotBlank() && received) Text(stringResource(viewfinderReactions.getValue(reaction)), color = Lime, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(26.dp).background(Ink.copy(alpha = .8f), Panel).padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@Composable private fun Msg(turn: Turn) {
    val clipboard = LocalClipboardManager.current
    Block {
        Text(turn.user, style = MaterialTheme.typography.titleMedium, color = Lime)
        Text(turn.answer)
        TextButton(onClick = { clipboard.setText(AnnotatedString(turn.answer)) }) { Text(stringResource(R.string.copiar_respuesta)) }
    }
}

@Composable private fun History(turns: List<Turn>, clear: () -> Unit, delete: (Set<String>) -> Unit, resume: (String) -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    val locale = context.interfaceLocale()
    val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val found = turns.groupBy { it.session }.values.filter { group ->
        query.isBlank() || group.any { it.user.contains(query, true) || it.answer.contains(query, true) }
    }.sortedByDescending { it.last().time }
    Heading(stringResource(R.string.historial), stringResource(R.string.conversaciones_guardadas_en_este_dispositivo))
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.buscar_en_el_historial)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = found.isNotEmpty(), onClick = { scope.launch {
            val relevant = found.filter { selected.isEmpty() || it.first().session in selected }.flatten().sortedBy { it.time }
            if (relevant.isEmpty()) { notify(context.getString(R.string.selecciona_conversaciones_que_aparezcan_en)); return@launch }
            val exported = withContext(Dispatchers.IO) { runCatching { exportHistory(context, relevant) } }
            exported.onSuccess { shareUri(context, it, "application/pdf") }.onFailure { notify(context.getString(R.string.no_se_pudo_exportar_el)) }
        } }) { Text(stringResource(R.string.exportar_a_pdf)) }
        TextButton(enabled = turns.isNotEmpty(), onClick = { confirm = true }) { Text(if (selected.isEmpty()) stringResource(R.string.borrar_historial) else stringResource(R.string.borrar_seleccion)) }
    }
    if (found.isEmpty()) Text(if (query.isBlank()) stringResource(R.string.aun_no_hay_conversaciones) else stringResource(R.string.no_hay_coincidencias), color = Muted)
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
                    Text(stringResource(R.string.mensajes, date.format(Date(recent.time)), group.size * 2), color = Muted)
                }
            }
            TextButton(onClick = { resume(first.session) }) { Text(stringResource(R.string.continuar_conversacion)) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (selected.isEmpty()) stringResource(R.string.borrar_todo_el_historial) else stringResource(R.string.borrar_las_conversaciones_seleccionadas)) },
        confirmButton = { TextButton(onClick = { if (selected.isEmpty()) clear() else delete(selected); selected = emptySet(); confirm = false }) { Text(stringResource(R.string.borrar)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancelar)) } })
}
