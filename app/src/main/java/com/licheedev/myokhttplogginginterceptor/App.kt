package com.licheedev.myokhttplogginginterceptor

import android.app.Application
import com.licheedev.okhttploginterceptor.JsonLoggingInterceptor
import com.licheedev.okhttploginterceptor.LoggingInterceptor
import okhttp3.OkHttpClient
import rxhttp.wrapper.cookie.CookieStore
import rxhttp.wrapper.param.RxHttp
import rxhttp.wrapper.ssl.HttpsUtils

class App : Application() {


    override fun onCreate() {
        super.onCreate()

        // 创建okhttp
        val builder = OkHttpClient.Builder()
        builder.addInterceptor(MyHttpLogger(true,50))
        //builder.addInterceptor(JsonLoggingInterceptor(true, 50))
        //builder.addInterceptor(LoggingInterceptor(true))
        //val interceptor = HttpLoggingInterceptor()
        //interceptor.level = HttpLoggingInterceptor.Level.HEADERS
        //builder.addInterceptor(interceptor)
        builder.cookieJar(CookieStore())
        //https全通，不安全
        val sslParams = HttpsUtils.getSslSocketFactory();
        builder.sslSocketFactory(sslParams.sSLSocketFactory, sslParams.trustManager);
        // 配置rxhttp
        RxHttp.setDebug(false)
        RxHttp.init(builder.build())

    }
}