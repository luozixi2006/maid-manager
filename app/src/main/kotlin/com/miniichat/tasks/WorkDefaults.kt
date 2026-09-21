package com.miniichat.tasks

import android.content.Context

/** Explicit user preference; never grants OS permission or extends an app/file scope. */
object WorkDefaults {
    const val ROUTINE = "work_routine_default"
    fun routine(context: Context) = TaskActions.preferences(context).getBoolean(ROUTINE, false)
    fun remember(context: Context, enabled: Boolean) = TaskActions.preferences(context).edit().putBoolean(ROUTINE, enabled).apply()
}
