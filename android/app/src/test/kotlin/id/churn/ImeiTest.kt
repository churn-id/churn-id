package id.churn

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test vectors of development.md, "IMEI derivation". */
class ImeiTest {

    private val secret = ByteArray(32) { it.toByte() }

    /** Factory IMEIs with TAC 35609021 whose serials are [d] apart. Only the TAC and `d` matter. */
    private fun factory(d: Int): ImeiPair {
        fun imei(serial: Int) = "35609021" + serial.toString().padStart(6, '0') + "0"
        return ImeiPair(imei(maxOf(0, -d)), imei(maxOf(0, -d) + d))
    }

    @Test
    fun computesTheCheckDigit() {
        assertEquals(8, Imei.checkDigit("49015420323751"))
        assertTrue(Imei.isValid("490154203237518"))
        assertFalse(Imei.isValid("490154203237519"))
        assertFalse(Imei.isValid("49015420323751"))
    }

    @Test
    fun derivesTheDocumentedPairs() {
        val iccid = "8941000000000000015"
        assertEquals(
            ImeiPair("356090213059463", "356090213059539"),
            Imei.derive(secret, iccid, factory(d = 7)),
        )
        assertEquals(
            ImeiPair("356090213059539", "356090213059463"),
            Imei.derive(secret, iccid, factory(d = -7)),
        )
        assertEquals(
            ImeiPair("356090213012637", "356090213012637"),
            Imei.derive(secret, iccid, factory(d = 0)),
        )
    }

    @Test
    fun skipsWordsAboveTheLimit() {
        // The first word of this ICCID's HMAC, ffff2de0, is rejected.
        assertEquals(
            ImeiPair("356090219466134", "356090219466209"),
            Imei.derive(secret, "8941000000000104627", factory(d = 7)),
        )
    }

    @Test
    fun keepsEachFactoryTac() {
        val factory = ImeiPair("356090215000003", "350000005000075")
        val pair = Imei.derive(secret, "8941000000000000015", factory)
        assertEquals("35609021", Imei.tac(pair.imei1))
        assertEquals("35000000", Imei.tac(pair.imei2))
        assertTrue(Imei.isValid(pair.imei1) && Imei.isValid(pair.imei2))
    }

    @Test
    fun throwawayPairsKeepTheFactorySpacing() {
        val random = SecureRandom()
        for (d in listOf(7, -7, 0, 999_998)) {
            val factory = factory(d)
            repeat(200) {
                val pair = Imei.throwaway(random, factory)
                assertTrue(Imei.isValid(pair.imei1) && Imei.isValid(pair.imei2))
                assertEquals(d, Imei.offset(pair))
            }
        }
    }

    @Test
    fun keepsTheDigitsOfAnIccid() {
        assertEquals("8941000000000000015", Imei.iccidDigits("8941000000000000015F"))
        assertEquals("89410000000000000150", Imei.iccidDigits(" 89410000000000000150\r"))
        assertNull(Imei.iccidDigits("8941000000000000015FF"))
        assertNull(Imei.iccidDigits("89410000000000A0015"))
        assertNull(Imei.iccidDigits(""))
    }
}
