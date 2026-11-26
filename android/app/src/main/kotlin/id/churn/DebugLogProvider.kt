package id.churn

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import kotlin.random.Random

/**
 * Lets the app that the user shares the debug log with read it. The provider isn't exported, so only an app that
 * Android has granted [URI] can read it, and it serves nothing else.
 */
class DebugLogProvider : ContentProvider() {

    private val file get() = File(context!!.filesDir, DebugLog.FILE_NAME)

    override fun onCreate() = true

    override fun getType(uri: Uri) = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("The debug log can only be read")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The file's name and size, which apps that receive a file ask for. */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ) = MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(
            columnNames.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> name(uri)
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            },
        )
    }

    override fun insert(uri: Uri, values: ContentValues?) = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        private val NAME = Regex("""churn-debug-log-\p{XDigit}{8}\.txt""")

        /**
         * Where the app that the user shares the log with reads it, under a name of its own for each share, so that the
         * logs of a run, saved to one folder, don't take each other's place.
         */
        fun uri(): Uri = Uri.parse("content://id.churn.log/churn-debug-log-%08x.txt".format(Random.nextInt()))

        /** The name that [uri] gave the share, or a fixed one for a URI that it didn't make. */
        internal fun name(uri: Uri) = uri.lastPathSegment?.takeIf { NAME.matches(it) } ?: "churn-debug-log.txt"
    }
}
