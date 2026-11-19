package de.edgefix.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import kotlin.concurrent.thread

/**
 * Prüft den ADB-Client gegen einen nachgebauten adbd: Token-Anmeldung,
 * Schlüssel-Übergabe beim ersten Mal, Signatur beim zweiten Mal, Shell-Befehl.
 */
class AdbClientTest {

    private class FakeAdbd {
        val server = ServerSocket(0)
        val port get() = server.localPort
        var knownKey: RSAPublicKey? = null
        val commands = mutableListOf<String>()

        fun serveOnce(): Thread = thread {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun read(): Triple<Int, IntArray, ByteArray> {
                    val h = ByteArray(24); input.readFully(h)
                    val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                    val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int; b.int; val magic = b.int
                    check(magic == cmd.inv())
                    val d = ByteArray(len); input.readFully(d)
                    return Triple(cmd, intArrayOf(a0, a1), d)
                }
                fun send(o: OutputStream, cmd: Int, a0: Int, a1: Int, d: ByteArray) {
                    val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    h.putInt(cmd).putInt(a0).putInt(a1).putInt(d.size).putInt(d.sumOf { it.toInt() and 0xff }).putInt(cmd.inv())
                    o.write(h.array()); o.write(d); o.flush()
                }
                val (c0, _, banner) = read()
                check(c0 == AdbClient.A_CNXN && String(banner).startsWith("host::"))
                val token = ByteArray(20) { (it * 7).toByte() }
                send(out, AdbClient.A_AUTH, 1, 0, token)
                val (c1, a1, sig) = read()
                check(c1 == AdbClient.A_AUTH && a1[0] == 2)
                val ok = knownKey?.let { k ->
                    Signature.getInstance("NONEwithRSA").run {
                        initVerify(k); update(AdbKey.SHA1_DIGEST_INFO); update(token); verify(sig)
                    }
                } ?: false
                if (!ok) {
                    send(out, AdbClient.A_AUTH, 1, 0, token)
                    val (c2, a2, pub) = read()
                    check(c2 == AdbClient.A_AUTH && a2[0] == 3 && pub.last() == 0.toByte())
                    knownKey = parseAdbPublicKey(String(pub, 0, pub.size - 1))
                }
                send(out, AdbClient.A_CNXN, 0x01000001, 4096, "device::ro.product.name=fake;".toByteArray())
                val (c3, a3, open) = read()
                check(c3 == AdbClient.A_OPEN)
                commands += String(open).trimEnd('\u0000')
                send(out, AdbClient.A_OKAY, 9, a3[0], ByteArray(0))
                send(out, AdbClient.A_WRTE, 9, a3[0], ByteArray(0))
                read() // OKAY
                send(out, AdbClient.A_CLSE, 9, a3[0], ByteArray(0))
                runCatching { read() }
            }
        }

        /** Liest das ADB-Textformat zurück (wie adbd) und baut den RSA-Schlüssel. */
        fun parseAdbPublicKey(text: String): RSAPublicKey {
            val raw = java.util.Base64.getDecoder().decode(text.substringBefore(' '))
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val words = b.int; b.int
            val n = ByteArray(words * 4); b.get(n); b.position(b.position() + words * 4)
            val e = b.int
            val modulus = BigInteger(1, n.reversedArray())
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(e.toLong()))) as RSAPublicKey
        }
    }

    @Test
    fun firstConnectSendsKeyThenSecondUsesSignature() {
        val dir = createTempDir()
        val key = AdbKeys.loadOrCreate(dir)
        val adbd = FakeAdbd()
        val log = mutableListOf<String>()

        val t1 = adbd.serveOnce()
        AdbClient("127.0.0.1", adbd.port, key) { log += it }.use { c ->
            assertEquals("device::ro.product.name=fake;", c.connect())
            c.shell("appops set de.edgefix SYSTEM_ALERT_WINDOW allow")
        }
        t1.join(5000)
        assertTrue(log.any { it.startsWith("→ öffentlicher Schlüssel") })
        assertEquals("shell:appops set de.edgefix SYSTEM_ALERT_WINDOW allow", adbd.commands.single())

        // Zweite Verbindung: Schlüssel bekannt, Signatur muss reichen
        log.clear()
        val t2 = adbd.serveOnce()
        AdbClient("127.0.0.1", adbd.port, AdbKeys.loadOrCreate(dir)) { log += it }.use { c ->
            c.connect()
            c.shell("true")
        }
        t2.join(5000)
        assertTrue(log.none { it.startsWith("→ öffentlicher Schlüssel") })
        File(dir.path).deleteRecursively()
    }

    @Test
    fun reportsWhereTheConnectionDropped() {
        val server = ServerSocket(0)
        thread { server.accept().use { s -> s.getInputStream().read(ByteArray(24)) } } // schließt nach CNXN
        val key = AdbKeys.loadOrCreate(createTempDir())
        val e = runCatching { AdbClient("127.0.0.1", server.localPort, key) {}.use { it.connect() } }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("direkt nach CNXN") == true)
    }
}
