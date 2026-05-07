package com.namhyun.reflect.data

import kotlinx.serialization.Serializable

@Serializable
data class InboxEntity(
    val id: Long = 0,
    val app: String,
    val contact: String,
    val incoming: String,
    val suggestionsJson: String,
    val originalKey: String = "",
    val originalPkg: String = "",
    val arrivedAt: Long = System.currentTimeMillis(),
    val handled: Boolean = false,
)
