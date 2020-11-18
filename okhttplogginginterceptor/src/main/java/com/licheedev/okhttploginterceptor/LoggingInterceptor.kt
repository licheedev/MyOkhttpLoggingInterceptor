package com.licheedev.okhttploginterceptor

import com.licheedev.myutils.LogPlus
import okhttp3.*
import okhttp3.internal.http.StatusLine.HTTP_CONTINUE
import okio.Buffer
import okio.GzipSource
import java.io.EOFException
import java.io.StringReader
import java.net.HttpURLConnection
import java.nio.charset.Charset

/**
 * 日志拦截器
 * @param logRequest 是否打印日志
 * @constructor
 */
open class LoggingInterceptor(val logRequest: Boolean) : Interceptor {

    companion object {
        const val TAG = "OkHttpLog"
    }

    /** 允许打印请求信息的最大字节数 */
    protected open fun allowMaxRequestLength(): Long = 512

    /** 允许打印响应信息的最大字节数 */
    protected open fun allowMaxResponseLength(): Long = 2048

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

            val requestBody = request.body()

            // 请求头
            val headers = request.headers()
            if (headers.size() > 0) {
                val headersMap = mutableMapOf<kotlin.String, kotlin.String>()
                for (i in 0 until headers.size()) {
                    headersMap.put(headers.name(i), headers.value(i))
                }
                detail.headers = headersMap
            }

            // 请求参数
            val paramsContent = StringBuilder()

            if (requestBody == null) {
                paramsContent.append("NONE")
            } else if (bodyHasUnknownEncoding(request.headers())) {
                paramsContent.append("UNKNOWN(encoded body omitted)")
            } else {
                val buffer = Buffer()
                requestBody.writeTo(buffer)

                val contentType = requestBody.contentType()
                val charset: Charset =
                    contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8

                detail.paramsCharset = charset.name()

                if (buffer.isProbablyUtf8()) {
                    if (requestBody.contentLength() > allowMaxRequestLength()) {
                        val temp = buffer.readString(allowMaxRequestLength(), charset)
                        val index =
                            temp.indexOf("Content-Length:").let { if (it > -1) it else temp.length }
                        val nextIndex = temp.indexOf("\r\n", index).let {
                            if (it > index) it else index
                        }

                        val text = if (nextIndex > 0) {
                            temp.substring(0, nextIndex)
                        } else {
                            temp
                        }.replace("\r\n", " | ")
                        paramsContent.append("$text...")
                    } else {
                        paramsContent.append(buffer.readString(charset))
                    }
                } else {
                    paramsContent.append("IGNORE(binary ${requestBody.contentLength()}-byte body omitted)")
                }
            }

            detail.params = paramsContent.toString()

            // 异常
            if (error != null) {
                detail.error = error

                if (toLog(detail)) {
                    log(detail)
                }
                throw error
            }

            // 响应
            val responseContent = StringBuilder()
            response!! // 能到这里，就表示已经初始化
            val responseBody = response.body()
            // 响应状态码
            detail.responseCode = response.code()

            if (!response.promisesBody()) {
                responseContent.append("NONE")
            } else if (bodyHasUnknownEncoding(response.headers())) {
                responseContent.append("UNKNOWN(encoded body omitted)")
            } else if (responseBody != null) {
                val source = responseBody.source()
                source.request(Long.MAX_VALUE) // Buffer the entire body.
                var buffer = source.buffer()

                //var gzippedLength: Long? = null
                if ("gzip".equals(headers["Content-Encoding"], ignoreCase = true)) {
                    //gzippedLength = buffer.size()
                    GzipSource(buffer.clone()).use { gzippedResponseBody ->
                        buffer = Buffer()
                        buffer.writeAll(gzippedResponseBody)
                    }
                }

                val contentType = responseBody.contentType()
                val charset: Charset =
                    contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                detail.responseCharset = charset.name()

                if (buffer.isProbablyUtf8() && responseBody.contentLength() != 0L) {
                    if (buffer.size() > allowMaxResponseLength()) {
                        responseContent.append(
                            buffer.clone().readString(allowMaxResponseLength(), charset)
                        )
                    } else {
                        responseContent.append(buffer.clone().readString(charset))
                    }
                    detail.isText = true
                } else {
                    responseContent.append("IGNORE(binary ${buffer.size()}-byte body omitted)")
                }
            }
            // 响应文本
            detail.response = responseContent.toString()

            if (toLog(detail)) {
                log(detail)
            }
        }

        if (error != null) {
            throw error
        }
        return response!!
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


    private fun Buffer.isProbablyUtf8(): Boolean {
        try {
            val prefix = Buffer()
            val byteCount = size().coerceAtMost(64)
            copyTo(prefix, 0, byteCount)
            for (i in 0 until 16) {
                if (prefix.exhausted()) {
                    break
                }
                val codePoint = prefix.readUtf8CodePoint()
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false
                }
            }
            return true
        } catch (_: EOFException) {
            return false // Truncated UTF-8 sequence.
        }
    }

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
        } catch (_: NumberFormatException) {
            defaultValue
        }
    }

    private fun bodyHasUnknownEncoding(headers: Headers): Boolean {
        val contentEncoding = headers["Content-Encoding"] ?: return false
        return !contentEncoding.equals("identity", ignoreCase = true) &&
                !contentEncoding.equals("gzip", ignoreCase = true)
    }

} 
