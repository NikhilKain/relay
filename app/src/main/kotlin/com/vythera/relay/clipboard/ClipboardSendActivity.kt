package com.vythera.relay.clipboard

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.vythera.relay.R
import com.vythera.relay.relay
import com.vythera.relay.service.RelayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An invisible activity that exists only to be focused.
 *
 * Android 10+ lets only the focused app read the clipboard, so "send what I just
 * copied" from the Quick Settings tile or the notification opens this for a fraction of
 * a second: once the window has focus it reads the clipboard, sends it to every trusted
 * device around, says where it went, and closes.
 */
class ClipboardSendActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RelayService.start(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent) return
        sent = true
        scope.launch {
            val controller = relay.controller()
            val targets = controller.syncLocalClipboard(force = true)
            val message = when {
                targets.isEmpty() -> getString(R.string.clipboard_nothing_sent)
                targets.size == 1 -> getString(R.string.send_sent_toast, controller.deviceName(targets.single()))
                else -> resources.getQuantityString(R.plurals.clipboard_sent_devices, targets.size, targets.size)
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
