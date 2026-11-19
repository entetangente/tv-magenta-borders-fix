package de.edgefix.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimaler ADB-Client (Verbindung, Anmeldung, Shell-Befehl) mit
 * ausführlichem Protokoll jedes Schritts. Ersetzt dadb, damit sich auf dem
 * Fernseher genau ablesen lässt, an welcher Stelle eine Verbindung scheitert.
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    private val log: (String) -> Unit,
) : Closeable {

    class AdbException(message: String) : IOException(message)

    private data class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray) {
        override fun toString() = "${name(command)}($arg0, $arg1, ${data.size} B)"
    }

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var maxData = 4096

    /** Verbindet und meldet sich an. Wartet bis [authTimeoutMs] auf die Bestätigung am Gerät. */
    fun connect(connectTimeoutMs: Int = 5000, authTimeoutMs: Int = 60_000): String {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = 10_000
        input = DataInputStream(s.getInputStream().buffered())
        output = s.getOutputStream()
        log("TCP verbunden mit $host:$port")

        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
        log("→ CNXN gesendet")
        var sentSignature = false
        var sentPublicKey = false
        while (true) {
            val m = try {
                read()
            } catch (e: EOFException) {
                throw AdbException(
                    when {
                        sentPublicKey -> "Verbindung nach Senden des Schlüssels getrennt (Abfrage abgelehnt oder nicht angezeigt)"
                        sentSignature -> "Verbindung nach Senden der Signatur getrennt"
                        else -> "Verbindung direkt nach CNXN getrennt, bevor eine Anmeldung möglich war"
                    },
                )
            }
            log("← $m")
            when (m.command) {
                A_CNXN -> {
                    maxData = m.arg1.coerceAtLeast(4096)
                    val banner = String(m.data).trimEnd('\u0000')
                    log("✓ angemeldet: $banner")
                    s.soTimeout = 30_000
                    return banner
                }
                A_AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) throw AdbException("Unerwartete AUTH-Art ${m.arg0}")
                    if (!sentSignature) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data))
                        sentSignature = true
                        log("→ Signatur gesendet")
                    } else if (!sentPublicKey) {
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload)
                        sentPublicKey = true
                        s.soTimeout = authTimeoutMs
                        log("→ öffentlicher Schlüssel gesendet, warte auf Bestätigung am Fernseher")
                    } else {
                        throw AdbException("Schlüssel wurde nicht akzeptiert")
                    }
                }
                A_STLS -> throw AdbException("Gerät verlangt eine TLS-Verbindung (STLS), das wird nicht unterstützt")
                else -> log("(ignoriert)")
            }
        }
    }

    /** Führt einen Shell-Befehl aus und liefert die Ausgabe. */
    fun shell(command: String): String {
        val localId = 1
        send(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())
        log("→ OPEN shell:$command")
        val out = StringBuilder()
        var remoteId = 0
        while (true) {
            val m = try {
                read()
            } catch (e: EOFException) {
                throw AdbException("Verbindung während des Befehls getrennt")
            }
            when (m.command) {
                A_OKAY -> remoteId = m.arg0
                A_WRTE -> {
                    out.append(String(m.data))
                    send(A_OKAY, localId, m.arg0, ByteArray(0))
                }
                A_CLSE -> {
                    if (remoteId != 0) runCatching { send(A_CLSE, localId, remoteId, ByteArray(0)) }
                    log("← Befehl beendet, Ausgabe: ${out.toString().trim().ifEmpty { "(leer)" }}")
                    return out.toString()
                }
                else -> log("← $m (ignoriert)")
            }
        }
    }

    override fun close() {
        runCatching { socket?.close() }
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command.inv())
        output.write(header.array())
        output.write(data)
        output.flush()
    }

    private fun read(): Message {
        val h = ByteArray(24)
        input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val a0 = b.int
        val a1 = b.int
        val len = b.int
        b.int // Prüfsumme
        val magic = b.int
        if (magic != cmd.inv()) throw AdbException("Ungültige Antwort (magic)")
        if (len < 0 || len > 1024 * 1024) throw AdbException("Ungültige Länge $len")
        val data = ByteArray(len)
        input.readFully(data)
        return Message(cmd, a0, a1, data)
    }

    companion object {
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453
        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        fun name(cmd: Int): String = when (cmd) {
            A_CNXN -> "CNXN"; A_AUTH -> "AUTH"; A_OPEN -> "OPEN"; A_OKAY -> "OKAY"
            A_CLSE -> "CLSE"; A_WRTE -> "WRTE"; A_STLS -> "STLS"; A_SYNC -> "SYNC"
            else -> "0x%08x".format(cmd)
        }
    }
}
