package fr.berliat.googledrivebackup

import kotlinx.datetime.Instant

import kotlinx.io.Sink
import kotlinx.io.Source

sealed class GoogleDriveBackupFile(
    val name: String,
    val mimeType: String?,
    val size: Long?
) {
    class UploadFile(
        name: String,
        val inputSource: Source,
        mimeType: String,
        size: Long
    ) : GoogleDriveBackupFile(name, mimeType, size)

    class DownloadFile(
        name: String,
        val outputSink: Sink,
        mimeType: String? = null,
        size: Long? = null,
        val id: String? = null,
        val modifiedTime: Instant? = null
    ) : GoogleDriveBackupFile(name, mimeType, size)
}
