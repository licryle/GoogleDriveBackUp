package fr.berliat.googledrivebackup

import android.app.Activity
import android.content.IntentSender
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Date
import java.util.concurrent.CancellationException

// Must be constructed during Fragment creation
class GoogleDriveBackup(val fragment: Fragment, val activity: Activity, val appName: String) {
    private val scopes = Collections.singletonList(Scope(DriveScopes.DRIVE_APPDATA))
    private val listeners = mutableListOf<GoogleDriveBackupInterface>()
    private val authorizationRequest = AuthorizationRequest
        .builder()
        .setRequestedScopes(scopes)
        .build()
    private var accountPickerLauncher: ActivityResultLauncher<IntentSenderRequest> = fragment.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "User selected an account")
        } else {
            Log.e(TAG, "Account selection canceled")
        }
    }

    private var _credentials : GoogleCredentials? = null
    val credentials : GoogleCredentials?
        get() = _credentials

    private var isCancelledBackup = false
    private var isCancelledRestore = false

    fun logout() {
        if (_credentials != null && _credentials!!.accessToken != null) {
            Identity.getAuthorizationClient(activity)
                .clearToken(ClearTokenRequest.builder().setToken(credentials!!.accessToken.tokenValue).build())
                .addOnSuccessListener {
                    Log.i(
                        TAG,
                        "Successfully removed the token from the cache"
                    )
                    triggerOnLogout()
                }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to clear token", e) }
        }
    }

    fun login(onlyFromCache: Boolean = false) {
        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    if (!onlyFromCache) {
                        val pendingIntent = authorizationResult.pendingIntent
                        try {
                            accountPickerLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent!!.intentSender).build()
                            )
                        } catch (e: IntentSender.SendIntentException) {
                            Log.e("Authorization", "Failed to start authorization UI", e)
                            triggerOnScopeDenied(e)
                        }
                    }
                } else {
                    _credentials = GoogleCredentials.create(
                        AccessToken(
                            authorizationResult.accessToken,
                            Date(System.currentTimeMillis() + 3600_000 * 24 * 365)
                        )
                    )

                    Log.i(TAG,
                        authorizationRequest.account.toString()+
                        authorizationResult.accessToken+
                        authorizationResult.grantedScopes.toString())
                    triggerOnReady()
                }
            }
            .addOnFailureListener { e -> triggerOnScopeDenied(e) }

    }

    fun cancelBackup() {
        isCancelledBackup = true
    }

    fun backup(files: List<GoogleDriveBackupFile>) {
        isCancelledBackup = false

        if (_credentials == null) {
            triggerOnBackupFailed(Exception("No credentials - Login first"))
            return
        }

        if (activity !is ComponentActivity) {
            triggerOnBackupFailed(Exception("Activity isn't a lifecycle owner"))
            return
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val driveService = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory(),
                    HttpCredentialsAdapter(_credentials)
                )
                    .setApplicationName(appName)
                    .build()

                withContext(Dispatchers.Main) {
                    triggerOnBackupStarted()
                }

                var bytesTotal = 0L
                files.forEach {
                    bytesTotal += it.size
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
                        f.contentStream
                    )

                    val file = driveService!!.files().create(metadata, mediaContent)
                        .setFields("id, name, parents")

                    // Enable resumable upload
                    val uploader: MediaHttpUploader = file.mediaHttpUploader
                    uploader.isDirectUploadEnabled = false  // resumable & progress tracking

                    // Add progress listener
                    uploader.progressListener = MediaHttpUploaderProgressListener { uploader ->
                        when (uploader.uploadState) {
                            MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS -> {
                                activity.lifecycleScope.launch(Dispatchers.Main) {
                                    triggerOnBackupProgress(
                                        f.name,
                                        fileSent + 1,
                                        files.size,
                                        bytesSent + uploader.numBytesUploaded,
                                        bytesTotal
                                    )
                                }
                                Log.d(TAG, "Uploaded ${uploader.numBytesUploaded} bytes of ${f.name}")

                                if (isCancelledBackup)
                                    throw CancellationException("Backup cancelled")
                            }

                            MediaHttpUploader.UploadState.MEDIA_COMPLETE -> {
                                bytesSent += f.size
                                fileSent += 1

                                activity.lifecycleScope.launch(Dispatchers.Main) {
                                    triggerOnBackupProgress(
                                        f.name,
                                        fileSent,
                                        files.size,
                                        bytesSent,
                                        bytesTotal
                                    )
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

                    if (isCancelledBackup)
                        throw CancellationException("Backup cancelled")

                    val uploadedFile = file.execute()
                    if (uploadedFile.id == null) {
                        throw Exception("File wasn't uploaded - ID missing")
                    }
                }


                withContext(Dispatchers.Main) {
                    triggerOnBackupSuccess()
                }
            } catch (_: CancellationException) {
                Log.d(TAG, "Backup Cloud cancelled")

                withContext(Dispatchers.Main) {
                    triggerOnBackupCancelled()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Backup Cloud Failed ${e.message}")

                withContext(Dispatchers.Main) {
                    triggerOnBackupFailed(e)
                }
            }
        }
    }

    /*fun restore(): List<GoogleDriveBackupFile> {
        val serverFiles = driveService.files().list()
            .setSpaces("appDataFolder")
            .setFields("files(id, name, parents, modifiedTime, size)")
            .execute()

        for (f in serverFiles.files) {
            Log.d(TAG, "Found backup in Drive: ${f.name} (${f.id}) of size ${f.size}, modified ${f.modifiedTime} in ${f.parents}")
        }
    }*/

    // Listeners
    fun addListener(listener: GoogleDriveBackupInterface) {
        listeners.add(listener)
    }

    fun removeListener(listener: GoogleDriveBackupInterface) {
        listeners.remove(listener)
    }

    private fun triggerOnLogout() { listeners.forEach { it -> it.onLogout() } }
    private fun triggerOnReady() { listeners.forEach { it -> it.onReady() } }
    private fun triggerOnScopeDenied(e: Exception) { listeners.forEach { it -> it.onScopeDenied(e) } }
    private fun triggerOnBackupStarted() { listeners.forEach { it -> it.onBackupStarted() } }
    private fun triggerOnBackupProgress(fileName: String, fileIndex: Int, fileCount: Int, bytesSent: Long, bytesTotal: Long) {
        listeners.forEach { it ->
            it.onBackupProgress(fileName, fileIndex, fileCount, bytesSent, bytesTotal) }
    }
    private fun triggerOnBackupSuccess() { listeners.forEach { it -> it.onBackupSuccess() } }
    private fun triggerOnBackupCancelled() { listeners.forEach { it -> it.onBackupCancelled() } }
    private fun triggerOnBackupFailed(e: Exception) { listeners.forEach { it -> it.onBackupFailed(e) } }

    companion object {
        private const val TAG = "GoogleDriveBackUp"
    }
}