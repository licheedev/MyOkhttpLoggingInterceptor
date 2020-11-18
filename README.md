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
			maven { url 'https://dl.bintray.com/licheedev/maven' }
		}
	}
  
  	dependencies {
	        implementation 'com.licheedev:okhttplogginginterceptor:1.0.0'
	}
```

初始化okhttp(或其他基于okhttp的库)
```kotlin
// 创建okhttp
val builder = OkHttpClient.Builder()
builder.addInterceptor(JsonLoggingInterceptor(true,30))
...
```

定制过滤策略
```kotlin
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


