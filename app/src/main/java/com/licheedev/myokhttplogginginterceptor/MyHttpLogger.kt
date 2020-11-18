package com.licheedev.myokhttplogginginterceptor

import com.licheedev.okhttploginterceptor.JsonLoggingInterceptor
import com.licheedev.okhttploginterceptor.RequestDetail

class MyHttpLogger(logRequest: Boolean, logJsonLines: Int) :
    JsonLoggingInterceptor(logRequest, logJsonLines) {

    /** 不打印log的接口 */
    private val noLogUrls = listOf<String>(
        "heartbeat"
        // TODO: 这里加入不需要打log的接口的url 
    ).map {
        "TODO.BASE_URL$it"
    }

    override fun toLog(detail: RequestDetail): Boolean {
        // 这里用来排除下载图片等响应为非文本的http请求
        //if (detail.response.contains("IGNORE")) {
        //    return false
        //}

        noLogUrls.forEach {
            if (it.contains(detail.url)) {
                return false
            }
        }
        return super.toLog(detail)
    }
}