package ru.dkd.app

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

object PushTokenRegistrar {
    fun register(context: Context, session: String, fcmToken: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("${BuildConfig.DKD_API_BASE_URL.trimEnd('/')}/api/android/push-token").openConnection() as HttpURLConnection)
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $session")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = org.json.JSONObject().put("token", fcmToken).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) Log.w("DKD-Push", "Token registration failed: HTTP $status")
            } catch (error: Exception) {
                Log.w("DKD-Push", "Token registration failed", error)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
