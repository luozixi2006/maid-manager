package com.miniichat.tasks

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

object CompanionRuntime { val running = MutableStateFlow(false) }

object CompanionAppearance {
    const val AVATAR = "companion_avatar"
    const val POPUPS = "companion_popups"
    fun avatar(context: Context) = TaskActions.preferences(context).getString(AVATAR, "").orEmpty()
    fun setAvatar(context: Context, path: String) { TaskActions.preferences(context).edit().putString(AVATAR, path).apply() }
    fun resolve(custom: String, taskAvatar: String?, personaAvatar: String) = custom.ifBlank { taskAvatar?.ifBlank { personaAvatar } ?: personaAvatar }
    fun popups(context: Context) = TaskActions.preferences(context).getBoolean(POPUPS, true)
}
