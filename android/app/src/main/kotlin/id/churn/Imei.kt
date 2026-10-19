package id.churn

import java.security.SecureRandom
import kotlin.math.abs

/** IMEI 1 and IMEI 2 of the router, 15 digits each. */
data class ImeiPair(val imei1: String, val imei2: String)

/** IMEI pairs as specified in design.md, section 4. */
object Imei {

    /** The TAC that public TAC lists show for the Mudi 7. */
    const val MUDI7_TAC = "35609021"

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

    /** A random pair with the TACs and the spacing of the [factory] IMEIs: a new card's pair, or a throwaway pair. */
    fun random(random: SecureRandom, factory: ImeiPair) = pair(factory, random.nextInt(range(factory)))

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
