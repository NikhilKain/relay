package com.vythera.relay.widget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.vythera.relay.R
import com.vythera.relay.relay
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.service.RelayService
import kotlinx.coroutines.launch

/**
 * The widget's tap target: pick files, send them to one device, and get out of the way.
 *
 * It has no interface of its own. The system file picker opens straight away, and the
 * transfer starts as soon as files are chosen, so sending from the home screen is
 * two taps: the device, then the file.
 */
class SendToActivity : ComponentActivity() {
    private var target: DeviceId? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult finish()
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val to = target ?: return@registerForActivityResult finish()
        lifecycleScope.launch {
            val controller = relay.controller()
            controller.sendUris(to, uris)
            Toast.makeText(this@SendToActivity, getString(R.string.widget_sending, controller.deviceName(to)), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(DeviceKey.name)?.takeIf { it.isNotBlank() } ?: return finish()
        target = DeviceId(id)
        RelayService.start(this)
        runCatching { picker.launch(arrayOf("*/*")) }.onFailure { finish() }
    }
}
