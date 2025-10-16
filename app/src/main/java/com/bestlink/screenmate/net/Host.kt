package com.bestlink.screenmate.net

import java.io.Serializable

data class Host(
    val ip: String,
    val port: Int = 56789,
    var name: String? = null,
    var id: String? = null,
    var tagId: String? = null,
    var connected: Boolean = false,
    var lastHeartbeatOkCount: Int = 0
) : Serializable