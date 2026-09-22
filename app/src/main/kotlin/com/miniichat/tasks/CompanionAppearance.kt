package com.miniichat.tasks

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

object CompanionRuntime { val running = MutableStateFlow(false) }

object CompanionAppearance {
    const val AVATAR = "companion_avatar"
    const val WORK_AVATAR = "companion_work_avatar"
    const val POPUPS = "companion_popups"
    fun avatar(context: Context) = TaskActions.preferences(context).getString(AVATAR, "").orEmpty()
    fun setAvatar(context: Context, path: String) { TaskActions.preferences(context).edit().putString(AVATAR, path).apply() }
    fun workAvatar(context: Context) = TaskActions.preferences(context).getString(WORK_AVATAR, "").orEmpty()
    fun setWorkAvatar(context: Context, path: String) { TaskActions.preferences(context).edit().putString(WORK_AVATAR,path).apply() }
    fun resolveForMode(work:Boolean,chatCustom:String,workCustom:String,taskAvatar:String?,personaAvatar:String) =
        if(work) workCustom.ifBlank { taskAvatar?.ifBlank { personaAvatar } ?: personaAvatar } else chatCustom.ifBlank { personaAvatar }
    fun resolve(custom: String, taskAvatar: String?, personaAvatar: String) = custom.ifBlank { taskAvatar?.ifBlank { personaAvatar } ?: personaAvatar }
    fun popups(context: Context) = TaskActions.preferences(context).getBoolean(POPUPS, true)
}
