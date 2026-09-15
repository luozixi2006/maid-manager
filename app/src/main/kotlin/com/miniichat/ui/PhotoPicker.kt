package com.miniichat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.miniichat.data.ImageInputException
import com.miniichat.data.LocalImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PhotoActions(val choose: () -> Unit, val take: () -> Unit, val busy: Boolean)

/** Camera and picker share one bounded import path. No broad storage/camera permissions. */
@Composable
fun rememberPhotoActions(
    ownerKey: String,
    maxCount: Int,
    onImported: (List<String>) -> Unit,
    onError: (String) -> Unit
): PhotoActions {
    val context = LocalContext.current
    val store = remember(context) { LocalImageStore(context) }
    val scope = rememberCoroutineScope()
    val currentOwner by rememberUpdatedState(ownerKey)
    val currentImported by rememberUpdatedState(onImported)
    val currentError by rememberUpdatedState(onError)
    var requestOwner by rememberSaveable { mutableStateOf(ownerKey) }
    var requestLimit by rememberSaveable { mutableStateOf(maxCount) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun importPhotos(uris: List<Uri>, camera: Boolean = false) {
        if (uris.isEmpty()) return
        busy = true
        val owner = requestOwner
        scope.launch {
            try {
                val paths = mutableListOf<String>()
                for (uri in uris.take(requestLimit)) {
                    try { paths += store.import(uri) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        currentError((error as? ImageInputException)?.userReason
                            ?: "无法读取或保存照片，请检查存储空间并重新选择。")
                    }
                }
                if (owner == currentOwner) currentImported(paths)
                if (uris.size > requestLimit) currentError("一次最多添加 $requestLimit 张照片，多余照片未添加。")
            } finally {
                if (camera) uris.forEach(store::discardCamera)
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) {
        importPhotos(it)
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let { uri -> importPhotos(listOf(uri)) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri?.let(Uri::parse)
        cameraUri = null
        if (uri != null) {
            if (success) importPhotos(listOf(uri), camera = true) else store.discardCamera(uri)
        }
    }
    return PhotoActions(
        choose = {
            if (!busy && maxCount > 0) {
                requestOwner = ownerKey
                requestLimit = maxCount
                try {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (maxCount == 1) singlePicker.launch(request) else picker.launch(request)
                } catch (_: Exception) { onError("无法打开系统相册，请检查系统是否有可用的照片选择器。") }
            }
        },
        take = {
            if (!busy && maxCount > 0) {
                requestOwner = ownerKey
                requestLimit = 1
                try {
                    val uri = store.cameraUri()
                    cameraUri = uri.toString()
                    camera.launch(uri)
                } catch (_: Exception) {
                    cameraUri?.let { store.discardCamera(Uri.parse(it)) }
                    cameraUri = null
                    onError("无法打开相机，请确认手机已安装可用的相机应用。")
                }
            }
        },
        busy = busy
    )
}
