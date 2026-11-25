package com.radba.remoto

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class RadbaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        }
    }
}
