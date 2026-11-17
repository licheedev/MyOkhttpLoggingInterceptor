package com.licheedev.okhttploginterceptor

import com.licheedev.myutils.LogPlus
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.GzipSource
import okio.Okio
import java.io.EOFException
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.nio.charset.Charset

/**
 * 日志拦截器
 *
 * 读取 body 时做了体积保护，避免大文件（固件包、apk、图片、日志压缩包等）在下载/上传时
 * 被整个读进内存导致 OOM：
 *
 * 1. 只有长度**已知且不超过** [allowMaxRequestBufferLength] / [allowMaxResponseBufferLength]
 *    的 body 才会被读进内存；
 * 2. 长度未知（chunked）时，最多只探测 `上限 + 1` 个字节，够到上限就直接放弃；
 * 3. 超限的 body **不读取、不消费**，只在日志里留一条 `IGNORE(...)` 摘要，下游照常读到完整数据。
 *
 * 说明：旧版本对响应体执行 `source.request(Long.MAX_VALUE)`，等于把整个响应体缓冲进内存，
 * 下载几百 MB 的固件/视频时会在写盘之前就把堆打爆。
 *
 * @param logRequest 是否打印日志
 */
open class LoggingInterceptor(val logRequest: Boolean) : Interceptor {

    companion object {
        const val TAG = "OkHttpLog"

        /** 请求体/响应体允许读进内存的默认上限（字节） */
        const val DEFAULT_MAX_BUFFER_LENGTH: Long = 256 * 1024L

        private const val HTTP_CONTINUE = 100
    }

    /** 允许打印请求信息的最大字符数 */
    protected open fun allowMaxRequestLength(): Long = 512

    /** 允许打印响应信息的最大字符数 */
    protected open fun allowMaxResponseLength(): Long = 2048

    /**
     * 允许读进内存的请求体上限（字节）。
     * 超过该值的请求体（如上传大文件）不会被读取，只打印一条 `IGNORE(...)` 摘要。
     */
    protected open fun allowMaxRequestBufferLength(): Long = DEFAULT_MAX_BUFFER_LENGTH

    /**
     * 允许读进内存的响应体上限（字节）。
     * 超过该值的响应体（如固件、apk、图片）不会被读取，只打印一条 `IGNORE(...)` 摘要。
     */
    protected open fun allowMaxResponseBufferLength(): Long = DEFAULT_MAX_BUFFER_LENGTH

    override fun intercept(chain: Interceptor.Chain): Response {

        val request: Request = chain.request()

        var response: Response? = null
        var error: Throwable? = null
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e
        }

        if (logRequest) {

            val detail = RequestDetail(request.method(), request.url().toString())

            // 请求头
            val headers = request.headers()
            if (headers.size() > 0) {
                val headersMap = mutableMapOf<String, String>()
                for (i in 0 until headers.size()) {
                    headersMap[headers.name(i)] = headers.value(i)
                }
                detail.headers = headersMap
            }

            // 请求参数
            detail.paramsCharset = charsetOf(request.body()?.contentType()).name()
            detail.params = parseParams(request)

            // 异常
            if (error != null) {
                detail.error = error

                if (toLog(detail)) {
                    log(detail)
                }
                throw error
            }

            // 响应
            response!! // 能到这里，就表示已经初始化
            parseResponse(detail, response)

            if (toLog(detail)) {
                log(detail)
            }
        }

        if (error != null) {
            throw error
        }
        return response!!
    }

    /** 请求参数（带体积保护，超限则不读取） */
    private fun parseParams(request: Request): String {

        val requestBody: RequestBody = request.body() ?: return "NONE"

        if (bodyHasUnknownEncoding(request.headers())) {
            return "UNKNOWN(encoded body omitted)"
        }

        val contentLength = contentLengthOf(requestBody)
        val maxBufferLength = allowMaxRequestBufferLength()

        // 长度已知且超限：一个字节都不读
        if (contentLength > maxBufferLength) {
            return "IGNORE(request body too large, ${contentLength}-byte omitted)"
        }

        // 长度未知（流式/分块上传）：不读取，避免整个请求体进入内存
        if (contentLength < 0L) {
            return "IGNORE(unknown-length request body omitted)"
        }

        return try {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            val charset = charsetOf(requestBody.contentType())
            if (buffer.isProbablyUtf8()) {
                buffer.readParamsText(charset)
            } else {
                "IGNORE(binary ${contentLength}-byte request body omitted)"
            }
        } catch (e: IOException) {
            "UNKNOWN(error while reading request body: $e)"
        }
    }

    /** 把已缓冲的请求体转成文本，过长则截断 */
    private fun Buffer.readParamsText(charset: Charset): String {

        val text = readString(charset)
        val maxLength = allowMaxRequestLength().coerceAtLeast(0L)
        if (text.length <= maxLength) {
            return text
        }

        val prefix = text.substring(0, maxLength.toInt())
        // 被截断的长表单，去掉残缺的 Content-Length 行
        val index = prefix.indexOf("Content-Length:").let { if (it > -1) it else prefix.length }
        val nextIndex = prefix.indexOf("\r\n", index).let { if (it > index) it else index }
        val cut = if (nextIndex > 0) prefix.substring(0, nextIndex) else prefix
        return cut.replace("\r\n", " | ") + "..."
    }

    /** 响应内容（带体积保护，超限则不读取） */
    private fun parseResponse(detail: RequestDetail, response: Response) {

        val responseContent = StringBuilder()
        // 响应状态码
        detail.responseCode = response.code()

        val responseBody = response.body()

        when {
            !response.promisesBody() -> responseContent.append("NONE")

            bodyHasUnknownEncoding(response.headers()) ->
                responseContent.append("UNKNOWN(encoded body omitted)")

            responseBody == null -> responseContent.append("NONE")

            else -> {
                val charset = charsetOf(responseBody.contentType())
                detail.responseCharset = charset.name()

                val text = readResponseText(response, responseBody, charset)
                if (text != null) {
                    responseContent.append(text)
                    detail.isText = true
                } else {
                    val length = contentLengthOf(responseBody)
                    val sizeText = if (length >= 0) "${length}-byte" else "unknown-size"
                    responseContent.append("IGNORE($sizeText response body omitted)")
                }
            }
        }
        // 响应文本
        detail.response = responseContent.toString()
    }

    /**
     * 在不超过 [allowMaxResponseBufferLength] 的前提下缓冲响应体并转成文本。
     *
     * 返回 null 表示体积超限或内容不是文本，放弃记录；
     * 无论返回什么，响应体都不会被消费，下游仍能读到完整数据。
     */
    private fun readResponseText(
        response: Response,
        body: ResponseBody,
        charset: Charset
    ): String? {

        val contentLength = contentLengthOf(body)
        val maxBufferLength = allowMaxResponseBufferLength()

        // 长度已知且超限：一个字节都不读
        if (contentLength > maxBufferLength) {
            return null
        }

        if (contentLength == 0L) {
            return ""
        }

        val source = body.source()

        if (contentLength > 0L) {
            // 长度已知且不超限：预读进 buffer（只填充，不消费）
            try {
                source.request(contentLength)
            } catch (e: IOException) {
                return null
            }
        } else {
            // 长度未知（chunked）：最多探测 上限+1 个字节；
            // 够不到上限说明整个 body 已经缓冲完，可以安全记录
            val enough = try {
                source.request(maxBufferLength + 1)
            } catch (e: IOException) {
                return null
            }
            if (enough) {
                return null
            }
        }

        var snapshot: Buffer = source.buffer().clone()

        // 兜底的 gzip 处理（正常情况 BridgeInterceptor 已解压并去掉 Content-Encoding 头）
        if ("gzip".equals(response.header("Content-Encoding"), ignoreCase = true)) {
            snapshot = inflate(snapshot) ?: return null
        }

        if (!snapshot.isProbablyUtf8()) {
            return null
        }

        val text = snapshot.readString(charset)
        val maxLength = allowMaxResponseLength().coerceAtLeast(0L)
        return if (text.length <= maxLength) text else text.substring(0, maxLength.toInt())
    }

    /**
     * 解压 gzip 数据。
     * 解压结果最多保留 `allowMaxResponseLength() * 4` 个字节，避免压缩比异常的数据把内存撑爆。
     */
    private fun inflate(source: Buffer): Buffer? {
        val maxInflate = allowMaxResponseLength().coerceAtLeast(0L) * 4
        if (maxInflate <= 0L) {
            return null
        }
        val result = Buffer()
        return try {
            GzipSource(source.clone()).use { gzip ->
                val buffered = Okio.buffer(gzip)
                var total = 0L
                while (total < maxInflate) {
                    val read = buffered.read(result, maxInflate - total)
                    if (read == -1L) {
                        break
                    }
                    total += read
                }
            }
            result
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 判断是否需要打log
     * @param detail RequestDetail
     * @return Boolean
     */
    protected open fun toLog(detail: RequestDetail): Boolean {
        return true
    }

    /** 进行打log操作 */
    protected open fun log(detail: RequestDetail) {
        LogPlus.i(TAG, formatLogText(detail))
    }

    /** 格式化log内容 */
    protected open fun formatLogText(detail: RequestDetail): String {
        val sb = StringBuilder()

        with(detail) {
            sb.append(method).append("-->")
                .append(url)

            if (headers != null) {
                sb.append("\nheaders-->").append(headers.toString())
            }

            if (params.isNotEmpty()) {
                sb.append("\nparams-->").append(params)
            }

            if (error != null) {
                sb.append("\nerror-->").append(error)
                return sb.toString()
            }

            if (responseCode == Int.MIN_VALUE) {
                sb.append("\nresponse-->")
            } else {
                sb.append("\nresponse($responseCode)-->")
            }
            if (response.isNotEmpty()) {
                sb.append(response)
            }
        }

        StringReader(sb.toString()).use {
            sb.clear()
            it.forEachLine { line ->
                sb.append("\n【HTTP】").append(line)
            }
        }
        return sb.toString()
    }

    /** 是否为文本内容（只看头部少量字节，不需要整个 body 进内存） */
    private fun Buffer.isProbablyUtf8(): Boolean {
        return try {
            val prefix = Buffer()
            // 数据不足 64 字节时只取实际存在的部分（copyTo 会校验范围，不能直接传 64）
            val byteCount = size().coerceAtMost(64L)
            copyTo(prefix, 0L, byteCount)
            for (i in 0 until 16) {
                if (prefix.size() == 0L) {
                    break
                }
                val codePoint = prefix.readUtf8CodePoint()
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false
                }
            }
            true
        } catch (e: EOFException) {
            // 数据太短（含空 body），按文本处理
            true
        }
    }

    /** 响应是否会带 body */
    private fun Response.promisesBody(): Boolean {
        // HEAD requests never yield a body regardless of the response headers.
        if (request().method() == "HEAD") {
            return false
        }

        val responseCode = code()
        if ((responseCode < HTTP_CONTINUE || responseCode >= 200) &&
            responseCode != HttpURLConnection.HTTP_NO_CONTENT &&
            responseCode != HttpURLConnection.HTTP_NOT_MODIFIED
        ) {
            return true
        }

        // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
        // response is malformed. For best compatibility, we honor the headers.
        if (headersContentLength() != -1L ||
            "chunked".equals(header("Transfer-Encoding"), ignoreCase = true)
        ) {
            return true
        }

        return false
    }

    /** Returns the Content-Length as reported by the response headers. */
    private fun Response.headersContentLength(): Long {
        return headers()["Content-Length"]?.toLongOrDefault(-1L) ?: -1L
    }

    private fun String.toLongOrDefault(defaultValue: Long): Long {
        return try {
            toLong()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    /** 取 body 的 Content-Length，取不到（分块/异常）返回 -1 */
    private fun contentLengthOf(requestBody: RequestBody): Long {
        return try {
            requestBody.contentLength()
        } catch (e: IOException) {
            -1L
        }
    }

    /** 取 body 的 Content-Length，取不到（分块/异常）返回 -1 */
    private fun contentLengthOf(body: ResponseBody): Long {
        return try {
            body.contentLength()
        } catch (e: IOException) {
            -1L
        }
    }

    /** 取编码，缺省 UTF-8 */
    private fun charsetOf(contentType: MediaType?): Charset {
        return contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    }

    private fun bodyHasUnknownEncoding(headers: Headers): Boolean {
        val contentEncoding = headers["Content-Encoding"] ?: return false
        return !contentEncoding.equals("identity", ignoreCase = true) &&
                !contentEncoding.equals("gzip", ignoreCase = true)
    }
}
