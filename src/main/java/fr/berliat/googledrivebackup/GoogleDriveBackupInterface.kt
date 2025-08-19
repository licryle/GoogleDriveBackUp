package fr.berliat.googledrivebackup

interface GoogleDriveBackupInterface {
    fun onLogout()
    fun onReady()
    fun onScopeDenied(e: Exception)
    fun onBackupStarted()
    fun onBackupProgress(fileName: String, fileIndex: Int, fileCount: Int, bytesSent: Long, bytesTotal: Long)
    fun onBackupSuccess()
    fun onBackupCancelled()
    fun onBackupFailed(e: Exception)
}