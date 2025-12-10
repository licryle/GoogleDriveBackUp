package fr.berliat.googledrivebackup

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

actual class GoogleDriveBackup actual constructor(val appName: String) {
    actual val state: StateFlow<GoogleDriveState> = MutableStateFlow(GoogleDriveState.LoggedOut)
    actual val credentials: GoogleCredentials? = null
    actual var transferChunkSize: Int = 0
    actual var jobs: MutableList<Job> = mutableListOf()
    actual val deviceAccounts: Array<Account> = emptyArray()

    actual fun logout(account: Account, successCallback: (() -> Unit)?) {}
    actual fun login(onlyFromCache: Boolean, successCallback: (() -> Unit)?) {}
    
    actual fun backup(files: List<GoogleDriveBackupFile.UploadFile>, onlyKeepMostRecent: Boolean)
            : SharedFlow<BackupEvent> {
         // Return dummy flow or throw not implemented
         return kotlinx.coroutines.flow.flowOf()
    }
    
    actual suspend fun deletePreviousBackups(): Result<Unit> = Result.success(Unit)
    
    actual fun cancel() {}
    
    actual fun restore(fileWanted: List<GoogleDriveBackupFile.DownloadFile>, onlyMostRecent: Boolean) : SharedFlow<RestoreEvent> {
         return kotlinx.coroutines.flow.flowOf()
    }
}
