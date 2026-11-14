package com.example.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

class MulticastLockManager(private val context: Context) {
    private var lock: WifiManager.MulticastLock? = null

    fun acquire() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (lock == null) {
                lock = wifi.createMulticastLock("WalkieTalkieMulticastLock").apply {
                    setReferenceCounted(true)
                }
            }
            if (lock?.isHeld == false) lock?.acquire()
        } catch (e: Exception) {
            Log.w("MulticastLockManager", "Failed to acquire MulticastLock", e)
        }
    }

    fun release() {
        try {
            if (lock?.isHeld == true) lock?.release()
        } catch (e: Exception) {
            Log.w("MulticastLockManager", "Failed to release MulticastLock", e)
        }
    }
}
