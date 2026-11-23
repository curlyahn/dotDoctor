package com.dot.doctor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Profile(
    /** 출생 연도 (나이 계산용, Claude에는 '○○대 초반'처럼 나이대만 보낸다) */
    val birthYear: Int? = null,
    /** "M" / "F" */
    val sex: String? = null,
    val heightCm: Int? = null,
    /** 가족력 (자유롭게) */
    val familyHistory: String = "",
    /** 지병·알레르기 등 (자유롭게) */
    val conditions: String = "",
)

data class Settings(
    val profile: Profile = Profile(),
    val model: String = DEFAULT_MODEL,
    /** 월요일 아침 '지난주 리포트를 만들 수 있어요' 알림 (보내기는 항상 사용자가 직접) */
    val weeklyReminder: Boolean = true,
    /** Claude에 보내기 전에 ○○로 바꿀 단어 (이름·병원 이름 등) */
    val blockWords: List<String> = emptyList(),
    val hasApiKey: Boolean = false,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val MODELS = listOf(
            "claude-sonnet-5-5" to "Sonnet 5.5 (기본, 빠르고 저렴)",
            "claude-opus-5-5" to "Opus 5.5 (더 깊이 분석)",
        )
    }
}

class Prefs(context: Context) {
    private val ds = context.applicationContext.dataStore

    private object K {
        val birthYear = intPreferencesKey("birth_year")
        val sex = stringPreferencesKey("sex")
        val height = intPreferencesKey("height_cm")
        val family = stringPreferencesKey("family_history")
        val conditions = stringPreferencesKey("conditions")
        val model = stringPreferencesKey("model")
        val weeklyReminder = booleanPreferencesKey("weekly_reminder")
        val blockWords = stringPreferencesKey("block_words")
        val apiKey = stringPreferencesKey("api_key_enc")
    }

    val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            profile = Profile(
                birthYear = p[K.birthYear],
                sex = p[K.sex],
                heightCm = p[K.height],
                familyHistory = p[K.family].orEmpty(),
                conditions = p[K.conditions].orEmpty(),
            ),
            model = p[K.model] ?: Settings.DEFAULT_MODEL,
            weeklyReminder = p[K.weeklyReminder] ?: true,
            blockWords = p[K.blockWords].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            hasApiKey = !p[K.apiKey].isNullOrBlank(),
        )
    }

    suspend fun now(): Settings = settings.first()

    suspend fun setProfile(pr: Profile) = ds.edit { p ->
        if (pr.birthYear != null) p[K.birthYear] = pr.birthYear else p.remove(K.birthYear)
        if (pr.sex != null) p[K.sex] = pr.sex else p.remove(K.sex)
        if (pr.heightCm != null) p[K.height] = pr.heightCm else p.remove(K.height)
        p[K.family] = pr.familyHistory
        p[K.conditions] = pr.conditions
    }

    suspend fun setModel(m: String) = ds.edit { it[K.model] = m }
    suspend fun setWeeklyReminder(on: Boolean) = ds.edit { it[K.weeklyReminder] = on }
    suspend fun setBlockWords(words: List<String>) = ds.edit { it[K.blockWords] = words.joinToString("\n") }

    suspend fun setApiKey(key: String?) = ds.edit { p ->
        if (key.isNullOrBlank()) p.remove(K.apiKey) else p[K.apiKey] = Secret.encrypt(key.trim())
    }

    suspend fun apiKey(): String? = ds.data.first()[K.apiKey]?.let { runCatching { Secret.decrypt(it) }.getOrNull() }
}

/** API 키를 안드로이드 키스토어(하드웨어 보안 영역) 키로 암호화해 둔다 */
private object Secret {
    private const val ALIAS = "dotdoctor_api_key"
    private const val T = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun encrypt(s: String): String {
        val c = Cipher.getInstance(T)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(s.toByteArray(Charsets.UTF_8))
        return b64(c.iv) + ":" + b64(ct)
    }

    fun decrypt(s: String): String {
        val (iv, ct) = s.split(":").let { unb64(it[0]) to unb64(it[1]) }
        val c = Cipher.getInstance(T)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
