package com.iborn.reminder

import android.app.Activity
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore

class StoreBridge(private val activity: Activity, private val emit: (String) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = activity.getSharedPreferences("reminder_secure_session", Activity.MODE_PRIVATE)
    private val alias = "reminder_store_session_key"
    private val apiBase = "https://iborn.pythonanywhere.com/api/v1"

    @JavascriptInterface fun hasSession(): Boolean = readToken() != null

    @JavascriptInterface fun login(username: String, password: String) {
        executor.execute {
            try {
                val response = request("/auth/login", "POST", JSONObject().put("username", username).put("password", password), null)
                val token = response.optString("token")
                if (token.isBlank()) throw IllegalStateException("پاسخ ورود از سرور معتبر نیست.")
                writeToken(token)
                val payload = loadPayload(token)
                emitCallback("onStoreLoginResult", true, payload)
            } catch (e: Exception) {
                clearToken()
                emitCallback("onStoreLoginResult", false, JSONObject().put("message", safeMessage(e)))
            }
        }
    }

    @JavascriptInterface fun refresh() {
        val token = readToken()
        if (token == null) {
            emitCallback("onStoreData", false, JSONObject().put("unauthorized", true).put("message", "لطفاً دوباره وارد شوید."))
            return
        }
        executor.execute {
            try {
                emitCallback("onStoreData", true, loadPayload(token))
            } catch (e: Exception) {
                val unauthorized = e.message?.contains("HTTP 401") == true
                if (unauthorized) clearToken()
                emitCallback("onStoreData", false, JSONObject().put("unauthorized", unauthorized).put("message", safeMessage(e)))
            }
        }
    }

    /** Local logout only. No logout/write request is sent to the server. */
    @JavascriptInterface fun logout() {
        clearToken()
        activity.runOnUiThread { emit("window.onStoreLoggedOut && window.onStoreLoggedOut()") }
    }

    private fun loadPayload(token: String): JSONObject {
        val today = LocalDate.now()
        val from = today.minusDays(7).toString()
        val to = today.plusDays(90).toString()
        val me = request("/me", "GET", null, token)
        val products = request("/products", "GET", null, token)
        val shiftsRaw = request("/shifts?from=$from&to=$to", "GET", null, token)
        val tasks = request("/tasks?from=$from&to=$to", "GET", null, token)
        val permissions = me.optJSONObject("permissions") ?: JSONObject()
        val profile = me.optJSONObject("profile") ?: JSONObject()
        val isManager = permissions.optBoolean("manage_staff", false)
        // The current API returns all shift memberships. For staff, expose only their own shifts/membership.
        val shifts = if (isManager) shiftsRaw else {
            val ownId = profile.optInt("id", -1)
            val ownShifts = JSONArray()
            val allShifts = shiftsRaw.optJSONArray("shifts") ?: JSONArray()
            for (i in 0 until allShifts.length()) {
                val shift = allShifts.optJSONObject(i) ?: continue
                val members = shift.optJSONArray("members") ?: JSONArray()
                val ownMembers = JSONArray()
                for (j in 0 until members.length()) {
                    val member = members.optJSONObject(j) ?: continue
                    if (member.optInt("id", -2) == ownId) ownMembers.put(member)
                }
                if (ownMembers.length() > 0) ownShifts.put(JSONObject(shift.toString()).put("members", ownMembers))
            }
            JSONObject().put("shifts", ownShifts)
        }
        // Staff directory is a manager-only GET endpoint; never request it for staff accounts.
        val staff = if (isManager) request("/staff", "GET", null, token) else JSONObject()
        return JSONObject().put("me", me).put("products", products).put("shifts", shifts).put("tasks", tasks).put("staff", staff)
    }

    private fun request(path: String, method: String, body: JSONObject?, token: String?): JSONObject {
        val connection = (URL(apiBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${extractError(text)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun extractError(text: String): String = try { JSONObject(text).optString("error", "درخواست ناموفق بود") } catch (_: Exception) { "درخواست ناموفق بود" }
    private fun safeMessage(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("HTTP 401") || m.contains("invalid_credentials") -> "نام کاربری یا رمز عبور صحیح نیست، یا نشست معتبر نیست."
            m.contains("HTTP 403") -> "این حساب مجوز مشاهده بخشی از اطلاعات را ندارد."
            m.contains("UnknownHost") || m.contains("unable to resolve") -> "اتصال اینترنت یا دسترسی به سرور برقرار نیست."
            m.startsWith("HTTP") -> "ارتباط با سرور ناموفق بود ($m)"
            else -> "ارتباط با سرور ناموفق بود. دوباره تلاش کنید."
        }
    }

    private fun getKey(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) return (ks.getKey(alias, null) as java.security.Key)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized private fun writeToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    @Synchronized private fun readToken(): String? {
        return try {
            val iv = prefs.getString("iv", null) ?: return null
            val data = prefs.getString("token", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { clearToken(); null }
    }

    @Synchronized private fun clearToken() { prefs.edit().clear().apply() }

    private fun emitCallback(name: String, ok: Boolean, payload: JSONObject) {
        val script = "window.$name && window.$name($ok, JSON.parse(${JSONObject.quote(payload.toString())}))"
        activity.runOnUiThread { emit(script) }
    }

    fun dispose() { executor.shutdownNow() }
}
