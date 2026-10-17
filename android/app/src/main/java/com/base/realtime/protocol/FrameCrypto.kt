package com.base.realtime.protocol

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object FrameCrypto {
    private const val TAG = "FrameCrypto"
    private const val ALG = "aead"
    private const val NONCE_N = 12
    private const val KEY_N = 32
    private const val TAG_BITS = 128
    private val AAD = "realtime-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun parseKey(raw: String): ByteArray? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length == 64 && trimmed.all { it in "0123456789abcdefABCDEF" }) {
            val key = hexDecode(trimmed)
            if (key.size == KEY_N) return key
        }
        for (flags in intArrayOf(
            Base64.NO_WRAP,
            Base64.DEFAULT,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            Base64.NO_WRAP or Base64.NO_PADDING,
        )) {
            try {
                val key = Base64.decode(trimmed, flags)
                if (key.size == KEY_N) return key
            } catch (_: Throwable) {
            }
        }
        Log.w(TAG, "frame key parse failed")
        return null
    }

    fun seal(key: ByteArray?, plaintext: String): String {
        if (key == null || key.isEmpty()) return plaintext
        require(key.size == KEY_N) { "bad frame key length" }
        val nonce = ByteArray(NONCE_N)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("v", Protocol.VERSION)
            .put("alg", ALG)
            .put("n", b64(nonce))
            .put("c", b64(sealed))
            .toString()
    }

    fun open(key: ByteArray?, text: String): String {
        val wire = try {
            JSONObject(text)
        } catch (_: Throwable) {
            if (key == null || key.isEmpty()) return text
            throw IllegalArgumentException("encrypted frame required")
        }
        val alg = wire.optString("alg", "")
        if (alg == ALG) {
            if (key == null || key.isEmpty()) throw IllegalArgumentException("frame key missing")
            if (wire.optInt("v", 0) != Protocol.VERSION) throw IllegalArgumentException("bad wire version")
            val nonce = b64d(wire.getString("n"))
            if (nonce.size != NONCE_N) throw IllegalArgumentException("bad nonce")
            val ciphertext = b64d(wire.getString("c"))
            if (ciphertext.isEmpty()) throw IllegalArgumentException("bad ciphertext")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(AAD)
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
        if (key != null && key.isNotEmpty()) throw IllegalArgumentException("encrypted frame required")
        return text
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64d(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            else -> value
        }
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun hexDecode(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((hexDigit(hex[i]) shl 4) or hexDigit(hex[i + 1])).toByte()
            i += 2
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex")
    }
}
