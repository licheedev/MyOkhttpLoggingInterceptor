package com.licheedev.okhttploginterceptor

/** 打log用的请求详情 */
class RequestDetail(
    /** 请求方法 */
    val method: String,
    /** 请求url */
    val url: String,
) {
    /** 请求头 */
    var headers: Map<String, String>? = null

    /** 请求参数 */
    var params: String = ""

    /** 响应状态码 */
    var responseCode = Int.MIN_VALUE

    /** 出现异常 */
    var error: Throwable? = null

    /** 响应内容 */
    var response: String = ""

    /** 响应是否为普通文本数据 */
    var isText: Boolean = false
        internal set
    
    /** 参数编码 */
    var paramsCharset = "utf8"
        internal set

    /** 响应文本编码 */
    var responseCharset = "utf8"
        internal set

}