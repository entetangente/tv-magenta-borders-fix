package de.edgefix.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/** ADB-Schlüssel: privater Schlüssel und öffentlicher Schlüssel im ADB-Textformat. */
class AdbKey(val privateKey: PrivateKey, val publicKeyText: String) {
    /** „<base64> <name>“ mit abschließendem Nullbyte, wie adbd es erwartet. */
    val publicKeyPayload: ByteArray get() = publicKeyText.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    /** Signatur des Tokens wie bei adb: RSA PKCS#1 v1.5 über einen SHA-1-„Digest“ (das Token selbst). */
    fun sign(token: ByteArray): ByteArray {
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(privateKey)
        s.update(SHA1_DIGEST_INFO)
        s.update(token)
        return s.sign()
    }

    companion object {
        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}

/**
 * Eigener ADB-Schlüssel der App, gespeichert im App-Verzeichnis. Ohne
 * Android-Abhängigkeiten, damit er auch in JVM-Tests nutzbar ist.
 */
object AdbKeys {
    private const val KEY_BITS = 2048
    private const val KEY_NAME = "edgefix@firetv"

    fun loadOrCreate(dir: File): AdbKey {
        val privFile = File(dir, "adbkey.pk8")
        val pubFile = File(dir, "adbkey.pub")
        if (!privFile.exists() || !pubFile.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeText(encodePublicKey(kp.public as RSAPublicKey))
        }
        val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
        return AdbKey(priv, pubFile.readText().trim())
    }

    /** Öffentlicher Schlüssel im ADB-Format (wie AOSP android_pubkey_encode). */
    fun encodePublicKey(key: RSAPublicKey): String = base64(publicKeyStruct(key)) + " " + KEY_NAME

    /** Binärstruktur RSAPublicKey (Little Endian), noch ohne Base64. */
    fun publicKeyStruct(key: RSAPublicKey): ByteArray {
        val words = KEY_BITS / 32
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
        val buf = ByteBuffer.allocate(4 + 4 + KEY_BITS / 8 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv)
        buf.put(littleEndian(n, KEY_BITS / 8))
        buf.put(littleEndian(rr, KEY_BITS / 8))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }

    private fun littleEndian(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray() // Big-Endian, evtl. mit führendem Nullbyte
        val out = ByteArray(len)
        for (i in 0 until len) {
            val src = be.size - 1 - i
            out[i] = if (src >= 0) be[src] else 0
        }
        return out
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Base64 ohne Zeilenumbrüche (java.util.Base64 gibt es erst ab Android 8). */
    fun base64(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) B64[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}
