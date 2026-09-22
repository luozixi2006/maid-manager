package com.miniichat.proactive

import android.content.Context
import kotlinx.coroutines.sync.Mutex

/** Shared gate prevents timer and device events from both sending at once. No transcript stored. */
object CompanionContactGate { val mutex=Mutex() }
object ProactiveDiagnostics {
    fun record(context:Context,persona:String,reason:String){context.getSharedPreferences("proactive_diagnostics",0).edit()
        .putString("reason:$persona",reason.take(160)).putLong("at:$persona",System.currentTimeMillis()).apply()}
    fun describe(context:Context,persona:String):String {
        val prefs=context.getSharedPreferences("proactive_diagnostics",0);val reason=prefs.getString("reason:$persona","").orEmpty()
        return if(reason.isBlank())"尚未进行自动判断" else com.miniichat.ui.formatMessageTime(prefs.getLong("at:$persona",0))+" · "+reason
    }
}
