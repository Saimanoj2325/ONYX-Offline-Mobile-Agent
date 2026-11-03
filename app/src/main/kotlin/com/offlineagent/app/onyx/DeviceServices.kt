package com.offlineagent.app.onyx

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.offlineagent.core.agents.DeviceSnapshot
import com.offlineagent.core.agents.Thermal
import com.offlineagent.engine.llamacpp.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Phase 5 preferences (separate file from the Phase 1–4 DataStore so nothing there changes). */
class OnyxPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("onyx_prefs", Context.MODE_PRIVATE)
    data class Values(
        val memoryEnabled: Boolean = true,
        val llmVerification: Boolean = true,
        val voiceReplies: Boolean = false,
        /** DARK (default, dark-first) | LIGHT | SYSTEM */
        val theme: String = "DARK",
        val dynamicColor: Boolean = true,
        /** Pause model work when the phone is hot (thermal SEVERE+). */
        val thermalGuard: Boolean = true,
    )
    private val _values = MutableStateFlow(Values())
    val values: StateFlow<Values> = _values.asStateFlow()

    /** Call off the main thread (first SharedPreferences read touches disk). */
    fun load() {
        _values.value = Values(sp.getBoolean("memory", true), sp.getBoolean("llm_verify", true), sp.getBoolean("voice_replies", false),
            sp.getString("theme", "DARK") ?: "DARK", sp.getBoolean("dynamic", true), sp.getBoolean("thermal_guard", true))
    }

    fun update(f: (Values) -> Values) {
        val v = f(_values.value); _values.value = v
        sp.edit().putBoolean("memory", v.memoryEnabled).putBoolean("llm_verify", v.llmVerification).putBoolean("voice_replies", v.voiceReplies)
            .putString("theme", v.theme).putBoolean("dynamic", v.dynamicColor).putBoolean("thermal_guard", v.thermalGuard).apply()
    }
}

/**
 * Device state for the Context agent and the runtime selector: RAM (ActivityManager.MemoryInfo), CPU cores, thermal
 * status (PowerManager listener, API 29+), battery saver, battery level, connectivity (default-network callback).
 */
class DeviceMonitor(private val ctx: Context) {
    private val _thermal = MutableStateFlow(Thermal.NONE)
    val thermal: StateFlow<Thermal> = _thermal.asStateFlow()
    private val _powerSave = MutableStateFlow(false)
    val powerSave: StateFlow<Boolean> = _powerSave.asStateFlow()
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private val _network = MutableStateFlow("none")
    val network: StateFlow<String> = _network.asStateFlow()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) {
            _thermal.value = Thermal.fromAndroid(pm.currentThermalStatus)
            pm.addThermalStatusListener(ctx.mainExecutor) { _thermal.value = Thermal.fromAndroid(it) }
        }
        _powerSave.value = pm.isPowerSaveMode
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { _powerSave.value = pm.isPowerSaveMode }
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    _network.value = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "network"
                    }
                }
                override fun onLost(network: Network) { _online.value = false; _network.value = "none" }
            })
        }.onFailure { com.offlineagent.app.diag.Diagnostics.log("network callback unavailable: $it") }
    }

    fun battery(): Pair<Int?, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return null to false
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return pct to bm.isCharging
    }

    /** Off the main thread: DeviceInfo reads sysfs once. */
    fun snapshot(): DeviceSnapshot {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val (pct, charging) = battery()
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return DeviceSnapshot(
            totalRamMb = mi.totalMem / (1024 * 1024), availRamMb = mi.availMem / (1024 * 1024), lowRamDevice = am.isLowRamDevice,
            cpuCores = Runtime.getRuntime().availableProcessors(), performanceCores = DeviceInfo.recommendedThreads(), soc = soc,
            sdkInt = Build.VERSION.SDK_INT, thermal = _thermal.value, powerSave = _powerSave.value, batteryPercent = pct, charging = charging,
            // Phase 6: free storage (models live in app-internal storage) + CPU features (dotprod / i8mm) for the tier picker.
            freeStorageMb = runCatching { android.os.StatFs(ctx.filesDir.absolutePath).availableBytes / (1024 * 1024) }.getOrDefault(-1L),
            cpuFeatures = DeviceInfo.cpuFeatures(),
        )
    }
}

/**
 * Voice input with Android's SpeechRecognizer. Uses the on-device recognizer when Android has one (API 31+,
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer]); otherwise the default recognizer with EXTRA_PREFER_OFFLINE, which
 * MAY use a network service if no offline language pack is installed — the UI says so ([offlineGuaranteed] = false).
 * Must be used from the main thread.
 */
class VoiceInput(private val ctx: Context) {
    sealed interface State {
        data object Idle : State
        data class Listening(val partial: String, val level: Float) : State
        data class Error(val message: String) : State
    }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx) || offlineGuaranteed
    val offlineGuaranteed: Boolean get() = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

    fun start(onResult: (String) -> Unit) {
        stop()
        if (!available) { _state.value = State.Error("No speech recognizer on this phone"); return }
        val r = if (offlineGuaranteed && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = State.Listening("", 0f) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { (_state.value as? State.Listening)?.let { _state.value = it.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) } }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                _state.value = State.Error(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Offline speech pack missing (recognizer wanted the network)"
                    else -> "Voice error $error"
                })
                release()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = State.Idle; release()
                if (text.isNotBlank()) onResult(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                (_state.value as? State.Listening)?.let { _state.value = it.copy(partial = t) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        runCatching { r.startListening(intent) }.onFailure { _state.value = State.Error("Could not start voice: ${it.message}"); release() }
    }

    fun stop() { recognizer?.stopListening(); release(); if (_state.value is State.Listening) _state.value = State.Idle }
    fun clearError() { if (_state.value is State.Error) _state.value = State.Idle }
    private fun release() { runCatching { recognizer?.destroy() }; recognizer = null }
}

/** Text-to-speech replies (the phone's TTS engine; most ship offline voices). */
class Speaker(ctx: Context) {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }
    fun speak(text: String) {
        if (!ready) return
        val clean = text.replace(Regex("[*#`>|_\\[\\]]"), " ").take(TextToSpeech.getMaxSpeechInputLength() - 1)
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "onyx-" + System.currentTimeMillis())
    }
    fun stop() { runCatching { tts.stop() } }
    fun shutdown() { runCatching { tts.shutdown() } }
}
