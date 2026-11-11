package com.eternallite.app.crypto

import com.goterl.lazysodium.LazySodium
import java.util.Base64

/**
 * The libsodium operations named in docs/03-protocol.md, and nothing else.
 * Open functions return null on failure instead of throwing.
 */
interface Sodium {
    fun signKeypair(): KeyPair
    fun boxKeypair(): KeyPair

    /** crypto_sign, combined mode: 64-byte signature followed by the message. */
    fun sign(message: ByteArray, signSk: ByteArray): ByteArray

    /** crypto_sign_open. Returns the message, or null if the signature is invalid. */
    fun signOpen(signed: ByteArray, signPk: ByteArray): ByteArray?

    /** crypto_box_seal. */
    fun seal(message: ByteArray, boxPk: ByteArray): ByteArray

    /** crypto_box_seal_open. Returns the plaintext, or null if it cannot be opened. */
    fun sealOpen(sealed: ByteArray, boxPk: ByteArray, boxSk: ByteArray): ByteArray?

    class KeyPair(val pk: ByteArray, val sk: ByteArray)
}

/**
 * Sodium on top of Lazysodium's native API. The app passes LazySodiumAndroid,
 * JVM unit tests pass LazySodiumJava; both extend LazySodium.
 */
class LazySodiumOps(private val ls: LazySodium) : Sodium {

    override fun signKeypair(): Sodium.KeyPair {
        val pk = ByteArray(SIGN_PK_BYTES)
        val sk = ByteArray(SIGN_SK_BYTES)
        check(ls.cryptoSignKeypair(pk, sk)) { "crypto_sign_keypair failed" }
        return Sodium.KeyPair(pk, sk)
    }

    override fun boxKeypair(): Sodium.KeyPair {
        val pk = ByteArray(BOX_KEY_BYTES)
        val sk = ByteArray(BOX_KEY_BYTES)
        check(ls.cryptoBoxKeypair(pk, sk)) { "crypto_box_keypair failed" }
        return Sodium.KeyPair(pk, sk)
    }

    override fun sign(message: ByteArray, signSk: ByteArray): ByteArray {
        require(signSk.size == SIGN_SK_BYTES) { "bad signing key length" }
        val out = ByteArray(SIGN_BYTES + message.size)
        check(ls.cryptoSign(out, message, message.size.toLong(), signSk)) { "crypto_sign failed" }
        return out
    }

    override fun signOpen(signed: ByteArray, signPk: ByteArray): ByteArray? {
        if (signed.size < SIGN_BYTES || signPk.size != SIGN_PK_BYTES) return null
        val out = ByteArray(signed.size - SIGN_BYTES)
        return if (ls.cryptoSignOpen(out, signed, signed.size.toLong(), signPk)) out else null
    }

    override fun seal(message: ByteArray, boxPk: ByteArray): ByteArray {
        require(boxPk.size == BOX_KEY_BYTES) { "bad box key length" }
        val out = ByteArray(SEAL_BYTES + message.size)
        check(ls.cryptoBoxSeal(out, message, message.size.toLong(), boxPk)) { "crypto_box_seal failed" }
        return out
    }

    override fun sealOpen(sealed: ByteArray, boxPk: ByteArray, boxSk: ByteArray): ByteArray? {
        if (sealed.size < SEAL_BYTES || boxPk.size != BOX_KEY_BYTES || boxSk.size != BOX_KEY_BYTES) return null
        val out = ByteArray(sealed.size - SEAL_BYTES)
        return if (ls.cryptoBoxSealOpen(out, sealed, sealed.size.toLong(), boxPk, boxSk)) out else null
    }

    companion object {
        const val SIGN_BYTES = 64
        const val SIGN_PK_BYTES = 32
        const val SIGN_SK_BYTES = 64
        const val BOX_KEY_BYTES = 32
        const val SEAL_BYTES = 48
    }
}

/** Standard base64 with padding (RFC 4648), no line wraps. Same as Node's Buffer base64. */
object B64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Returns null if the text is not valid base64. */
    fun decode(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
