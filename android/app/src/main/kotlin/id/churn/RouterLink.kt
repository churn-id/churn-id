package id.churn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException

/**
 * The router's USB Ethernet network, requested from Android until [close]. It has no internet, so Android doesn't
 * send anything through it by default; the app binds its sockets to [network].
 */
class RouterNetwork private constructor(
    private val connectivity: ConnectivityManager,
    private val callback: ConnectivityManager.NetworkCallback,
    val network: Network,
) : Closeable {

    override fun close() = release(connectivity, callback)

    companion object {

        /** Requests the network and waits up to [timeoutMillis] for it; null if it doesn't show up. */
        fun connect(context: Context, timeoutMillis: Int): RouterNetwork? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val found = LinkedBlockingQueue<Network>()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    found.offer(network)
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.requestNetwork(request, callback, timeoutMillis)
            val network = found.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            if (network == null) {
                release(connectivity, callback)
                return null
            }
            return RouterNetwork(connectivity, callback, network)
        }

        /**
         * Whether Android lets the app open sockets at all. Without the network permission, which GrapheneOS lets
         * the user turn off per app, it refuses every socket, even one for the cable.
         */
        fun socketsAllowed() = try {
            Os.close(Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0))
            true
        } catch (e: ErrnoException) {
            // Any other error shows up again when the app connects.
            e.errno != OsConstants.EPERM && e.errno != OsConstants.EACCES
        }

        private fun release(connectivity: ConnectivityManager, callback: ConnectivityManager.NetworkCallback) {
            // A request that timed out or was released before is already gone, and unregistering it again throws.
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}

/** The router presented [key], which the user hasn't trusted; [changed] if another key was trusted before. */
class UntrustedHostKey(val key: String, val changed: Boolean) : Exception() {
    val fingerprint get() = HostKeys.fingerprint(key)
}

class WrongPassword : Exception()

object HostKeys {

    /** [key] as its SSH type and its SSH encoding in Base64, like a line of `known_hosts`. */
    fun encode(key: PublicKey) =
        KeyType.fromKey(key).toString() + " " + Base64.getEncoder().encodeToString(blob(key))

    /** The fingerprint OpenSSH shows for an [encode]d key. */
    fun fingerprint(encoded: String): String {
        val blob = Base64.getDecoder().decode(encoded.substringAfter(' '))
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun blob(key: PublicKey) = Buffer.PlainBuffer().putPublicKey(key).compactData
}

/** An SSH connection to the router as root, over a [RouterNetwork] that it closes with itself. */
class RouterConnection private constructor(
    private val ssh: SSHClient,
    private val usb: RouterNetwork,
) : Shell, Closeable {

    /** False once the router or the phone ended the connection; [run] then fails at once. */
    val isOpen get() = ssh.isConnected

    override fun run(command: String, input: ByteArray?, timeoutSeconds: Long): Output =
        ssh.startSession().use { session ->
            val running = session.exec(command)
            // Closing standard input sends end-of-file, also when there is no input.
            running.outputStream.use { if (input != null) it.write(input) }
            running.join(timeoutSeconds, TimeUnit.SECONDS)
            val stdout = running.inputStream.readBytes()
            val stderr = running.errorStream.readBytes().toString(Charsets.UTF_8)
            Output(running.exitStatus, stdout, stderr)
        }

    override fun close() {
        runCatching { ssh.disconnect() }
        usb.close()
    }

    companion object {

        /**
         * Connects through [usb] to [address] and logs in with [password]. Throws [UntrustedHostKey]
         * unless the router presents [trustedKey], and [WrongPassword] if it refuses the password. The
         * connection takes [usb] over: it closes it when it closes, or at once if it can't connect.
         */
        fun open(usb: RouterNetwork, address: String, password: String, trustedKey: String?): RouterConnection {
            val verifier = PinnedKey(trustedKey)
            val ssh = SSHClient(DefaultConfig())
            try {
                ssh.socketFactory = usb.network.socketFactory
                ssh.connectTimeout = 10_000
                ssh.addHostKeyVerifier(verifier)
                ssh.connect(address, 22)
                ssh.authPassword("root", password)
                return RouterConnection(ssh, usb)
            } catch (e: Exception) {
                runCatching { ssh.disconnect() }
                usb.close()
                val presented = verifier.presented
                when {
                    e is TransportException && presented != null && presented != trustedKey ->
                        throw UntrustedHostKey(presented, changed = trustedKey != null)
                    e is UserAuthException -> throw WrongPassword()
                    else -> throw e
                }
            }
        }
    }

    /** Accepts only [trusted], and remembers what the router presented. */
    private class PinnedKey(private val trusted: String?) : HostKeyVerifier {

        @Volatile
        var presented: String? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val encoded = HostKeys.encode(key)
            presented = encoded
            return encoded == trusted
        }

        /** Asks for the trusted key's type first, so the router presents that key and not another of its keys. */
        override fun findExistingAlgorithms(hostname: String, port: Int) = listOfNotNull(trusted?.substringBefore(' '))
    }
}
