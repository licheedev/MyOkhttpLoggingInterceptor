# MyOkhttpLoggingInterceptor
自定义Okhttp日志拦截器

## 核心代码参考
https://github.com/square/okhttp/tree/master/okhttp-logging-interceptor

## 使用

添加依赖
```gradle
allprojects {
    repositories {
        ...
        mavenCentral()
    }
}

dependencies {
  implementation 'com.licheedev:okhttplogginginterceptor:1.0.1'
}
```

## 大文件保护（1.0.1 起）

1.0.0 为了打日志，会对响应体执行 `source.request(Long.MAX_VALUE)`，等于把**整个响应体**
读进内存。下载几百 MB 的固件包、apk、视频时，会在开始写盘之前就把堆打爆（`OutOfMemoryError`，
堆栈落在 `okio.RealBufferedSource.request` → `LoggingInterceptor.intercept`）。

1.0.1 起对 body 的读取做了体积保护：

- 只有长度**已知且不超过上限**（默认 256KB）的 body 才会被读进内存；
- 长度未知（chunked）时，最多只探测 `上限 + 1` 个字节，够到上限就直接放弃；
- 超限的 body **不读取、不消费**，日志里只留一条摘要，下游仍能读到完整数据：

```
【HTTP】GET-->https://xxx/firmware.zip
【HTTP】response(200)-->IGNORE(524288000-byte response body omitted)
```

上限可按需覆盖（返回值单位为字节）：

```kotlin
class MyHttpLogger(logRequest: Boolean, logJsonLines: Int) :
    JsonLoggingInterceptor(logRequest, logJsonLines) {

    /** 响应体超过 1MB 就不记录了 */
    override fun allowMaxResponseBufferLength(): Long = 1024 * 1024L

    /** 请求体超过 1MB 就不记录了（上传大文件） */
    override fun allowMaxRequestBufferLength(): Long = 1024 * 1024L
}
```

`allowMaxRequestLength()` / `allowMaxResponseLength()` 仍然只控制**打印多少字符**，
与上面的缓冲上限互不影响。

初始化okhttp(或其他基于okhttp的库)
```kotlin
// 创建okhttp
val builder = OkHttpClient.Builder()
builder.addInterceptor(JsonLoggingInterceptor(true,30))
...
```

可定制定制过滤策略
```kotlin
builder.addInterceptor(MyHttpLogger(true,30))
// 重写JsonLoggingInterceptor
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
```

打印示例
```
2020-11-18 16:55:32.875 7416-7459/com.licheedev.myokhttplogginginterceptor I/OkHttpLog: log(LoggingInterceptor.kt:179)
    【HTTP】GET-->https://api.apiopen.top/getSingleJoke?sid=28654780
    【HTTP】params-->{NONE}
    【HTTP】response(200)-->
    【HTTP】{
    【HTTP】  "code": 200,
    【HTTP】  "message": "成功!",
    【HTTP】  "result": {
    【HTTP】    "sid": "28654780",
    【HTTP】    "text": "这难道是传说中的脸刹？",
    【HTTP】    "type": "video",
    【HTTP】    "thumbnail": "http://wimg.spriteapp.cn/picture/2018/0927/5bacc729ae94b__b.jpg",
    【HTTP】    "video": "http://wvideo.spriteapp.cn/video/2018/0927/5bacc729be874_wpd.mp4",
    【HTTP】    "images": null,
    【HTTP】    "up": "99",
    【HTTP】    "down": "7",
    【HTTP】    "forward": "3",
    【HTTP】    "comment": "9",
    【HTTP】    "uid": "12745266",
    【HTTP】    "name": "赵菓菓",
    【HTTP】    "header": "http://wimg.spriteapp.cn/profile/large/2018/08/14/5b721ea4242da_mini.jpg",
    【HTTP】    "top_comments_content": null,
    【HTTP】    "top_comments_voiceuri": null,
    【HTTP】    "top_comments_uid": null,
    【HTTP】    "top_comments_name": null,
    【HTTP】    "top_comments_header": null,
    【HTTP】    "passtime": "2018-09-30 02:55:02"
    【HTTP】  }
    【HTTP】}
```


