package fr.berliat.googledrivebackup

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.util.Log

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope

import com.google.api.client.googleapis.media.MediaHttpDownloader
import com.google.api.client.googleapis.media.MediaHttpDownloaderProgressListener
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.time.Instant
import java.util.Collections
import java.util.concurrent.CancellationException

// Must be constructed during Fragment creation
class GoogleDriveBackup(val fragment: Fragment, val activity: ComponentActivity, val appName: String) {
    private val _state = MutableStateFlow<GoogleDriveState>(GoogleDriveState.LoggedOut)
    val state: StateFlow<GoogleDriveState> = _state

    private val scopes = Collections.singletonList(Scope(DriveScopes.DRIVE_APPDATA))
    private val authorizationRequest = AuthorizationRequest
        .builder()
        .setRequestedScopes(scopes)
        .build()

    private var _credentials : GoogleCredentials? = null
    val credentials : GoogleCredentials?
        get() = _credentials
    private var driveService : Drive? = null

    private var isCancelled = false
    var transferChunkSize = MediaHttpUploader.DEFAULT_CHUNK_SIZE

    // This would ideally be more robust. Here before launching the account picker activity, we
    // store the successfulCallback in a queue and deque on result and on error. With concurrency,
    // callbacks could still be executed in the wrong order, but at least should be all executed.
    private val loginSuccessCallbackQueue: ArrayDeque<(() -> Unit)?> = ArrayDeque()
    private val accountPickerLauncher: ActivityResultLauncher<IntentSenderRequest> = fragment.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
            val loginSuccessCallback = loginSuccessCallbackQueue.removeFirstOrNull()
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "User selected an account")
                // Restarting the login, but now we have the right account
                login(false, loginSuccessCallback)
            } else {
                Log.e(TAG, "Account selection canceled")

                activity.lifecycleScope.launch {
                    _state.emit(GoogleDriveState.NoAccountSelected)
                }
            }
    }

    val deviceAccounts: Array<Account>
        get() = AccountManager.get(activity.applicationContext).accounts

    fun logout(account: Account, successCallback: (() -> Unit)? = null) {
        val revReq = RevokeAccessRequest.builder()
            .setAccount(account)
            .setScopes(scopes)
            .build()

        Identity.getAuthorizationClient(activity)
            .revokeAccess(revReq)
            .addOnSuccessListener {
                Log.d(TAG, "Signed out: cached $account with Scopes $scopes cleared")
                _credentials = null
                driveService = null

                activity.lifecycleScope.launch {
                    _state.emit(GoogleDriveState.LoggedOut)
                }

                successCallback?.invoke()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Sign out failed for account $account", e)
            }
    }

    fun login(onlyFromCache: Boolean = false, successCallback: (() -> Unit)? = null) {
        ensureGoogleApiAvailability{
            Identity.getAuthorizationClient(activity)
                .authorize(authorizationRequest)
                .addOnSuccessListener { authorizationResult ->
                    if (authorizationResult.hasResolution()) {
                        // Need to select which account
                        if (!onlyFromCache) {
                            val pendingIntent = authorizationResult.pendingIntent
                            loginSuccessCallbackQueue.add(successCallback)
                            accountPickerLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent!!.intentSender).build()
                            )
                        } else {
                            activity.lifecycleScope.launch {
                                _state.emit(GoogleDriveState.NoAccountSelected)
                            }
                        }
                    } else {
                        // Account was already selected, let's proceed
                        generateCredentialsOnLogin(authorizationResult)

                        activity.lifecycleScope.launch {
                            _state.emit(GoogleDriveState.Ready)
                        }
                        successCallback?.invoke()
                    }
                }
                .addOnFailureListener { e ->
                    activity.lifecycleScope.launch {
                        _state.emit(GoogleDriveState.ScopeDenied(e))
                    }
                }
        }
    }

    private fun ensureGoogleApiAvailability(successCallback: (() -> Unit)) {
        GoogleApiAvailability.getInstance()
            .makeGooglePlayServicesAvailable(activity)
            .addOnSuccessListener {
                successCallback.invoke()
            } // Play services ready
            .addOnFailureListener { e ->
                activity.lifecycleScope.launch {
                    _state.emit(GoogleDriveState.NoGoogleAPI(e))
                }
            }
    }

    private fun generateCredentialsOnLogin(auth: AuthorizationResult) {
        auth.toGoogleSignInAccount()?.account?.name
        _credentials = GoogleCredentials.create(
            AccessToken(
                auth.accessToken,
                null
            )
        )

        Log.i(TAG,
            authorizationRequest.account.toString()+
                    auth.accessToken+
                    auth.grantedScopes.toString())

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory(),
            HttpCredentialsAdapter(_credentials)
        )
            .setApplicationName(appName)
            .build()
    }

    fun backup(files: List<GoogleDriveBackupFile.UploadFile>, onlyKeepMostRecent: Boolean = true)
            : SharedFlow<BackupEvent> {
        val _events = MutableSharedFlow<BackupEvent>(replay = 1, extraBufferCapacity = 10)
        val events: SharedFlow<BackupEvent> = _events

        isCancelled = false

        activity.lifecycleScope.launch(Dispatchers.IO) {
            if (state.value != GoogleDriveState.Ready) {
                _events.emit(BackupEvent.Failed(Exception("Not Ready for Backup")))
                return@launch
            }

            try {
                _state.emit(GoogleDriveState.Busy)
                Log.d(TAG, "Backup started")
                _events.emit(BackupEvent.Started)

                var bytesTotal = 0L
                files.forEach {
                    bytesTotal += it.size ?: 0
                }
                var bytesSent = 0L
                var fileSent = 0

                files.forEach { f ->
                    val metadata = File().apply {
                        name = f.name
                        parents = listOf("appDataFolder") // Optional, hidden folder
                    }

                    val mediaContent = InputStreamContent(
                        f.mimeType,
                        f.inputStream
                    )

                    val file = driveService!!.files().create(metadata, mediaContent)
                        .setFields("id, name, parents")

                    // Enable resumable upload
                    val uploader: MediaHttpUploader = file.mediaHttpUploader
                    uploader.setChunkSize(transferChunkSize)
                    uploader.isDirectUploadEnabled = false  // resumable & progress tracking

                    // Add progress listener
                    uploader.progressListener = MediaHttpUploaderProgressListener { uploader ->
                        when (uploader.uploadState) {
                            MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS -> {
                                activity.lifecycleScope.launch {
                                    _events.emit(BackupEvent.Progress(
                                        f.name,
                                        fileSent + 1,
                                        files.size,
                                        bytesSent + uploader.numBytesUploaded,
                                        bytesTotal
                                    ))
                                }
                                Log.d(TAG, "Uploaded ${uploader.numBytesUploaded} bytes of ${f.name}")

                                if (isCancelled)
                                    throw CancellationException("Backup cancelled")
                            }

                            MediaHttpUploader.UploadState.MEDIA_COMPLETE -> {
                                bytesSent += f.size ?: 0
                                fileSent += 1

                                activity.lifecycleScope.launch {
                                    _events.emit(BackupEvent.Progress(
                                        f.name,
                                        fileSent,
                                        files.size,
                                        bytesSent,
                                        bytesTotal
                                    ))
                                }

                                Log.d(TAG, "Upload complete for ${f.name}")
                            }

                            MediaHttpUploader.UploadState.NOT_STARTED ->
                                Log.d(TAG, "Upload not started for ${f.name}")

                            MediaHttpUploader.UploadState.INITIATION_STARTED ->
                                Log.d(TAG, "Upload started for ${f.name}")

                            MediaHttpUploader.UploadState.INITIATION_COMPLETE ->
                                Log.d(TAG, "Upload initiation complete for ${f.name}")
                        }
                    }

                    if (isCancelled)
                        throw CancellationException("Backup cancelled")

                    val uploadedFile = file.execute()
                    if (uploadedFile.id == null) {
                        throw Exception("File wasn't uploaded - ID missing")
                    }
                }

                // We succeeded, delete old ones if asked
                if (onlyKeepMostRecent)
                    deletePreviousBackups()

                _events.emit(BackupEvent.Success)
            } catch (_: CancellationException) {
                Log.d(TAG, "Backup cancelled")
                _events.emit(BackupEvent.Cancelled)
            } catch (e: Exception) {
                Log.d(TAG, "Backup failed ${e.message}")
                _events.emit(BackupEvent.Failed(e))
            } finally {
                _state.emit(GoogleDriveState.Ready)
            }
        }

        return events
    }

    fun listBackedUpFiles(): List<File> {
        if (driveService == null)
            throw Exception("No credentials - Login first")

        val serverFiles = driveService!!.files().list()
            .setSpaces("appDataFolder")
            .setFields("files(id, name, mimeType, parents, modifiedTime, size)")
            .execute()

        return serverFiles.files
    }

    suspend fun deletePreviousBackups(): Result<Unit>
        = withContext(Dispatchers.IO) {
        if (driveService == null)
            return@withContext Result.failure(Exception("No credentials - Login first"))

            try {
                val files = listBackedUpFiles()

                val grouped = files.groupBy { it.name }
                val olderFiles = grouped.flatMap { (_, group) ->
                    val maxTime =
                        group.maxOf { it.modifiedTime.value }     // find the most recent modifiedTime
                    group.filter { it.modifiedTime.value < maxTime }       // keep only the older ones
                }

                olderFiles.forEach { f ->
                    driveService!!.files().delete(f.id).execute()
                }

                Log.d(TAG, "Delete previous backup success")
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                    Log.d(TAG, "Delete previous backup Failure")
                return@withContext Result.failure(e)
            }
        }

    fun cancel() {
        isCancelled = true
    }

    fun restore(fileWanted: List<GoogleDriveBackupFile.DownloadFile>, onlyMostRecent: Boolean) : SharedFlow<RestoreEvent> {
        val _events = MutableSharedFlow<RestoreEvent>(replay = 1, extraBufferCapacity = 10)
        val events: SharedFlow<RestoreEvent> = _events

        isCancelled = false

        activity.lifecycleScope.launch(Dispatchers.IO) {
            if (state.value != GoogleDriveState.Ready) {
                _events.emit(RestoreEvent.Failed(Exception("Not Ready for Restore")))
                return@launch
            }

            try {
                _state.emit(GoogleDriveState.Busy)
                Log.d(TAG, "Restore started")
                _events.emit(RestoreEvent.Started)

                val filesAvailable = listBackedUpFiles()
                if (filesAvailable.isEmpty()) {
                    _events.emit(RestoreEvent.Empty)
                    return@launch
                }

                // Get the intersection of files wanted and existing files, add metadata
                var files = filesAvailable
                    .filter { f -> ! fileWanted.none { fw -> fw.name == f.name } }
                    .map { f -> GoogleDriveBackupFile.DownloadFile(
                        f.name,
                        fileWanted.find { fw -> f.name == fw.name }!!.outputStream,
                        f.mimeType,
                        (f.get("size") as Long), // f.size returns the array size of 6 elements
                        f.id,
                        Instant.ofEpochMilli(f.modifiedTime.value))
                    }

                if (onlyMostRecent) {
                    files = files
                        .sortedBy { it.modifiedTime }         // oldest → newest
                        .associateBy { it.name }              // only keeps the last (newest) per name
                        .values.toList()
                }

                var bytesTotal = 0L
                files.forEach {
                    bytesTotal += it.size ?: 0
                }
                var bytesReceived = 0L
                var fileReceived = 0

                files.forEach { f ->
                    val file = driveService!!.files().get(f.id)

                    // Enable resumable upload
                    val downloader: MediaHttpDownloader = file.mediaHttpDownloader
                    downloader.isDirectDownloadEnabled = false  // resumable & progress tracking
                    downloader.setChunkSize(transferChunkSize)

                    // Add progress listener
                    downloader.progressListener = MediaHttpDownloaderProgressListener { downloader ->
                        when (downloader.downloadState) {
                            MediaHttpDownloader.DownloadState.MEDIA_IN_PROGRESS -> {
                                activity.lifecycleScope.launch {
                                    _events.emit(
                                        RestoreEvent.Progress(
                                            f.name,
                                            fileReceived + 1,
                                            files.size,
                                            bytesReceived + downloader.numBytesDownloaded,
                                            bytesTotal
                                        )
                                    )
                                }
                                Log.d(
                                    TAG,
                                    "Download ${downloader.numBytesDownloaded} bytes of ${f.name}"
                                )

                                if (isCancelled)
                                    throw CancellationException("Restore cancelled")
                            }

                            MediaHttpDownloader.DownloadState.MEDIA_COMPLETE -> {
                                bytesReceived += f.size ?: 0
                                fileReceived += 1

                                activity.lifecycleScope.launch {
                                    _events.emit(
                                        RestoreEvent.Progress(
                                            f.name,
                                            fileReceived,
                                            files.size,
                                            bytesReceived,
                                            bytesTotal
                                        )
                                    )
                                }

                                Log.d(TAG, "Download complete for ${f.name}")
                            }

                            MediaHttpDownloader.DownloadState.NOT_STARTED ->
                                Log.d(TAG, "Download not started for ${f.name}")
                        }
                    }

                    if (isCancelled)
                        throw CancellationException("Restoration cancelled")

                    f.outputStream.use { output ->
                        file.executeMediaAndDownloadTo(output)
                        output.flush()
                    }
                }

                _events.emit(RestoreEvent.Success(files))
            } catch (_: CancellationException) {
                Log.d(TAG, "Backup cancelled")
                _events.emit(RestoreEvent.Cancelled)
            } catch (e: Exception) {
                Log.d(TAG, "Restore failed ${e.message}")
                _events.emit(RestoreEvent.Failed(e))
            } finally {
                _state.emit(GoogleDriveState.Ready)
            }
        }

        return events
    }

    companion object {
        private const val TAG = "GoogleDriveBackUp"
    }
}

