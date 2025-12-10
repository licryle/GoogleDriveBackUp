package fr.berliat.googledrivebackup

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

import androidx.fragment.app.FragmentActivity

// Must be constructed during Fragment creation
expect class GoogleDriveBackup(activity: FragmentActivity, appName: String) {
    val state: StateFlow<GoogleDriveState>
    val credentials : GoogleCredentials?
    
    var transferChunkSize: Int // MediaHttpUploader.DEFAULT_CHUNK_SIZE is Int
    var jobs : MutableList<Job>

    val deviceAccounts: Array<Account>

    fun logout(account: Account, successCallback: (() -> Unit)? = null)
    fun login(onlyFromCache: Boolean = false, successCallback: (() -> Unit)? = null)

    fun backup(files: List<GoogleDriveBackupFile.UploadFile>, onlyKeepMostRecent: Boolean = true)
            : SharedFlow<BackupEvent>

    suspend fun deletePreviousBackups(): Result<Unit>

    fun cancel()

    fun restore(fileWanted: List<GoogleDriveBackupFile.DownloadFile>, onlyMostRecent: Boolean) : SharedFlow<RestoreEvent>
}
