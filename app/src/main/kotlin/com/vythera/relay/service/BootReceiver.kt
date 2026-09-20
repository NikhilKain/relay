package com.vythera.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vythera.relay.relay
import kotlinx.coroutines.launch

/** Brings Relay back after a reboot or an update, if the user wants it always available. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val container = context.relay
        container.scope.launch {
            try {
                val settings = container.settings.current()
                if (settings.onboardingComplete && settings.stayAvailable) RelayService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
