package id.churn

import java.net.SocketTimeoutException
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeysTest {

    @Test
    fun encodesAKeyAsKnownHostsHoldsIt() {
        assertEquals(KEY, HostKeys.encode(publicKey(KEY)))
        assertEquals(OTHER_KEY, HostKeys.encode(publicKey(OTHER_KEY)))
    }

    @Test
    fun givesTheFingerprintThatOpenSshShows() {
        // As ssh-keygen -lf printed them for the two keys.
        assertEquals("SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8", HostKeys.fingerprint(KEY))
        assertEquals("SHA256:F34nin7tcaYH6WR5LSWSfj6weFBPfBpuyUUoPFP9YjA", HostKeys.fingerprint(OTHER_KEY))
    }

    @Test
    fun acceptsOnlyTheTrustedKey() {
        val pinned = PinnedKey(KEY)
        assertFalse(pinned.verify(ADDRESS, 22, publicKey(OTHER_KEY)))
        assertEquals(OTHER_KEY, pinned.presented)
        assertTrue(pinned.verify(ADDRESS, 22, publicKey(KEY)))
        assertEquals(KEY, pinned.presented)
    }

    @Test
    fun acceptsNoKeyWhileNoneIsTrusted() {
        val pinned = PinnedKey(null)
        assertFalse(pinned.verify(ADDRESS, 22, publicKey(KEY)))
        assertEquals(KEY, pinned.presented)
    }

    @Test
    fun asksForTheTrustedKeysType() {
        assertEquals(listOf("ssh-ed25519"), PinnedKey(KEY).findExistingAlgorithms(ADDRESS, 22))
        assertEquals(emptyList<String>(), PinnedKey(null).findExistingAlgorithms(ADDRESS, 22))
    }

    @Test
    fun tellsOfAKeyThatNoneWasTrustedBefore() {
        val pinned = PinnedKey(null)
        pinned.verify(ADDRESS, 22, publicKey(KEY))
        val failure = pinned.failure(notVerified()) as UntrustedHostKey
        assertEquals(KEY, failure.key)
        assertFalse(failure.changed)
    }

    @Test
    fun tellsOfAKeyOtherThanTheTrustedOne() {
        val pinned = PinnedKey(KEY)
        pinned.verify(ADDRESS, 22, publicKey(OTHER_KEY))
        val failure = pinned.failure(notVerified()) as UntrustedHostKey
        assertEquals(OTHER_KEY, failure.key)
        assertTrue(failure.changed)
    }

    @Test
    fun tellsOfARefusedPassword() {
        val pinned = PinnedKey(KEY)
        pinned.verify(ADDRESS, 22, publicKey(KEY))
        assertTrue(pinned.failure(UserAuthException("Exhausted available authentication methods")) is WrongPassword)
    }

    @Test
    fun passesOnAnyOtherFailure() {
        val pinned = PinnedKey(KEY)
        // Before the router presented a key: it didn't answer, or it offered none of the algorithms that sshj knows.
        val timeout = SocketTimeoutException("connect timed out")
        assertSame(timeout, pinned.failure(timeout))
        val noSettlement = TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "Unable to reach a settlement")
        assertSame(noSettlement, pinned.failure(noSettlement))
        // After the trusted key, as when the connection drops.
        pinned.verify(ADDRESS, 22, publicKey(KEY))
        val lost = TransportException(DisconnectReason.CONNECTION_LOST, "Broken transport; encountered EOF")
        assertSame(lost, pinned.failure(lost))
    }

    private companion object {
        const val ADDRESS = "192.168.8.1"

        /** The public keys of RFC 8032's first two Ed25519 test vectors, each as a line of `known_hosts` holds it. */
        const val KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea"
        const val OTHER_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAID1AF8PoQ4lakrcKp00bfrycmCzPLsSWjMDNVfEq9GYM"

        /** [encoded], read as sshj reads the key that the router presents. */
        fun publicKey(encoded: String) =
            Buffer.PlainBuffer(Base64.getDecoder().decode(encoded.substringAfter(' '))).readPublicKey()

        /** What sshj throws once the verifier refuses the router's key. */
        fun notVerified() = TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "Could not verify host key")
    }
}
