package id.churn

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/**
 * Lets the app that the user shares the coverage file with read it, as [DebugLogProvider] does the debug log. It
 * repeats that provider's few lines rather than changing it, so that release builds stay as they are.
 */
class CoverageProvider : ContentProvider() {

    private val file get() = Coverage.file(context!!.filesDir)

    override fun onCreate() = true

    override fun getType(uri: Uri) = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("The coverage file can only be read")
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
                    OpenableColumns.DISPLAY_NAME -> file.name
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
        const val AUTHORITY = "id.churn.coverage"
    }
}
