package com.launchertv.home

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Cliente ADB mínimo contra la depuración por red de la propia TV (127.0.0.1:5555).
 *
 * Permite a la app ejecutar comandos de shell con permisos de ADB (activar o desactivar el
 * launcher de fábrica), algo que una app normal no puede hacer. La primera conexión hace
 * que la TV pregunte si se permite la depuración desde esta clave.
 */
object LocalAdb {

    class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    private const val VERSION = 0x01000000
    private const val MAX_DATA = 4096

    /** Prefijo DigestInfo de SHA-1: adbd verifica el token como un RSA_sign(NID_sha1). */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /**
     * Ejecuta [command] en un shell de ADB y devuelve su salida. Bloquea: llamar fuera del hilo
     * principal. Si la TV aún no ha autorizado la clave, espera hasta 90 s a que el usuario acepte.
     */
    fun shell(ctx: Context, command: String, port: Int = 5555): String {
        val keys = keyPair(ctx)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
        } catch (e: Exception) {
            socket.close()
            throw AdbException("La depuración por red no está activada", e)
        }
        socket.use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 10_000
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = BufferedOutputStream(s.getOutputStream())

            send(output, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var signatureSent = false
            while (true) {
                val msg = read(input)
                if (msg.command == A_CNXN) break
                if (msg.command != A_AUTH || msg.arg0 != AUTH_TOKEN) {
                    throw AdbException("Respuesta inesperada de ADB")
                }
                if (!signatureSent) {
                    send(output, A_AUTH, AUTH_SIGNATURE, 0, sign(keys.private, msg.data))
                    signatureSent = true
                } else {
                    // Clave desconocida: la TV muestra el aviso de autorización.
                    send(output, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes(keys.public as RSAPublicKey))
                    s.soTimeout = 90_000
                }
            }

            s.soTimeout = 30_000
            val localId = 1
            send(output, A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())
            val result = ByteArrayOutputStream()
            while (true) {
                val msg = read(input)
                when (msg.command) {
                    A_WRTE -> {
                        result.write(msg.data)
                        send(output, A_OKAY, localId, msg.arg0, ByteArray(0))
                    }
                    A_CLSE -> {
                        send(output, A_CLSE, localId, msg.arg0, ByteArray(0))
                        return result.toString("UTF-8")
                    }
                }
            }
        }
    }

    private fun read(input: DataInputStream): Message {
        val header = ByteArray(24)
        try {
            input.readFully(header)
        } catch (e: Exception) {
            throw AdbException("La TV no autorizó la conexión", e)
        }
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        b.putInt(data.sumOf { it.toInt() and 0xff })
        b.putInt(command.inv())
        b.put(data)
        out.write(b.array())
        out.flush()
    }

    /** Firma PKCS#1 v1.5 del token (tratado como un hash SHA-1 ya calculado). */
    private fun sign(key: PrivateKey, token: ByteArray): ByteArray {
        val block = ByteArray(256)
        val zeroIndex = block.size - SHA1_DIGEST_INFO.size - token.size - 1
        block[1] = 0x01
        for (i in 2 until zeroIndex) block[i] = 0xff.toByte()
        SHA1_DIGEST_INFO.copyInto(block, zeroIndex + 1)
        token.copyInto(block, zeroIndex + 1 + SHA1_DIGEST_INFO.size)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(block)
    }

    /** Clave pública en el formato de adbd (estructura RSAPublicKey de mincrypt, en base64). */
    private fun publicKeyBytes(key: RSAPublicKey): ByteArray {
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = key.modulus
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(words * 64).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        b.putInt(n0inv.toInt())
        var tmp = n
        repeat(words) { b.putInt(tmp.mod(r32).toInt()); tmp = tmp.shiftRight(32) }
        tmp = rr
        repeat(words) { b.putInt(tmp.mod(r32).toInt()); tmp = tmp.shiftRight(32) }
        b.putInt(key.publicExponent.toInt())
        val name = "InicioTV@" + Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "")
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " " + name + "\u0000").toByteArray()
    }

    /** Clave RSA propia de la app, guardada en su almacenamiento privado. */
    @Synchronized
    private fun keyPair(ctx: Context): KeyPair {
        val dir = File(ctx.filesDir, "adb")
        val privFile = File(dir, "adbkey")
        val pubFile = File(dir, "adbkey.pub")
        if (privFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())),
                )
            } catch (e: Exception) {
                // clave corrupta: se genera una nueva
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        dir.mkdirs()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }
}
