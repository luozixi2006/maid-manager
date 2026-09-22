package com.miniichat.watch

import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.core.app.*
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.*
import com.miniichat.companion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object WatchRuntime {
    val status=MutableStateFlow("尚未连接")
    val sensing=MutableStateFlow(false)
    val revision=MutableStateFlow(0)
    private val lock=Mutex()
    suspend fun run(context:Context) = lock.withLock {
        if(!LinkConfig(context).enabled) return@withLock
        try {
            val initial=LinkStore(context).use{it.meta("cursor").isBlank()}
            val received=LinkClient.sync(context).toMutableList()
            val thoughtError=runCatching{LinkClient.think(context)}.exceptionOrNull()
            if(thoughtError is CancellationException)throw thoughtError
            received+=LinkClient.sync(context)
            received.filter {!initial && it.getString("kind")=="message" && it.getJSONObject("body").optString("role")=="assistant"}.forEach {change->
                val profile=LinkConfig(context).profile; val text=change.getJSONObject("body").optString("content")
                val manager=context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("messages","角色消息",NotificationManager.IMPORTANCE_HIGH))
                val avatar=runCatching {val bytes=Base64.decode(profile.optString("avatar"),Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)}.getOrNull()
                val person=Person.Builder().setName(profile.optString("name","陪伴")).apply {avatar?.let {setIcon(IconCompat.createWithBitmap(it))}}.build()
                val intent=PendingIntent.getActivity(context,0,Intent(context,WatchActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification=NotificationCompat.Builder(context,"messages").setSmallIcon(R.drawable.ic_watch)
                    .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("我").build()).addMessage(text,System.currentTimeMillis(),person))
                    .setContentTitle(person.name).setContentText(text).setContentIntent(intent).setAutoCancel(true).setLargeIcon(avatar).build()
                if(Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)==0)
                    manager.notify(change.getString("id").hashCode(),notification)
            }
            status.value=if(thoughtError!=null)"记录已同步，回复待重试" else "已同步";revision.value++
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) { status.value=failureText(error) }
    }
    /** Only short, app-authored diagnostics reach the UI; transport payloads and the token never do. */
    private fun failureText(error:Exception):String {
        val known=error.message.orEmpty().trim()
        return when {
            error is LinkFailure -> known.ifBlank{"手机没有完成这次同步"}
            (error is IllegalStateException || error is IllegalArgumentException) && known.isNotBlank() && known.length<=60 -> known
            else -> "暂时无法连接，记录已留在手表"
        }
    }
    fun schedule(context:Context) {
        // Bluetooth transport needs no network, so no network constraint is applied. UPDATE replaces the
        // scheduled work, so an older CONNECTED constraint can no longer block recovery sync.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("watch-recovery",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchSyncWorker>(15,TimeUnit.MINUTES).build())
    }
}
class WatchSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {WatchRuntime.run(applicationContext);return Result.success()}
}
class WatchBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(LinkConfig(context).enabled) {
            WatchRuntime.schedule(context)
            // Do not silently restart health FGS without the required while-in-use grant.
            WatchRuntime.status.value="重启后请打开手表应用恢复感知"
        }
    }
}
