package fr.berliat.googledrivebackup

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

sealed class GoogleDriveBackupFile(
    val name: String,
    val mimeType: String?,
    val size: Long?
) {
    class UploadFile(
        name: String,
        val inputStream: InputStream,
        mimeType: String,
        size: Long
    ) : GoogleDriveBackupFile(name, mimeType, size)

    class DownloadFile(
        name: String,
        val outputStream: OutputStream,
        mimeType: String? = null,
        size: Long? = null,
        val id: String? = null,
        val modifiedTime: Instant? = null
    ) : GoogleDriveBackupFile(name, mimeType, size)
}
