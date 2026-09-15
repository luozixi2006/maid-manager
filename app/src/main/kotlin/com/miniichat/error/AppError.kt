package com.miniichat.error

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/**
 * A privacy-safe, user-facing description of an application failure.
 *
 * Deliberately absent: request/response bodies, prompts, chat messages, API keys,
 * authorization headers and arbitrary Throwable messages. Those values must never
 * enter the local error history or an exported report.
 */
@Serializable
data class AppError(
    val id: String = newErrorId(),
    val occurredAt: Long = System.currentTimeMillis(),
    val area: ErrorArea = ErrorArea.UNKNOWN,
    val operation: ErrorOperation = ErrorOperation.UNKNOWN,
    val type: AppErrorType = AppErrorType.UNEXPECTED,
    val code: String = type.code,
    val title: String,
    val userMessage: String,
    val explanation: String,
    val suggestions: List<String> = emptyList(),
    val providerHost: String? = null,
    val modelId: String? = null,
    val httpStatus: Int? = null,
    /** Exception class names only. Throwable messages are intentionally excluded. */
    val causeChain: List<String> = emptyList(),
    val recoverable: Boolean = true
)

@Serializable
enum class ErrorArea(val label: String) {
    CHAT("聊天"),
    MODEL("模型"),
    SEARCH("联网搜索"),
    STORAGE("本地数据"),
    IMPORT_EXPORT("导入与导出"),
    UPDATE("软件更新"),
    AUDIO("语音"),
    TASK("后台任务"),
    SETTINGS("设置"),
    UNKNOWN("其他")
}

@Serializable
enum class ErrorOperation(val label: String) {
    SEND_MESSAGE("发送消息"),
    LIST_MODELS("获取模型列表"),
    TEST_CONNECTION("测试连接"),
    WEB_SEARCH("联网搜索"),
    READ_LOCAL_DATA("读取本地数据"),
    WRITE_LOCAL_DATA("保存本地数据"),
    IMPORT_DATA("导入数据"),
    EXPORT_DATA("导出数据"),
    CHECK_UPDATE("检查更新"),
    DOWNLOAD_UPDATE("下载更新"),
    INSTALL_UPDATE("安装更新"),
    PLAY_AUDIO("播放语音"),
    EXECUTE_PHONE_TASK("执行手机任务"),
    EXTRACT_MEMORY("提取长期记忆"),
    VALIDATE_SETTINGS("检查设置"),
    UNKNOWN("执行操作")
}

@Serializable
enum class AppErrorType(val code: String, val label: String) {
    CONFIGURATION_MISSING("CONFIGURATION_MISSING", "配置缺失"),
    INVALID_ADDRESS("INVALID_ADDRESS", "地址无效"),
    AUTHENTICATION_FAILED("AUTHENTICATION_FAILED", "身份验证失败"),
    ACCESS_DENIED("ACCESS_DENIED", "访问被拒绝"),
    MODEL_OR_ENDPOINT_NOT_FOUND("MODEL_OR_ENDPOINT_NOT_FOUND", "模型或接口不存在"),
    REQUEST_REJECTED("REQUEST_REJECTED", "请求不被接受"),
    INPUT_TOO_LARGE("INPUT_TOO_LARGE", "请求内容过长"),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁"),
    PROVIDER_UNAVAILABLE("PROVIDER_UNAVAILABLE", "服务暂时不可用"),
    NETWORK_UNAVAILABLE("NETWORK_UNAVAILABLE", "网络不可用"),
    HOST_NOT_FOUND("HOST_NOT_FOUND", "找不到服务地址"),
    CONNECTION_FAILED("CONNECTION_FAILED", "无法连接服务"),
    TLS_FAILED("TLS_FAILED", "安全连接失败"),
    CLEARTEXT_BLOCKED("CLEARTEXT_BLOCKED", "HTTP 连接被系统阻止"),
    REQUEST_TIMEOUT("REQUEST_TIMEOUT", "请求超时"),
    RESPONSE_FORMAT_INVALID("RESPONSE_FORMAT_INVALID", "返回格式不兼容"),
    EMPTY_RESPONSE("EMPTY_RESPONSE", "服务返回空内容"),
    LOCAL_STORAGE_FAILED("LOCAL_STORAGE_FAILED", "本地保存失败"),
    UPDATE_MANIFEST_INVALID("UPDATE_MANIFEST_INVALID", "更新清单无效"),
    UPDATE_DOWNLOAD_FAILED("UPDATE_DOWNLOAD_FAILED", "更新包下载失败"),
    UPDATE_INTEGRITY_FAILED("UPDATE_INTEGRITY_FAILED", "更新包完整性校验失败"),
    UPDATE_PACKAGE_MISMATCH("UPDATE_PACKAGE_MISMATCH", "更新包应用标识不符"),
    UPDATE_VERSION_MISMATCH("UPDATE_VERSION_MISMATCH", "更新包版本不符"),
    UPDATE_SIGNATURE_MISMATCH("UPDATE_SIGNATURE_MISMATCH", "更新包签名不符"),
    UPDATE_INSTALL_FAILED("UPDATE_INSTALL_FAILED", "无法启动系统安装"),
    PERMISSION_DENIED("PERMISSION_DENIED", "缺少系统权限"),
    CANCELLED("CANCELLED", "操作已取消"),
    UNEXPECTED("UNEXPECTED", "未知错误")
}

data class AppErrorContext(
    val area: ErrorArea = ErrorArea.UNKNOWN,
    val operation: ErrorOperation = ErrorOperation.UNKNOWN,
    val providerBaseUrl: String? = null,
    val modelId: String? = null
)

fun newErrorId(now: Long = System.currentTimeMillis()): String {
    val timePart = java.lang.Long.toString(now, 36).uppercase(Locale.ROOT)
    val randomPart = UUID.randomUUID().toString().replace("-", "").take(6).uppercase(Locale.ROOT)
    return "ERR-$timePart-$randomPart"
}
