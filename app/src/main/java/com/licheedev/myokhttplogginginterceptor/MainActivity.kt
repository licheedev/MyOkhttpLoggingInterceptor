package com.licheedev.myokhttplogginginterceptor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.android.synthetic.main.activity_main.*
import rxhttp.wrapper.param.RxHttp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonGet.setOnClickListener {

            RxHttp.get("https://api.apiopen.top/getSingleJoke?sid=28654780")
                .asString()
                .subscribe({
                    // TODO: 2020/11/18  
                }, {
                    // TODO: 2020/11/18  
                })
        }

        buttonImage.setOnClickListener {
            Glide.with(this)
                .load("https://image1.suning.cn/uimg/cms/img/159642507148437980.png")
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(image)
        }

    }
}