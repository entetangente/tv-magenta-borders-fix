package de.edgefix.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class AdbKeysTest {
    /** Eigene Kodierung muss byte-genau der von dadb (bewährte Implementierung) entsprechen. */
    @Test
    fun publicKeyMatchesDadbEncoding() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val companion = Class.forName("dadb.AdbKeyPair\$Companion")
        val m = companion.getDeclaredMethod("convertRsaPublicKeyToAdbFormat", RSAPublicKey::class.java)
        m.isAccessible = true
        val instance = Class.forName("dadb.AdbKeyPair").getDeclaredField("Companion").get(null)
        val expected = m.invoke(instance, pub) as ByteArray
        assertArrayEquals(expected, AdbKeys.publicKeyStruct(pub))
    }

    @Test
    fun base64MatchesJdk() {
        for (n in 0..10) {
            val data = ByteArray(n) { (it * 37 + 5).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(data), AdbKeys.base64(data))
        }
    }

    /** Signatur muss der von dadb entsprechen (gleicher Schlüssel, gleiches Token). */
    @Test
    fun signatureMatchesDadb() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val token = ByteArray(20) { it.toByte() }
        val ours = AdbKey(kp.private, "x").sign(token)
        val dadbKey = dadb.AdbKeyPair(kp.private, ByteArray(1))
        val msgClass = Class.forName("dadb.AdbMessage")
        val ctor = msgClass.declaredConstructors.first { it.parameterCount == 7 }
        ctor.isAccessible = true
        val msg = ctor.newInstance(0x48545541, 1, 0, token.size, 0, 0, token)
        val sign = dadb.AdbKeyPair::class.java.declaredMethods.first { it.name.startsWith("signPayload") }
        sign.isAccessible = true
        val theirs = sign.invoke(dadbKey, msg) as ByteArray
        assertArrayEquals(theirs, ours)
    }
}
