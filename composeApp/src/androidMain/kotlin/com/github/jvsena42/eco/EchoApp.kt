package com.github.jvsena42.eco

import android.app.Application
import com.github.jvsena42.eco.di.initKoinAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class EchoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid {
            androidLogger()
            androidContext(this@EchoApp)
        }
    }
}
