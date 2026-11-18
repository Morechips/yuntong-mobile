package cn.yuntong.companion

import android.app.Application

class YuntongApplication : Application() {
    val controller: AppController by lazy { AppController(this) }
}
