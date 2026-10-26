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
 * AI key storage (r25, provider-aware r40): each provider's API key is encrypted with
 * an AndroidKeyStore AES/GCM key and held as ciphertext in a private prefs file. This
 * is the spec's "or equivalent Keystore wrapping" branch, chosen deliberately over
 * AndroidX EncryptedSharedPreferences: the module declares a no-third-party-
 * dependencies policy (see gearslip/build.gradle.kts), and the Keystore wrapping below
 * is exactly what EncryptedSharedPreferences does with its own master key — same
 * primitive, same hardware backing where the device offers it, zero new dependencies.
 *
 * Provider rules (r40): Groq is the default provider and Gemini is kept as an
 * experimental option. Each provider has its OWN key slot — the Gemini slot's storage
 * is unchanged from r25, and the Groq slot reuses the same pattern. Migration: a user
 * who already has a stored Gemini key and no saved provider stays on Gemini until
 * they switch; a fresh install (no key anywhere) defaults to Groq. Switching providers
 * never moves or copies a key, and a provider without a stored key is inert until a
 * key is added, exactly as the single-provider behavior was. The on/off switch is
 * global; the privacy-notice acknowledgement is per provider, because the wordings
 * differ materially (Groq no-training vs unpaid-Gemini trains).
 *
 * What never happens, by construction: a key is never written in plain text (the
 * prefs file holds only Base64 ciphertext + IV), never logged (the only log lines here
 * name the outcome, never a value), never put in a launch trace or crash report (see
 * [AiAssistant.AiRedaction], applied by [app.autospace.GearslipLog] and the crash
 * reporter's tail filter), and never returned to any UI — callers see only status
 * ([KeyState]) or the key itself handed straight to a client, which sends it only in
 * the request header.
 */
object AiKeyStore {

    /** What the settings UI may show — never the key, never its length, never a masked form. */
    enum class KeyState { NO_KEY, SAVED_UNTESTED, VALID, INVALID }

    private const val PREFS = "autospace_ai"
    private const val KEY_ENABLED = "ai_enabled"
    private const val KEY_PROVIDER = "ai_provider"
    private const val KEY_NOTICE_ACKED = "ai_notice_acked"
    private const val KEY_NOTICE_ACKED_GROQ = "ai_notice_acked_groq"
    private const val KEY_CIPHERTEXT = "ai_key_ciphertext"
    private const val KEY_IV = "ai_key_iv"
    private const val KEY_STATE = "ai_key_state"
    private const val KEY_GROQ_CIPHERTEXT = "groq_key_ciphertext"
    private const val KEY_GROQ_IV = "groq_key_iv"
    private const val KEY_GROQ_STATE = "groq_key_state"
    private const val KEY_ALIAS = "autospace_ai_key_v1"
    private const val GCM_TAG_BITS = 128

    private val enabledFlow = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    private val providerFlow = MutableStateFlow(AiAssistant.Provider.DEFAULT)
    val provider: StateFlow<AiAssistant.Provider> = providerFlow.asStateFlow()

    private val geminiKeyStateFlow = MutableStateFlow(KeyState.NO_KEY)
    private val groqKeyStateFlow = MutableStateFlow(KeyState.NO_KEY)
    // Stable wrappers: keyState() must return the same flow object on every call,
    // or Compose collectors would restart on each recomposition.
    private val geminiKeyState = geminiKeyStateFlow.asStateFlow()
    private val groqKeyState = groqKeyStateFlow.asStateFlow()

    private var loaded = false

    /** Reads the stored flags once (later saves update the flows directly). Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabledFlow.value = prefs.getBoolean(KEY_ENABLED, false)
        // Migration (r40): no saved provider means a pre-r40 install. A stored Gemini
        // key keeps that user on Gemini; everyone else defaults to Groq. Persisted, so
        // the migration runs once and can never overwrite a later explicit switch.
        val savedProvider = prefs.getString(KEY_PROVIDER, null)
            ?.let { name -> AiAssistant.Provider.entries.firstOrNull { it.name == name } }
        providerFlow.value = savedProvider ?: run {
            val migrated = if (!prefs.getString(KEY_CIPHERTEXT, null).isNullOrEmpty()) {
                AiAssistant.Provider.GEMINI
            } else {
                AiAssistant.Provider.DEFAULT
            }
            prefs.edit().putString(KEY_PROVIDER, migrated.name).apply()
            migrated
        }
        geminiKeyStateFlow.value = storedState(prefs, KEY_CIPHERTEXT, KEY_STATE)
        groqKeyStateFlow.value = storedState(prefs, KEY_GROQ_CIPHERTEXT, KEY_GROQ_STATE)
    }

    private fun storedState(
        prefs: android.content.SharedPreferences,
        ciphertextKey: String,
        stateKey: String,
    ): KeyState {
        if (prefs.getString(ciphertextKey, null).isNullOrEmpty()) return KeyState.NO_KEY
        return when (prefs.getString(stateKey, null)) {
            "VALID" -> KeyState.VALID
            "INVALID" -> KeyState.INVALID
            else -> KeyState.SAVED_UNTESTED
        }
    }

    /** The active provider: the saved choice, or the migration result on first load. */
    fun activeProvider(context: Context): AiAssistant.Provider {
        ensureLoaded(context)
        return providerFlow.value
    }

    /** Switches provider. Keys are per provider and never move; a keyless provider is inert until a key is added. */
    fun setProvider(context: Context, provider: AiAssistant.Provider) {
        ensureLoaded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROVIDER, provider.name).apply()
        providerFlow.value = provider
    }

    /** Key status for one provider — the flow the settings UI collects. */
    fun keyState(provider: AiAssistant.Provider): StateFlow<KeyState> = when (provider) {
        AiAssistant.Provider.GROQ -> groqKeyState
        AiAssistant.Provider.GEMINI -> geminiKeyState
    }

    /** The privacy notice (§6) has been shown and acknowledged for this provider at least once. */
    fun noticeAcked(context: Context, provider: AiAssistant.Provider): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (provider == AiAssistant.Provider.GROQ) KEY_NOTICE_ACKED_GROQ else KEY_NOTICE_ACKED
        return prefs.getBoolean(key, false)
    }

    fun ackNotice(context: Context, provider: AiAssistant.Provider) {
        val key = if (provider == AiAssistant.Provider.GROQ) KEY_NOTICE_ACKED_GROQ else KEY_NOTICE_ACKED
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, true).apply()
    }

    fun setEnabled(context: Context, on: Boolean) {
        ensureLoaded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        enabledFlow.value = on
    }

    /** True only when the feature is on AND the active provider has a key stored (validity is a separate question). */
    fun hasKey(context: Context, provider: AiAssistant.Provider): Boolean {
        ensureLoaded(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (provider == AiAssistant.Provider.GROQ) KEY_GROQ_CIPHERTEXT else KEY_CIPHERTEXT
        return !prefs.getString(key, null).isNullOrEmpty()
    }

    /** The gate the AI fallback checks: on + a key stored for the active provider. Network is checked separately, per turn. */
    fun aiAvailable(context: Context): Boolean {
        ensureLoaded(context)
        return enabledFlow.value && hasKey(context, providerFlow.value)
    }

    /** Saves (and encrypts) a key for one provider. Blank input clears nothing — call [removeKey] to wipe. */
    fun saveKey(context: Context, provider: AiAssistant.Provider, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val (ciphertextKey, ivKey, stateKey, flow) = slot(provider)
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ciphertextKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(ivKey, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(stateKey, "SAVED_UNTESTED")
                .apply()
            flow.value = KeyState.SAVED_UNTESTED
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The decrypted key for one provider, or null when none is stored or decryption fails. Never log the result. */
    fun loadKey(context: Context, provider: AiAssistant.Provider): String? {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val (ciphertextKey, ivKey, _, _) = slot(provider)
            val ciphertext = prefs.getString(ciphertextKey, null) ?: return null
            val iv = prefs.getString(ivKey, null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            val plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8).ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Wipes one provider's stored key. The Keystore key itself is left alone. When the
     * wiped key belongs to the active provider, the feature turns off with it — the
     * old single-key behavior; wiping an inactive provider's key leaves the switch alone.
     */
    fun removeKey(context: Context, provider: AiAssistant.Provider) {
        ensureLoaded(context)
        val (ciphertextKey, ivKey, stateKey, flow) = slot(provider)
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(ciphertextKey).remove(ivKey).putString(stateKey, "NO_KEY")
        if (providerFlow.value == provider) {
            editor.putBoolean(KEY_ENABLED, false)
            enabledFlow.value = false
        }
        editor.apply()
        flow.value = KeyState.NO_KEY
    }

    /**
     * Runs the no-generation-cost validation for one provider and records the outcome.
     * Returns the verdict. The verdict type is shared across providers (VALID / INVALID /
     * UNKNOWN) even though it is declared on [GeminiClient] — the values are
     * provider-neutral, and a second enum would only invite mismatched mappings.
     */
    fun testKey(context: Context, provider: AiAssistant.Provider): GeminiClient.KeyCheck {
        val key = loadKey(context, provider) ?: return GeminiClient.KeyCheck.INVALID
        val verdict = when (provider) {
            AiAssistant.Provider.GROQ -> when (GroqClient.validateKey(key)) {
                GroqClient.KeyCheck.VALID -> GeminiClient.KeyCheck.VALID
                GroqClient.KeyCheck.INVALID -> GeminiClient.KeyCheck.INVALID
                GroqClient.KeyCheck.UNKNOWN -> GeminiClient.KeyCheck.UNKNOWN
            }
            AiAssistant.Provider.GEMINI -> GeminiClient.validateKey(key)
        }
        val (_, _, stateKey, flow) = slot(provider)
        val state = when (verdict) {
            GeminiClient.KeyCheck.VALID -> KeyState.VALID
            GeminiClient.KeyCheck.INVALID -> KeyState.INVALID
            GeminiClient.KeyCheck.UNKNOWN -> flow.value
        }
        if (state != flow.value) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(stateKey, state.name).apply()
            flow.value = state
        }
        return verdict
    }

    private data class Slot(
        val ciphertextKey: String,
        val ivKey: String,
        val stateKey: String,
        val flow: MutableStateFlow<KeyState>,
    )

    private fun slot(provider: AiAssistant.Provider): Slot = when (provider) {
        AiAssistant.Provider.GROQ -> Slot(KEY_GROQ_CIPHERTEXT, KEY_GROQ_IV, KEY_GROQ_STATE, groqKeyStateFlow)
        AiAssistant.Provider.GEMINI -> Slot(KEY_CIPHERTEXT, KEY_IV, KEY_STATE, geminiKeyStateFlow)
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
