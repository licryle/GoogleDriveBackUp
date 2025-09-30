package fr.berliat.googledrivebackup

sealed class GoogleDriveState {
    object LoggedOut : GoogleDriveState()
    object Ready : GoogleDriveState()
    object Busy : GoogleDriveState()
    data class NoGoogleAPI(val exception: Exception) : GoogleDriveState()
    object NoAccountSelected : GoogleDriveState()
    data class ScopeDenied(val exception: Exception) : GoogleDriveState()
}

sealed class BackupEvent {
    object Started : BackupEvent()
    data class Progress(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesSent: Long,
        val bytesTotal: Long
    ) : BackupEvent()
    object Success : BackupEvent()
    object Cancelled : BackupEvent()
    data class Failed(val exception: Exception) : BackupEvent()
}

sealed class RestoreEvent {
    object Empty : RestoreEvent()
    object Started : RestoreEvent()
    data class Progress(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesReceived: Long,
        val bytesTotal: Long
    ) : RestoreEvent()
    data class Success(val files: List<GoogleDriveBackupFile.DownloadFile>) : RestoreEvent()
    object Cancelled : RestoreEvent()
    data class Failed(val exception: Exception) : RestoreEvent()
}