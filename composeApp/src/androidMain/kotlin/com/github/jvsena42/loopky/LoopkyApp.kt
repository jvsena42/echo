package com.github.jvsena42.loopky

import android.app.Application
import com.github.jvsena42.loopky.di.initKoinAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class LoopkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(unsplashAccessKey = BuildConfig.UNSPLASH_ACCESS_KEY) {
            androidLogger()
            androidContext(this@LoopkyApp)
        }
    }
}
