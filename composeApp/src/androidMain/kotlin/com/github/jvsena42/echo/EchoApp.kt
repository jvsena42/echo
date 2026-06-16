package com.github.jvsena42.echo

import android.app.Application
import com.github.jvsena42.echo.di.initKoinAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class EchoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(unsplashAccessKey = BuildConfig.UNSPLASH_ACCESS_KEY) {
            androidLogger()
            androidContext(this@EchoApp)
        }
    }
}
