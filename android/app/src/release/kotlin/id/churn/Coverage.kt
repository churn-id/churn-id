package id.churn

import android.content.Context
import android.net.Uri

/** Release builds count no coverage, which only the coverage build does (release-tests.md, "Coverage pass"). */
@Suppress("UNUSED_PARAMETER")
object Coverage {

    const val AVAILABLE = false

    fun save(context: Context): Exception? = null

    fun uri(context: Context): Uri = throw UnsupportedOperationException()
}
