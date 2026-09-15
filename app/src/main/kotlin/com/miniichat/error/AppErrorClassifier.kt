package com.miniichat.error

import com.miniichat.api.LlmEmptyResponseException
import com.miniichat.api.LlmHttpException
import com.miniichat.api.LlmProtocolException
import com.miniichat.update.UpdateException
import com.miniichat.update.UpdateFailureReason
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException as JavaSocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object AppErrorClassifier {

    fun classify(error: Throwable, context: AppErrorContext = AppErrorContext()): AppError {
        val causes = causeSequence(error)
        causes.filterIsInstance<com.miniichat.data.ImageInputException>().firstOrNull()?.let { image ->
            return describe(AppErrorType.LOCAL_STORAGE_FAILED, context, null,
                listOf(image::class.java.name)).copy(
                title = "照片暂时无法使用", userMessage = image.userReason,
                explanation = "照片没有成功读取或超过大小限制，尚未发送给模型。",
                suggestions = listOf("重新选择照片，或新建对话后重试")
            )
        }
        val http = causes.filterIsInstance<LlmHttpException>().firstOrNull()
        if (http?.statusCode in setOf(400, 415, 422) && http != null &&
            listOf("image_url", "image input", "vision", "multimodal", "image content").any {
                http.responseBody.contains(it, ignoreCase = true)
            }) {
            return describe(AppErrorType.REQUEST_REJECTED, context, http.statusCode,
                listOf(http::class.java.name)).copy(
                title = "模型未接受照片", userMessage = "当前服务拒绝了图片请求。",
                explanation = "该模型可能不支持图片，或照片数量、大小超出模型限制。原始服务响应不会写入错误报告。",
                suggestions = listOf("在模型目录选择支持图片输入的视觉模型", "减少照片数量后重试")
            )
        }
        val update = causes.filterIsInstance<UpdateException>().firstOrNull()
        val type = when {
            error is CancellationException -> AppErrorType.CANCELLED
            update != null -> classifyUpdateFailure(update.reason)
            http != null -> classifyHttpStatus(http.statusCode)
            causes.any { it is HttpRequestTimeoutException || it is ConnectTimeoutException ||
                it is JavaSocketTimeoutException } -> AppErrorType.REQUEST_TIMEOUT
            causes.any { it is UnknownHostException } -> AppErrorType.HOST_NOT_FOUND
            causes.any { it is SSLException } -> AppErrorType.TLS_FAILED
            causes.any { throwableLooksLikeCleartextBlock(it) } -> AppErrorType.CLEARTEXT_BLOCKED
            causes.any { it is ConnectException || it is SocketException } -> AppErrorType.CONNECTION_FAILED
            causes.any { it is LlmProtocolException || it is SerializationException } ->
                AppErrorType.RESPONSE_FORMAT_INVALID
            causes.any { it is LlmEmptyResponseException } -> AppErrorType.EMPTY_RESPONSE
            causes.any { it is SecurityException } -> AppErrorType.PERMISSION_DENIED
            context.area in setOf(ErrorArea.STORAGE, ErrorArea.IMPORT_EXPORT) &&
                causes.any { it is IOException || it is FileNotFoundException } -> AppErrorType.LOCAL_STORAGE_FAILED
            causes.any { it is IOException } -> AppErrorType.NETWORK_UNAVAILABLE
            context.providerBaseUrl?.let { safeProviderHost(it) == null } == true -> AppErrorType.INVALID_ADDRESS
            else -> AppErrorType.UNEXPECTED
        }
        return describe(
            type = type,
            context = context,
            httpStatus = http?.statusCode ?: update?.httpStatus,
            causeChain = causes.map { it::class.java.name }.distinct().take(MAX_CAUSE_DEPTH)
        )
    }

    fun validation(
        type: AppErrorType,
        context: AppErrorContext,
        title: String? = null,
        message: String? = null
    ): AppError {
        require(type in setOf(AppErrorType.CONFIGURATION_MISSING, AppErrorType.INVALID_ADDRESS)) {
            "validation() only accepts configuration error types"
        }
        val base = describe(type, context, null, emptyList())
        return base.copy(
            title = title ?: base.title,
            userMessage = message ?: base.userMessage
        )
    }

    /** For HTTP clients that do not use [LlmHttpException]. No response body is accepted. */
    fun http(statusCode: Int, context: AppErrorContext = AppErrorContext()): AppError = describe(
        type = classifyHttpStatus(statusCode),
        context = context,
        httpStatus = statusCode,
        causeChain = emptyList()
    )

    private fun classifyHttpStatus(statusCode: Int): AppErrorType = when (statusCode) {
        400, 405, 406, 415, 422 -> AppErrorType.REQUEST_REJECTED
        401 -> AppErrorType.AUTHENTICATION_FAILED
        403 -> AppErrorType.ACCESS_DENIED
        404 -> AppErrorType.MODEL_OR_ENDPOINT_NOT_FOUND
        408, 504 -> AppErrorType.REQUEST_TIMEOUT
        409 -> AppErrorType.REQUEST_REJECTED
        413 -> AppErrorType.INPUT_TOO_LARGE
        429 -> AppErrorType.RATE_LIMITED
        in 500..599 -> AppErrorType.PROVIDER_UNAVAILABLE
        else -> AppErrorType.REQUEST_REJECTED
    }

    private fun classifyUpdateFailure(reason: UpdateFailureReason): AppErrorType = when (reason) {
        UpdateFailureReason.MANIFEST_INVALID -> AppErrorType.UPDATE_MANIFEST_INVALID
        UpdateFailureReason.DOWNLOAD_NETWORK_FAILED -> AppErrorType.UPDATE_DOWNLOAD_FAILED
        UpdateFailureReason.DOWNLOAD_STORAGE_FAILED -> AppErrorType.LOCAL_STORAGE_FAILED
        UpdateFailureReason.APK_INTEGRITY_FAILED -> AppErrorType.UPDATE_INTEGRITY_FAILED
        UpdateFailureReason.APK_PACKAGE_MISMATCH -> AppErrorType.UPDATE_PACKAGE_MISMATCH
        UpdateFailureReason.APK_VERSION_MISMATCH -> AppErrorType.UPDATE_VERSION_MISMATCH
        UpdateFailureReason.APK_SIGNATURE_MISMATCH -> AppErrorType.UPDATE_SIGNATURE_MISMATCH
        UpdateFailureReason.INSTALL_LAUNCH_FAILED -> AppErrorType.UPDATE_INSTALL_FAILED
    }

    private fun describe(
        type: AppErrorType,
        context: AppErrorContext,
        httpStatus: Int?,
        causeChain: List<String>
    ): AppError {
        val details = when (type) {
            AppErrorType.CONFIGURATION_MISSING -> ErrorDescription(
                "配置未完成", "请先完成这项功能所需的设置。",
                "当前操作缺少服务地址、模型或密钥等必要配置，因此没有发出请求。",
                listOf("打开对应设置并补全必填项", "保存后重新尝试")
            )
            AppErrorType.INVALID_ADDRESS -> ErrorDescription(
                "服务地址无效", "填写的服务地址无法识别。",
                "地址可能缺少 http:// 或 https://，也可能包含不支持的格式。",
                listOf("检查 Base URL 是否完整", "不要把接口路径重复填写在 Base URL 中")
            )
            AppErrorType.AUTHENTICATION_FAILED -> ErrorDescription(
                "API 密钥无效", "服务拒绝了身份验证${statusSuffix(httpStatus)}。",
                "通常是 API 密钥错误、已失效，或密钥没有发送到当前服务。",
                listOf("重新复制并保存当前服务的 API 密钥", "确认密钥属于当前服务")
            )
            AppErrorType.ACCESS_DENIED -> ErrorDescription(
                "当前账号无权访问", "服务拒绝了这次请求${statusSuffix(httpStatus)}。",
                "当前密钥可能没有模型权限、地区受限，或服务端策略禁止了该操作。",
                listOf("检查账号和模型权限", "在服务商控制台确认账号状态")
            )
            AppErrorType.MODEL_OR_ENDPOINT_NOT_FOUND -> ErrorDescription(
                "模型或接口不存在", "服务找不到请求的模型或接口${statusSuffix(httpStatus)}。",
                "模型 ID 可能已经变更，或 Base URL 与 OpenAI 兼容接口路径不匹配。",
                listOf("重新获取模型列表并选择可用模型", "检查服务地址是否填写正确")
            )
            AppErrorType.REQUEST_REJECTED -> ErrorDescription(
                "请求未被服务接受", "服务认为请求格式或参数不符合要求${statusSuffix(httpStatus)}。",
                "当前模型可能不支持某个参数，或兼容接口与标准格式存在差异。",
                listOf("检查当前模型是否支持所用功能", "尝试更换模型或服务")
            )
            AppErrorType.INPUT_TOO_LARGE -> ErrorDescription(
                "对话内容过长", "服务拒绝了过大的请求${statusSuffix(httpStatus)}。",
                "当前对话和系统提示的总长度超过了模型或服务允许的上限。",
                listOf("新建聊天后重试", "换用上下文容量更大的模型")
            )
            AppErrorType.RATE_LIMITED -> ErrorDescription(
                "请求过于频繁", "服务暂时限制了请求${statusSuffix(httpStatus)}。",
                "短时间请求次数过多、并发过高，或账号额度触发了服务商限制。",
                listOf("稍等片刻后重试", "在服务商控制台检查额度和限流规则")
            )
            AppErrorType.PROVIDER_UNAVAILABLE -> ErrorDescription(
                "AI 服务暂时不可用", "服务端未能完成请求${statusSuffix(httpStatus)}。",
                "服务可能正在维护、负载过高，或上游模型暂时故障。",
                listOf("稍后重试", "启用备用模型或切换到其他服务")
            )
            AppErrorType.NETWORK_UNAVAILABLE -> ErrorDescription(
                "网络连接失败", "设备当前无法完成网络请求。",
                "网络可能未连接、被代理或防火墙中断，服务也可能暂时不可达。",
                listOf("检查设备网络连接", "确认代理、VPN 或 Tailscale 状态")
            )
            AppErrorType.HOST_NOT_FOUND -> ErrorDescription(
                "找不到服务地址", "无法解析服务的网络地址。",
                "Base URL 的域名可能填写错误，或者当前 DNS 无法解析该域名。",
                listOf("检查 Base URL 拼写", "切换网络后重试")
            )
            AppErrorType.CONNECTION_FAILED -> ErrorDescription(
                "无法连接服务", "设备未能与服务建立连接。",
                "服务可能没有启动、端口不可访问，或连接被防火墙和网络策略阻止。",
                listOf("确认服务正在运行", "检查端口、VPN/Tailscale 和防火墙")
            )
            AppErrorType.TLS_FAILED -> ErrorDescription(
                "安全连接失败", "无法建立可信的 HTTPS 连接。",
                "服务器证书可能过期、域名不匹配，或网络代理修改了证书。",
                listOf("检查设备日期和网络环境", "确认服务端 HTTPS 证书有效")
            )
            AppErrorType.CLEARTEXT_BLOCKED -> ErrorDescription(
                "Android 阻止了 HTTP 请求", "系统安全策略不允许访问这个明文 HTTP 地址。",
                "当前地址使用 http://，但应用没有被允许访问该明文地址。",
                listOf("优先改用 HTTPS 地址", "如为可信局域网服务，请使用应用支持的 HTTP 配置")
            )
            AppErrorType.REQUEST_TIMEOUT -> ErrorDescription(
                "请求超时", "服务在规定时间内没有完成响应${statusSuffix(httpStatus)}。",
                "网络延迟、服务排队或模型生成耗时过长都可能导致超时。",
                listOf("保持网络稳定后重试", "缩短输入或改用响应更快的模型")
            )
            AppErrorType.RESPONSE_FORMAT_INVALID -> ErrorDescription(
                "服务返回格式不兼容", "已收到响应，但应用无法按兼容格式读取。",
                "服务返回的内容不是预期的 OpenAI 兼容 JSON 或 SSE 格式。",
                listOf("确认 Base URL 指向兼容接口", "尝试关闭流式输出后重试")
            )
            AppErrorType.EMPTY_RESPONSE -> ErrorDescription(
                "服务返回空内容", "请求成功，但没有收到可显示的回复。",
                "模型可能被内容策略拦截、提前结束，或兼容接口没有返回标准内容字段。",
                listOf("换一种问法后重试", "尝试其他模型或关闭流式输出")
            )
            AppErrorType.LOCAL_STORAGE_FAILED -> ErrorDescription(
                "本地数据操作失败", "无法读取或保存所需的本地文件。",
                "文件可能不可访问、存储空间不足，或所选位置已失效。",
                listOf("确认设备有足够存储空间", "重新选择可访问的文件位置")
            )
            AppErrorType.UPDATE_MANIFEST_INVALID -> ErrorDescription(
                "更新清单无效", "下载到的更新说明未通过安全检查。",
                "清单格式、下载地址、应用标识或安全字段不符合当前应用的更新规则，因此更新已停止。",
                listOf("稍后重新检查更新", "确认当前版本绑定的是官方项目仓库")
            )
            AppErrorType.UPDATE_DOWNLOAD_FAILED -> ErrorDescription(
                "更新包下载失败", "无法从 GitHub 完成更新文件下载${statusSuffix(httpStatus)}。",
                "网络连接、GitHub 服务状态或下载响应异常，导致更新包没有完整保存。",
                listOf("检查网络后重新下载", "稍后重试并保持应用在前台")
            )
            AppErrorType.UPDATE_INTEGRITY_FAILED -> ErrorDescription(
                "更新包校验失败", "下载的 APK 不完整或与发布清单不一致。",
                "文件为空、已损坏、下载不完整，或 SHA-256 完整性校验不匹配；应用不会打开这个安装包。",
                listOf("删除缓存后重新下载更新", "确认更新来自项目的正式 GitHub Release")
            )
            AppErrorType.UPDATE_PACKAGE_MISMATCH -> ErrorDescription(
                "更新包不属于当前应用", "APK 的应用标识与女仆管理器不一致。",
                "下载到的文件或发布清单指向了另一个 Android 应用，为避免覆盖错误应用已停止安装。",
                listOf("重新检查并下载正式更新", "确认当前版本绑定的是正确的项目仓库")
            )
            AppErrorType.UPDATE_VERSION_MISMATCH -> ErrorDescription(
                "更新包版本不符", "APK 版本与发布清单或当前设备不匹配。",
                "版本号、版本名称、最低 Android 版本可能不一致，或者下载的版本没有高于当前版本。",
                listOf("重新检查更新并下载最新版本", "确认手机 Android 版本满足新版本要求")
            )
            AppErrorType.UPDATE_SIGNATURE_MISMATCH -> ErrorDescription(
                "更新包签名不一致", "Android 更新包不是由当前安装版本的签名密钥生成。",
                "为防止未知 APK 覆盖现有应用，签名校验已阻止继续安装。",
                listOf("只安装项目正式 GitHub Release 中的 APK", "如更换过签名密钥，请先导出本地数据再处理安装")
            )
            AppErrorType.UPDATE_INSTALL_FAILED -> ErrorDescription(
                "无法打开系统安装器", "更新包已准备好，但 Android 安装确认页面未能启动。",
                "系统安装器不可用、文件授权失败，或设备安全策略阻止了应用打开 APK。",
                listOf("检查是否允许本应用安装未知来源应用", "重新下载后再次打开系统安装确认")
            )
            AppErrorType.PERMISSION_DENIED -> ErrorDescription(
                "缺少系统权限", "Android 不允许应用完成这项操作。",
                "相关系统权限尚未授予，或被设备的安全策略限制。",
                listOf("在系统设置中检查应用权限", "授权后重新尝试")
            )
            AppErrorType.CANCELLED -> ErrorDescription(
                "操作已取消", "这次操作没有完成。",
                "操作被用户取消，或因为页面离开而自动停止。",
                listOf("需要时重新执行操作")
            )
            AppErrorType.UNEXPECTED -> ErrorDescription(
                "发生未知错误", "应用未能完成这项操作。",
                "这是尚未识别的错误类型，已记录不含聊天内容和密钥的诊断信息。",
                listOf("重试一次", "如持续发生，请导出错误报告用于排查")
            )
        }
        return AppError(
            area = context.area,
            operation = context.operation,
            type = type,
            code = type.code,
            title = details.title,
            userMessage = details.message,
            explanation = details.explanation,
            suggestions = details.suggestions,
            providerHost = context.providerBaseUrl?.let(::safeProviderHost),
            modelId = context.modelId?.let(PrivacySanitizer::safeIdentifier),
            httpStatus = httpStatus,
            causeChain = causeChain,
            recoverable = type !in setOf(AppErrorType.ACCESS_DENIED, AppErrorType.PERMISSION_DENIED)
        )
    }

    private fun statusSuffix(status: Int?): String = status?.let { "（HTTP $it）" }.orEmpty()

    private fun causeSequence(error: Throwable): List<Throwable> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && result.size < MAX_CAUSE_DEPTH && seen.add(current)) {
            result += current
            current = current.cause
        }
        return result
    }

    private fun throwableLooksLikeCleartextBlock(error: Throwable): Boolean {
        // The message is inspected in memory only and is never persisted or exported.
        val message = error.message.orEmpty()
        return message.contains("CLEARTEXT", ignoreCase = true) &&
            message.contains("not permitted", ignoreCase = true)
    }

    private data class ErrorDescription(
        val title: String,
        val message: String,
        val explanation: String,
        val suggestions: List<String>
    )

    private const val MAX_CAUSE_DEPTH = 8
}

fun safeProviderHost(baseUrl: String): String? = runCatching {
    val normalized = if (baseUrl.contains("://")) baseUrl else "https://$baseUrl"
    URI(normalized).host?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.take(253)
}.getOrNull()
