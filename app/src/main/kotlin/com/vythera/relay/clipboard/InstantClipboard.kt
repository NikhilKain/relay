package com.vythera.relay.clipboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Copy on the phone, paste on the computer, without opening Relay.
 *
 * Since Android 10 only the app in focus may read the clipboard, so a background app is
 * not even told when it changes. What the system *does* do is log each listener it turns
 * away: "Denying clipboard access to <package>". Relay keeps a clipboard listener
 * registered, watches the system log for that line about itself, and when it appears
 * takes focus for an instant with an invisible one-pixel window, reads the clip, sends it,
 * and gives focus back.
 *
 * That needs two things Android does not grant apps on its own:
 * - **log access** (READ_LOGS), granted once from a computer with adb, and on Android 13+
 *   approved in a system prompt the first time Relay opens after each restart;
 * - **display over other apps**, for the one-pixel window.
 *
 * The same approach is used by KDE Connect. Nothing from the log is kept: Relay only looks
 * for its own package name in ClipboardService errors.
 */
class InstantClipboard(
    private val context: Context,
    private val onCopied: () -> Unit,
) {
    enum class Status { Off, NeedsLogAccess, NeedsOverlay, WaitingForApproval, Active }

    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val windows = context.getSystemService(WindowManager::class.java)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { /* only here so the system logs the denial */ }

    @Volatile private var enabled = false
    @Volatile private var reader: Thread? = null
    @Volatile private var logcat: java.lang.Process? = null
    private var focusView: View? = null
    private var pending = false

    val hasLogAccess: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    val hasOverlay: Boolean
        get() = Settings.canDrawOverlays(context)

    /**
     * Starts watching, if everything is in place. Call it while Relay is on screen: on
     * Android 13+ the log-access prompt can only appear for the app in front.
     */
    fun start() {
        enabled = true
        refreshStatus()
        if (!hasLogAccess || !hasOverlay) return
        // A reader that started before the user allowed log access only ever sees this
        // app's own lines, so it is replaced rather than left running blind.
        if (reader?.isAlive == true) {
            if (_status.value == Status.Active) return
            logcat?.destroy()
            reader = null
        }
        main.post { runCatching { clipboard.addPrimaryClipChangedListener(listener) } }
        reader = Thread(::watchLog, "relay-instant-clipboard").apply { isDaemon = true; start() }
    }

    fun stop() {
        enabled = false
        main.post { runCatching { clipboard.removePrimaryClipChangedListener(listener) } }
        logcat?.destroy()
        logcat = null
        reader = null
        _status.value = Status.Off
    }

    /** Re-checks permissions, e.g. after the user comes back from the overlay setting. */
    fun refreshStatus() {
        _status.value = when {
            !enabled -> Status.Off
            !hasLogAccess -> Status.NeedsLogAccess
            !hasOverlay -> Status.NeedsOverlay
            reader?.isAlive == true && _status.value == Status.Active -> Status.Active
            else -> Status.WaitingForApproval
        }
    }

    private fun watchLog() {
        val needle = "Denying clipboard access to ${context.packageName},"
        try {
            val process = ProcessBuilder("logcat", "-T", "1", "-v", "brief", "ClipboardService:E", "*:S").redirectErrorStream(true).start()
            logcat = process
            // Android asks the user before an app may read the whole log, and only one
            // such request is handled at a time: a second `logcat` here would be turned
            // down and the refusal remembered. So this is the only one, and the first
            // system line it reads is the proof that the user allowed it.
            _status.value = Status.WaitingForApproval
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                for (line in lines) {
                    if (!enabled) break
                    // Any line from the system is proof the prompt was answered with Allow.
                    _status.value = Status.Active
                    if (needle in line) main.post(::grabFocusAndRead)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instant clipboard stopped", e)
        } finally {
            if (enabled) _status.value = Status.WaitingForApproval
        }
    }

    /**
     * A one-pixel window that can take focus. While it has it, Relay is the focused app and
     * may read the clipboard. It is removed as soon as the clip is read, or after a second.
     */
    private fun grabFocusAndRead() {
        if (pending || !hasOverlay) return
        pending = true
        val view = object : View(context) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) {
                    onCopied()
                    // Give the read a moment on the main thread before handing focus back.
                    main.postDelayed(::releaseFocus, 150)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
        }
        runCatching { windows.addView(view, params) }
            .onSuccess {
                focusView = view
                main.postDelayed(::releaseFocus, 1000)
            }
            .onFailure {
                Log.w(TAG, "Could not show the focus window", it)
                pending = false
            }
    }

    private fun releaseFocus() {
        focusView?.let { runCatching { windows.removeView(it) } }
        focusView = null
        pending = false
    }

    /** The one command that grants log access, for the setup screen. */
    fun grantCommand(): String = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS"

    private companion object {
        const val TAG = "Relay"
    }
}
