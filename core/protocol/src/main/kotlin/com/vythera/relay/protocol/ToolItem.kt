package com.vythera.relay.protocol

import kotlinx.serialization.Serializable

/** Bounded records for remote files, notifications and locally configured commands. */
@Serializable
data class ToolItem(val id: String, val title: String, val subtitle: String = "", val kind: String = "") {
    init {
        require(id.length <= 4096 && title.length <= 512 && subtitle.length <= 1024 && kind.length <= 32)
    }
}
