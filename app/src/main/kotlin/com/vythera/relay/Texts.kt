package com.vythera.relay

import android.content.Context
import android.text.format.Formatter
import com.vythera.relay.content.ContentCategory
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.protocol.TransferItem
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferStatus
import java.util.concurrent.TimeUnit

/**
 * Every sentence Relay shows about devices and transfers. Kept in one place so the
 * tone stays consistent and so no exception text can reach the UI by accident.
 */
object Texts {
    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun speed(context: Context, bytesPerSecond: Long): String? =
        if (bytesPerSecond <= 0) null else context.getString(R.string.transfer_speed, size(context, bytesPerSecond))

    fun remaining(context: Context, millis: Long?): String? {
        millis ?: return null
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        return when {
            seconds < 3 -> context.getString(R.string.transfer_almost_done)
            seconds < 90 -> context.getString(R.string.transfer_remaining_seconds, seconds.toInt())
            else -> context.getString(R.string.transfer_remaining_minutes, TimeUnit.SECONDS.toMinutes(seconds).toInt() + 1)
        }
    }

    fun relativeTime(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(now - millis)
        return when {
            minutes < 1 -> context.getString(R.string.time_just_now)
            minutes < 60 -> context.getString(R.string.time_minutes, minutes.toInt())
            minutes < 24 * 60 -> context.getString(R.string.time_hours, (minutes / 60).toInt())
            minutes < 48 * 60 -> context.getString(R.string.time_yesterday)
            else -> android.text.format.DateUtils.formatDateTime(context, millis, android.text.format.DateUtils.FORMAT_ABBREV_MONTH or android.text.format.DateUtils.FORMAT_SHOW_DATE)
        }
    }

    fun platform(context: Context, platform: Platform): String = context.getString(
        when (platform) {
            Platform.ANDROID -> R.string.device_platform_android
            Platform.WINDOWS -> R.string.device_platform_windows
            Platform.LINUX -> R.string.device_platform_linux
            Platform.MACOS -> R.string.device_platform_macos
            Platform.WEB -> R.string.device_platform_web
            Platform.UNKNOWN -> R.string.device_platform_unknown
        },
    )

    fun kind(type: DeviceType): RelayDeviceKind = when (type) {
        DeviceType.PHONE -> RelayDeviceKind.Phone
        DeviceType.TABLET -> RelayDeviceKind.Tablet
        DeviceType.LAPTOP -> RelayDeviceKind.Laptop
        DeviceType.DESKTOP -> RelayDeviceKind.Desktop
        DeviceType.BROWSER -> RelayDeviceKind.Browser
        DeviceType.UNKNOWN -> RelayDeviceKind.Unknown
    }

    /** "vacation.zip", "12 photos", "3 files". */
    fun itemsSummary(context: Context, items: List<TransferItem>, category: ContentCategory): String {
        if (items.size == 1) return items.first().name
        val res = context.resources
        return when (category) {
            ContentCategory.PHOTOS -> res.getQuantityString(R.plurals.content_photos, items.size, items.size)
            ContentCategory.VIDEOS -> res.getQuantityString(R.plurals.content_videos, items.size, items.size)
            ContentCategory.FOLDER -> items.first().relativePath.substringBefore('/').ifEmpty { res.getQuantityString(R.plurals.offer_files, items.size, items.size) }
            else -> res.getQuantityString(R.plurals.offer_files, items.size, items.size)
        }
    }

    fun failure(context: Context, failure: TransferFailure, peerName: String): String = when (failure) {
        TransferFailure.CONNECTION_LOST -> context.getString(R.string.failure_connection_lost, peerName)
        TransferFailure.PEER_UNAVAILABLE -> context.getString(R.string.failure_peer_unavailable, peerName)
        TransferFailure.NO_RESPONSE -> context.getString(R.string.failure_no_response, peerName)
        TransferFailure.CHECKSUM_MISMATCH -> context.getString(R.string.failure_checksum)
        TransferFailure.STORAGE_FULL -> context.getString(R.string.failure_storage_full, peerName)
        TransferFailure.STORAGE_UNAVAILABLE -> context.getString(R.string.failure_storage_unavailable)
        TransferFailure.SOURCE_UNAVAILABLE -> context.getString(R.string.failure_source_unavailable)
        TransferFailure.NOT_TRUSTED -> context.getString(R.string.failure_not_trusted, peerName)
        TransferFailure.INCOMPATIBLE_VERSION -> context.getString(R.string.failure_incompatible, peerName)
        TransferFailure.PROTOCOL_ERROR -> context.getString(R.string.failure_protocol)
    }

    fun outcome(status: TransferStatus): String = when (status) {
        TransferStatus.Completed -> "completed"
        TransferStatus.Declined -> "declined"
        is TransferStatus.Cancelled -> "cancelled"
        is TransferStatus.Failed -> "failed"
        else -> "active"
    }
}
