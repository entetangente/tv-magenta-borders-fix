package de.edgefix.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class AdbKeysTest {
    /** Eigene Kodierung muss byte-genau der von dadb entsprechen. */
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
}
