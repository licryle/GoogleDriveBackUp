package fr.berliat.googledrivebackup

interface GoogleDriveBackupInterface {
    fun onLogout()
    fun onReady()
    fun onNoGoogleAPI(e : Exception)
    fun onNoAccountSelected()
    fun onScopeDenied(e: Exception)
    fun onBackupStarted()
    fun onBackupProgress(fileName: String, fileIndex: Int, fileCount: Int, bytesSent: Long, bytesTotal: Long)
    fun onBackupSuccess()
    fun onBackupCancelled()
    fun onBackupFailed(e: Exception)

    fun onRestoreEmpty()
    fun onRestoreStarted()
    fun onRestoreProgress(fileName: String, fileIndex: Int, fileCount: Int, bytesReceived: Long, bytesTotal: Long)
    fun onRestoreSuccess(files: List<GoogleDriveBackupFile.DownloadFile>)
    fun onRestoreCancelled()
    fun onRestoreFailed(e: Exception)

    fun onDeletePreviousBackupFailed(e: Exception)
    fun onDeletePreviousBackupSuccess()
}