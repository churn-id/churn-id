package id.churn

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

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
                    OpenableColumns.DISPLAY_NAME -> NAME
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
        private const val NAME = "churn-debug-log.txt"
        val URI: Uri = Uri.parse("content://id.churn.log/$NAME")
    }
}
