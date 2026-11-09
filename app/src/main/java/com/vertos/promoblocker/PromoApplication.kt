package com.vertos.promoblocker

import android.app.Application

class PromoApplication : Application() {
    lateinit var repository: BlockerRepository
        private set
    override fun onCreate() {
        super.onCreate()
        // Build default patterns before Telecom connects. Disk loading remains asynchronous.
        DefaultRules.rules
        repository = BlockerRepository(this)
    }
}
