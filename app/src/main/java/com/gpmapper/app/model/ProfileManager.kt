package com.gpmapper.app.model

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProfileManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gpmapper_profiles", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveProfile(profile: MappingProfile) {
        val json = gson.toJson(profile)
        prefs.edit().putString("profile_${profile.id}", json).apply()
    }

    fun loadProfile(profileId: String): MappingProfile? {
        val json = prefs.getString("profile_$profileId", null) ?: return null
        return try {
            gson.fromJson(json, MappingProfile::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteProfile(profileId: String) {
        prefs.edit().remove("profile_$profileId").apply()
    }

    fun getAllProfiles(): List<MappingProfile> {
        val profiles = mutableListOf<MappingProfile>()
        val allKeys = prefs.all.keys.filter { it.startsWith("profile_") }
        for (key in allKeys) {
            val json = prefs.getString(key, null) ?: continue
            try {
                val profile = gson.fromJson(json, MappingProfile::class.java)
                profiles.add(profile)
            } catch (_: Exception) {}
        }
        return profiles
    }

    fun setActiveProfile(profileId: String) {
        prefs.edit().putString("active_profile_id", profileId).apply()
    }

    fun getActiveProfileId(): String? {
        return prefs.getString("active_profile_id", null)
    }

    fun getProfileForPackage(packageName: String): MappingProfile? {
        val allProfiles = getAllProfiles()
        return allProfiles.find { it.packageName == packageName }
            ?: allProfiles.firstOrNull()
    }

    fun saveDefaultProfiles() {
        saveProfile(MappingProfile.createDefault())
        saveProfile(MappingProfile.createMobaDefault())
    }

    fun setAutoDetectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_detect", enabled).apply()
    }

    fun isAutoDetectEnabled(): Boolean {
        return prefs.getBoolean("auto_detect", true)
    }
}
