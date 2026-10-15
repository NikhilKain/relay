package com.vythera.relay.node

import com.vythera.relay.protocol.ToolItem

/** Tracks snapshots rather than accumulating notification history or private content. */
class NotificationChanges {
    private var previous: Map<String, ToolItem>? = null
    fun reset() { previous = null }
    fun update(items: List<ToolItem>): List<ToolItem> {
        val current = items.associateBy { it.id }
        val old = previous
        previous = current
        return if (old == null) emptyList() else current.values.filter { old[it.id] != it }
    }
}

class LowBatteryWarning {
    private var warned = false
    fun update(level: Int?, charging: Boolean): Boolean {
        if (charging || (level != null && level >= 20)) warned = false
        if (level == null || level !in 0..15 || charging || warned) return false
        warned = true
        return true
    }
}
