package com.miniichat.watch

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.miniichat.companion.LinkConfig
import com.miniichat.companion.LinkStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class WatchConnectionUiTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun savedConnection(enabled:Boolean) {
        context.getSharedPreferences("companion_link",0).edit().putString("base","bluetooth://AA:BB:CC:DD:EE:FF")
            .putString("conversation","chat-a").putString("profile",JSONObject().put("persona_id","persona-a").put("name","角色 A").toString())
            .putBoolean("enabled",enabled).commit()
        LinkStore(context).use{it.meta("bound","chat-a");it.enqueue("message","queued-a",JSONObject().put("id","queued-a").put("role","user").put("content","未发出的消息"))}
    }
    private fun buttons(root:View):List<Button> = buildList {
        if(root is Button)add(root)
        if(root is ViewGroup)for(i in 0 until root.childCount)addAll(buttons(root.getChildAt(i)))
    }
    @Test fun connectedPageHasVisibleSyncDisconnectAndRepairActions() {
        savedConnection(true)
        val controller=Robolectric.buildActivity(WatchActivity::class.java).create()
        val root=controller.get().window.decorView
        for(text in listOf("立即同步","断开","重新配对","旧会话")) {
            val button=buttons(root).single{it.text.toString()==text}
            assertEquals(View.VISIBLE,button.visibility)
            assertEquals(View.VISIBLE,(button.parent as View).visibility)
        }
        controller.destroy()
    }
    @Test fun disconnectRetainsQueuedMessagesAndOffersConnectWithoutNewCode() {
        savedConnection(true)
        val controller=Robolectric.buildActivity(WatchActivity::class.java).create()
        val activity=controller.get()
        buttons(activity.window.decorView).single{it.text=="断开"}.performClick()
        assertFalse(LinkConfig(context).enabled)
        assertTrue(buttons(activity.window.decorView).any{it.text=="连接原手机"})
        LinkStore(context).use{assertTrue(it.hasPending("message","queued-a"))}
        controller.destroy()
    }
    @Test fun pendingDataDoesNotBlockShowingRePairForm() {
        savedConnection(false)
        val controller=Robolectric.buildActivity(WatchActivity::class.java).create()
        val activity=controller.get()
        buttons(activity.window.decorView).single{it.text=="重新配对"}.performClick()
        assertTrue(buttons(activity.window.decorView).any{it.text=="验证并连接"})
        assertFalse(LinkConfig(context).enabled)
        LinkStore(context).use{assertTrue(it.hasPending("message","queued-a"))}
        controller.destroy()
    }
}
