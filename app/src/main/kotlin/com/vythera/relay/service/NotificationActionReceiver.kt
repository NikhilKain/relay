package com.vythera.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vythera.relay.relay
import kotlinx.coroutines.launch

/** Handles buttons on Relay's notifications without opening the app. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val argument = intent.getStringExtra(EXTRA_ARGUMENT)
        val container = context.relay
        if (intent.action == ACTION_TURN_OFF) {
            RelayService.stop(context)
            return
        }
        val pending = goAsync()
        container.scope.launch {
            try {
                val controller = container.controller()
                when (intent.action) {
                    ACTION_ACCEPT -> argument?.let { controller.acceptTransfer(it) }
                    ACTION_DECLINE -> argument?.let { controller.declineTransfer(it) }
                    ACTION_COPY_CLIP -> argument?.let { controller.applyClipOffer(it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.vythera.relay.action.ACCEPT_TRANSFER"
        const val ACTION_DECLINE = "com.vythera.relay.action.DECLINE_TRANSFER"
        const val ACTION_COPY_CLIP = "com.vythera.relay.action.COPY_CLIP"
        const val ACTION_TURN_OFF = "com.vythera.relay.action.TURN_OFF"
        const val EXTRA_ARGUMENT = "argument"
    }
}
