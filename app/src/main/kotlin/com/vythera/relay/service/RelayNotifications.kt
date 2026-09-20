package com.vythera.relay.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vythera.relay.R
import com.vythera.relay.ui.MainActivity

/**
 * Relay's notifications. Four channels so people can silence one kind without losing
 * the others: the quiet "Relay is on" status, requests that need an answer, transfer
 * progress, and things that arrived.
 */
class RelayNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.channel_status_body)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_REQUESTS, context.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_requests_body)
                },
                NotificationChannel(CHANNEL_TRANSFERS, context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_RECEIVED, context.getString(R.string.channel_received), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun status(title: String? = null, text: String? = null, progress: Int? = null): Notification =
        NotificationCompat.Builder(context, if (progress != null) CHANNEL_TRANSFERS else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: context.getString(R.string.notification_status_title))
            .setContentText(text ?: context.getString(R.string.notification_status_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .apply { if (progress != null) setProgress(100, progress, false) }
            .addAction(0, context.getString(R.string.notification_send_clipboard), sendClipboard())
            .addAction(0, context.getString(R.string.notification_turn_off), action(NotificationActionReceiver.ACTION_TURN_OFF, 1))
            .build()

    fun pairingRequest(deviceName: String) = post(
        ID_PAIRING,
        NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_pair_title, deviceName))
            .setContentText(context.getString(R.string.notification_pair_body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build(),
    )

    fun transferOffer(transferId: String, deviceName: String, summary: String, size: String) = post(
        idFor(transferId),
        NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.offer_title, deviceName))
            .setContentText(context.getString(R.string.notification_offer_body, summary, size))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.offer_receive), action(NotificationActionReceiver.ACTION_ACCEPT, idFor(transferId), transferId))
            .addAction(0, context.getString(R.string.offer_decline), action(NotificationActionReceiver.ACTION_DECLINE, idFor(transferId) + 1, transferId))
            .build(),
    )

    fun received(key: String, deviceName: String, text: String, open: Intent? = null) = post(
        idFor(key),
        NotificationCompat.Builder(context, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_received_title, deviceName))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open?.let { PendingIntent.getActivity(context, idFor(key), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) } ?: openApp())
            .build(),
    )

    fun clipboardOffer(clipId: String, deviceName: String, preview: String) = post(
        ID_CLIPBOARD,
        NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_clip_title, deviceName))
            .setContentText(preview.take(120))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.notification_clip_action), action(NotificationActionReceiver.ACTION_COPY_CLIP, ID_CLIPBOARD, clipId))
            .build(),
    )

    fun continueOffer(requestId: String, deviceName: String, url: String) = post(
        idFor(requestId),
        NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_continue_title, deviceName))
            .setContentText(url)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    idFor(requestId),
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build(),
    )

    fun update(id: Int, notification: Notification) = post(id, notification)

    fun cancel(key: String) = manager.cancel(idFor(key))
    fun cancelPairing() = manager.cancel(ID_PAIRING)

    private fun post(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(id, notification)
    }

    private fun sendClipboard(): PendingIntent = PendingIntent.getActivity(
        context,
        4,
        Intent(context, com.vythera.relay.clipboard.ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, requestCode: Int, argument: String? = null): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_ARGUMENT, argument),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_REQUESTS = "requests"
        const val CHANNEL_TRANSFERS = "transfers"
        const val CHANNEL_RECEIVED = "received"
        const val ID_STATUS = 1
        private const val ID_PAIRING = 2
        private const val ID_CLIPBOARD = 3

        fun idFor(key: String): Int = 1_000 + (key.hashCode() and 0x0fffffff)
    }
}
