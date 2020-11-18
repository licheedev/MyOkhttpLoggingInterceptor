package com.licheedev.okhttploginterceptor

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.StringReader
import java.net.URLDecoder

/**
 * 日志拦截器，针对json文本进行Unicode解码，并且对特定行数内的json进行美化排版
 * @param logRequest 是否打印日志
 * @property logJsonLines Int 允许格式化输出的行数，只对少于该行数内的json进行格式化
 * @constructor
 */
open class JsonLoggingInterceptor(logRequest: Boolean, private val logJsonLines: Int) :
    LoggingInterceptor(logRequest) {

    companion object {
        val lineSeparator = System.getProperty("line.separator")!!
    }


    override fun formatLogText(detail: RequestDetail): String {

        val sb = StringBuilder()

        with(detail) {
            sb.append(method).append("-->")
                .append(url)

            if (headers != null) {
                sb.append("\nheaders-->").append(headers.toString())
            }

            if (params.isNotEmpty()) {
                sb.append("\nparams-->")
                    .append(
                        "{${
                            try {
                                // 这里的文本被url编码过，这里进行解码
                                URLDecoder.decode(params, paramsCharset)
                            } catch (e: Exception) {
                                params
                            }
                        }}"
                    )
            }

            if (error != null) {
                sb.append("\nerror-->").append(error)
                return@with
            }

            if (responseCode == Int.MIN_VALUE) {
                sb.append("\nresponse-->")
            } else {
                sb.append("\nresponse($responseCode)-->")
            }
            if (response.isNotEmpty()) {
                var formattedJson: kotlin.String? = null
                if (logJsonLines > 0) {
                    try {
                        val json: kotlin.String = response.trim()
                        if (json.startsWith("{")) {
                            val jsonObject = JSONObject(json)
                            formattedJson = jsonObject.toString(2)
                        } else if (json.startsWith("[")) {
                            val jsonArray = JSONArray(json)
                            formattedJson = jsonArray.toString(2)
                        }
                    } catch (e: JSONException) {
                        // 无视
                    }
                }

                if (formattedJson != null) {
                    val lines: Array<kotlin.String> =
                        formattedJson.split(lineSeparator).toTypedArray()
                    if (lines.size <= logJsonLines) {
                        for (line in lines) {
                            sb.append(lineSeparator).append(UnicodeUtil.decode(line))
                        }
                    } else {
                        sb.append(UnicodeUtil.decode(detail.response))
                    }
                } else {
                    sb.append(UnicodeUtil.decode(detail.response))
                }
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
} 
