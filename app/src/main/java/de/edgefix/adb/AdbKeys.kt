package de.edgefix.adb

import android.util.Base64
import dadb.AdbKeyPair
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Eigener ADB-Schlüssel der App. dadb erzeugt und liest Schlüssel mit
 * java.util.Base64, das es erst ab Android 8 gibt. Fire OS 6 basiert auf
 * Android 7.1, deshalb übernimmt diese Klasse das mit android.util.Base64.
 */
object AdbKeys {
    private const val KEY_BITS = 2048
    private const val KEY_NAME = "edgefix@firetv"

    fun loadOrCreate(dir: File): AdbKeyPair {
        val privFile = File(dir, "adbkey.pk8")
        val pubFile = File(dir, "adbkey.pub")
        if (!privFile.exists() || !pubFile.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeText(encodePublicKey(kp.public as RSAPublicKey))
        }
        val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
        // ADB erwartet den Text „<base64> <name>“ mit abschließendem Nullbyte.
        val pub = pubFile.readBytes() + byteArrayOf(0)
        return AdbKeyPair(priv, pub)
    }

    /** Öffentlicher Schlüssel im ADB-Format (wie AOSP android_pubkey_encode). */
    fun encodePublicKey(key: RSAPublicKey): String =
        Base64.encodeToString(publicKeyStruct(key), Base64.NO_WRAP) + " " + KEY_NAME

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
}
