package com.vibeiptv.app.data.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.reflect.TypeToken
import com.vibeiptv.app.data.model.PortalConfig
import com.vibeiptv.app.util.Json

/**
 * Portal storage. Sensitive values (portals, active id, PIN, locked categories)
 * live in EncryptedSharedPreferences ("vibe_secure", MasterKey AES256_GCM).
 * Player settings live in plain prefs ("vibe_prefs").
 */
class PortalStore(ctx: Context) {

    private val appCtx = ctx.applicationContext

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appCtx,
            "vibe_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val prefs: SharedPreferences by lazy {
        appCtx.getSharedPreferences("vibe_prefs", Context.MODE_PRIVATE)
    }

    private val portalListType = object : TypeToken<List<PortalConfig>>() {}.type

    fun getPortals(): List<PortalConfig> {
        val json = secure.getString("portals_json", null) ?: return emptyList()
        return try {
            Json.gson.fromJson<List<PortalConfig>>(json, portalListType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Upserts by [PortalConfig.id]. */
    fun savePortal(p: PortalConfig) {
        val list = getPortals().toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        secure.edit().putString("portals_json", Json.gson.toJson(list)).apply()
    }

    fun deletePortal(id: String) {
        val list = getPortals().filter { it.id != id }
        secure.edit().putString("portals_json", Json.gson.toJson(list)).apply()
        if (secure.getString("active_id", null) == id) {
            secure.edit().remove("active_id").apply()
        }
    }

    fun getActivePortal(): PortalConfig? {
        val id = secure.getString("active_id", null) ?: return null
        return getPortals().firstOrNull { it.id == id }
    }

    fun setActivePortal(id: String) {
        secure.edit().putString("active_id", id).apply()
    }

    var decoderMode: String
        get() = prefs.getString("decoder_mode", "auto") ?: "auto"
        set(v) { prefs.edit().putString("decoder_mode", v).apply() }

    var bufferMs: Int
        get() = prefs.getInt("buffer_ms", 30000)
        set(v) { prefs.edit().putInt("buffer_ms", v).apply() }

    fun getPin(): String? = secure.getString("pin", null)?.ifBlank { null }

    /** Pass null to clear the PIN. */
    fun setPin(pin: String?) {
        val e = secure.edit()
        if (pin.isNullOrBlank()) e.remove("pin") else e.putString("pin", pin)
        e.apply()
    }

    fun getLockedCategoryIds(): Set<String> {
        val raw = secure.getString("locked_cats", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setLockedCategoryIds(ids: Set<String>) {
        secure.edit().putString("locked_cats", ids.joinToString(",")).apply()
    }
}
