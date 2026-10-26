package app.autospace.car

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI key storage (r25): the user's Gemini API key, encrypted with an AndroidKeyStore AES/GCM
 * key and held as ciphertext in a private prefs file. This is the spec's "or equivalent
 * Keystore wrapping" branch, chosen deliberately over AndroidX EncryptedSharedPreferences:
 * the module declares a no-third-party-dependencies policy (see gearslip/build.gradle.kts),
 * and the Keystore wrapping below is exactly what EncryptedSharedPreferences does with its
 * own master key — same primitive, same hardware backing where the device offers it, zero
 * new dependencies.
 *
 * What never happens, by construction: the key is never written in plain text (the prefs
 * file holds only Base64 ciphertext + IV), never logged (the only log lines here name the
 * outcome, never a value), never put in a launch trace or crash report (see
 * [AiAssistant.AiRedaction], applied by [app.autospace.GearslipLog] and the crash
 * reporter's tail filter), and never returned to any UI — callers see only status
 * ([KeyState]) or the key itself handed straight to [GeminiClient], which sends it only
 * in the request header.
 */
object AiKeyStore {

    /** What the settings UI may show — never the key, never its length, never a masked form. */
    enum class KeyState { NO_KEY, SAVED_UNTESTED, VALID, INVALID }

    private const val PREFS = "autospace_ai"
    private const val KEY_ENABLED = "ai_enabled"
    private const val KEY_NOTICE_ACKED = "ai_notice_acked"
    private const val KEY_CIPHERTEXT = "ai_key_ciphertext"
    private const val KEY_IV = "ai_key_iv"
    private const val KEY_STATE = "ai_key_state"
    private const val KEY_ALIAS = "autospace_ai_key_v1"
    private const val GCM_TAG_BITS = 128

    private val enabledFlow = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    private val keyStateFlow = MutableStateFlow(KeyState.NO_KEY)
    val keyState: StateFlow<KeyState> = keyStateFlow.asStateFlow()

    private var loaded = false

    /** Reads the stored flags once (later saves update the flows directly). Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabledFlow.value = prefs.getBoolean(KEY_ENABLED, false)
        keyStateFlow.value = when {
            prefs.getString(KEY_CIPHERTEXT, null).isNullOrEmpty() -> KeyState.NO_KEY
            else -> when (prefs.getString(KEY_STATE, null)) {
                "VALID" -> KeyState.VALID
                "INVALID" -> KeyState.INVALID
                else -> KeyState.SAVED_UNTESTED
            }
        }
    }

    /** The privacy notice (§6) has been shown and acknowledged at least once. */
    fun noticeAcked(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTICE_ACKED, false)
    }

    fun ackNotice(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTICE_ACKED, true).apply()
    }

    fun setEnabled(context: Context, on: Boolean) {
        ensureLoaded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        enabledFlow.value = on
    }

    /** True only when the feature is on AND a key is stored (validity is a separate question). */
    fun hasKey(context: Context): Boolean {
        ensureLoaded(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_CIPHERTEXT, null).isNullOrEmpty()
    }

    /** The gate the AI fallback checks: on + key stored. Network is checked separately, per turn. */
    fun aiAvailable(context: Context): Boolean {
        ensureLoaded(context)
        return enabledFlow.value && hasKey(context)
    }

    /** Saves (and encrypts) a key. Blank input clears nothing — call [removeKey] to wipe. */
    fun saveKey(context: Context, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_STATE, "SAVED_UNTESTED")
                .apply()
            keyStateFlow.value = KeyState.SAVED_UNTESTED
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The decrypted key, or null when none is stored or decryption fails. Never log the result. */
    fun loadKey(context: Context): String? {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
            val iv = prefs.getString(KEY_IV, null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            val plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8).ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Wipes the stored key and turns the feature off. The Keystore key itself is left alone. */
    fun removeKey(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_CIPHERTEXT).remove(KEY_IV).putString(KEY_STATE, "NO_KEY")
            .putBoolean(KEY_ENABLED, false).apply()
        keyStateFlow.value = KeyState.NO_KEY
        enabledFlow.value = false
    }

    /** Runs the no-generation-cost validation and records the outcome. Returns the verdict. */
    fun testKey(context: Context): GeminiClient.KeyCheck {
        val key = loadKey(context) ?: return GeminiClient.KeyCheck.INVALID
        val verdict = GeminiClient.validateKey(key)
        val state = when (verdict) {
            GeminiClient.KeyCheck.VALID -> KeyState.VALID
            GeminiClient.KeyCheck.INVALID -> KeyState.INVALID
            GeminiClient.KeyCheck.UNKNOWN -> keyStateFlow.value
        }
        if (state != keyStateFlow.value) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_STATE, state.name).apply()
            keyStateFlow.value = state
        }
        return verdict
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
