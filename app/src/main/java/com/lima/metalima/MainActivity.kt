package com.lima.metalima

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import com.lima.metalima.camera.CameraViewModel
import com.lima.metalima.livemode.LiveConversationViewModel
import com.lima.metalima.wearables.WearablesViewModel
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.WearablesError
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.interfaceContext())
    }

    private val glasses: WearablesViewModel by viewModels()
    private val camera: CameraViewModel by viewModels { CameraViewModel.Factory(application, glasses) }
    private val assistant by lazy { AssistantViewModel.shared(application) }
    private val conversation: LiveConversationViewModel by viewModels()
    private val permissionMutex = Mutex()
    private var cameraPrompt: CancellableContinuation<PermissionStatus>? = null
    private var audioPrompt: CancellableContinuation<Boolean>? = null

    private val bluetooth = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) registerGlasses()
        else glasses.setRecentError(getString(R.string.error_permissions_required))
    }
    private val wearablePermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val pending = cameraPrompt
        cameraPrompt = null
        pending?.resume(result.getOrDefault(PermissionStatus.Denied))
    }
    private val microphone = registerForActivityResult(RequestPermission()) { granted ->
        val pending = audioPrompt
        audioPrompt = null
        pending?.resume(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED) initWearables()
        setContent { MetaLimaApp(this, glasses, camera, assistant, conversation) }
    }

    override fun onStop() {
        conversation.stop()
        super.onStop()
    }

    fun connectGlasses() {
        if (checkSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED) registerGlasses()
        else bluetooth.launch(BLUETOOTH_CONNECT)
    }

    private fun initWearables(): Boolean = runCatching {
        glasses.onPermissionsResult(mapOf(BLUETOOTH_CONNECT to true)) {
            Wearables.initialize(applicationContext).onFailure { error, _ ->
                check(error == WearablesError.ALREADY_INITIALIZED) {
                    getString(R.string.no_se_pudo_iniciar_meta)
                }
            }
        }
    }.onFailure {
        glasses.setRecentError(getString(R.string.no_se_pudo_iniciar_la_3))
    }.isSuccess

    private fun registerGlasses() {
        if (initWearables()) runCatching { glasses.startRegistration(this) }.onFailure {
            glasses.setRecentError(getString(R.string.no_se_pudo_abrir_meta))
        }
    }

    suspend fun requestWearableCamera(permission: Permission): PermissionStatus = permissionMutex.withLock {
        suspendCancellableCoroutine { pending ->
            cameraPrompt = pending
            pending.invokeOnCancellation { cameraPrompt = null }
            runCatching { wearablePermission.launch(permission) }.onFailure {
                cameraPrompt = null
                pending.resume(PermissionStatus.Denied)
            }
        }
    }

    suspend fun requestMicrophone(): Boolean {
        if (checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED) return true
        return permissionMutex.withLock {
            suspendCancellableCoroutine { pending ->
                audioPrompt = pending
                pending.invokeOnCancellation { audioPrompt = null }
                runCatching { microphone.launch(RECORD_AUDIO) }.onFailure {
                    audioPrompt = null
                    pending.resume(false)
                }
            }
        }
    }
}
