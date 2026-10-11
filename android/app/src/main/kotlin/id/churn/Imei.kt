package id.churn

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** IMEI 1 and IMEI 2 of the router, 15 digits each. */
data class ImeiPair(val imei1: String, val imei2: String)

/** IMEI derivation as specified in design.md, section 4. */
object Imei {

    /** The TAC that public TAC lists show for the Mudi 7. */
    const val MUDI7_TAC = "35609021"

    private const val LABEL = "churn-imei-v1:"
    private const val HMAC = "HmacSHA256"

    /** Whether [imei] is 15 digits with a correct check digit. */
    fun isValid(imei: String) =
        imei.length == 15 && imei.all { it in '0'..'9' } && checkDigit(imei.take(14)) == imei[14] - '0'

    /** The Luhn check digit for [payload], a string of digits. */
    fun checkDigit(payload: String): Int {
        var sum = 0
        payload.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 0) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return (10 - sum % 10) % 10
    }

    fun tac(imei: String) = imei.take(8)

    private fun serial(imei: String) = imei.substring(8, 14).toInt()

    /** `d`: serial of IMEI 2 minus serial of IMEI 1. */
    fun offset(factory: ImeiPair) = serial(factory.imei2) - serial(factory.imei1)

    /** The ICCID's digits, without the `F` the modem pads a 19-digit one with; null if it isn't an ICCID. */
    fun iccidDigits(raw: String): String? {
        val digits = raw.trim().removeSuffix("F").removeSuffix("f")
        return digits.takeIf { it.length in 18..20 && it.all { c -> c in '0'..'9' } }
    }

    /** The pair the profile with [iccid] (digits only) gets on the router with [secret] and [factory] IMEIs. */
    fun derive(secret: ByteArray, iccid: String, factory: ImeiPair): ImeiPair {
        require(secret.size == 32) { "The secret must be 32 bytes" }
        require(iccid.isNotEmpty() && iccid.all { it in '0'..'9' }) { "The ICCID must be digits" }
        val n = range(factory)
        // The largest multiple of n that fits in 32 bits. Words at or above it are skipped, which keeps
        // s free of modulo bias.
        val limit = (1L shl 32) / n * n
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret, HMAC)) }
        var block = mac.doFinal((LABEL + iccid).toByteArray(Charsets.US_ASCII))
        while (true) {
            val words = ByteBuffer.wrap(block)
            repeat(8) {
                val word = words.int.toUInt().toLong()
                if (word < limit) return pair(factory, (word % n).toInt())
            }
            block = mac.doFinal(block)
        }
    }

    /** A random pair, built like a derived one, for the router's next start before the new card is known. */
    fun throwaway(random: SecureRandom, factory: ImeiPair) = pair(factory, random.nextInt(range(factory)))

    /** `n`: how many values `s` can take so that both serials stay within 6 digits. */
    private fun range(factory: ImeiPair) = 1_000_000 - abs(offset(factory))

    private fun pair(factory: ImeiPair, s: Int): ImeiPair {
        val d = offset(factory)
        val serial1 = s + maxOf(0, -d)
        return ImeiPair(build(tac(factory.imei1), serial1), build(tac(factory.imei2), serial1 + d))
    }

    private fun build(tac: String, serial: Int): String {
        val payload = tac + serial.toString().padStart(6, '0')
        return payload + checkDigit(payload)
    }
}
