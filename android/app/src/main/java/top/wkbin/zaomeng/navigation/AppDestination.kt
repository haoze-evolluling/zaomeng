package top.wkbin.zaomeng.navigation

import kotlinx.serialization.Serializable

@Serializable
data object BookshelfDestination

@Serializable
data object ImportBookDestination

@Serializable
data object ModelSettingsDestination

@Serializable
data class RunDetailDestination(val runId: String)

@Serializable
data class RedistillDestination(val runId: String)

@Serializable
data class RelationsDestination(val runId: String)

@Serializable
data object CardLibraryDestination

@Serializable
data class PersonaDestination(val runId: String, val character: String)

@Serializable
data class SessionsDestination(val runId: String = "")

@Serializable
data class ChatDestination(val runId: String, val sessionId: String)
