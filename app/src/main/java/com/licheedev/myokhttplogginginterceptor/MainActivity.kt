package com.licheedev.myokhttplogginginterceptor

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import rxhttp.wrapper.param.RxHttp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // kotlin-android-extensions 已在 Kotlin 1.8+ 移除，改用 findViewById
        findViewById<Button>(R.id.buttonGet).setOnClickListener {

            RxHttp.get("https://api.apiopen.top/getSingleJoke?sid=28654780")
                .asString()
                .subscribe({
                    // TODO: 2020/11/18  
                }, {
                    // TODO: 2020/11/18  
                })
        }

        findViewById<Button>(R.id.buttonImage).setOnClickListener {
            Glide.with(this)
                .load("https://image1.suning.cn/uimg/cms/img/159642507148437980.png")
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(findViewById<ImageView>(R.id.image))
        }

    }
}
