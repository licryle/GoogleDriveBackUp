@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package fr.berliat.googledrivebackup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import kotlinx.io.*
import platform.Foundation.*
import platform.UIKit.*
import cocoapods.GoogleSignIn.*
import cocoapods.GoogleAPIClientForREST.*
import kotlinx.cinterop.*
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class GoogleDriveBackup actual constructor(val appName: String) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow<GoogleDriveState>(GoogleDriveState.LoggedOut)
    actual val state: StateFlow<GoogleDriveState> = _state

    private var _credentials: GoogleCredentials? = null
    actual val credentials: GoogleCredentials? get() = _credentials

    private var driveService: GTLRDriveService? = null

    actual var transferChunkSize: Int = 1024 * 1024 // 1MB
    actual var jobs: MutableList<Job> = mutableListOf()

    actual val deviceAccounts: Array<Account>
        get() = GIDSignIn.sharedInstance.currentUser?.let { arrayOf(it) } ?: emptyArray()

    actual fun login(onlyFromCache: Boolean, successCallback: (() -> Unit)?) {
        val driveScope = "https://www.googleapis.com/auth/drive.appdata"
        if (onlyFromCache) {
            GIDSignIn.sharedInstance.restorePreviousSignInWithCompletion { user, error ->
                val scopes = user?.grantedScopes as? List<*>
                if (user != null && scopes?.contains(driveScope) != true) {
                    // Cached user lacks the necessary scope. Treat as NoAccountSelected
                    // to force an interactive sign-in with scopes attached.
                    handleSignInResult(null, null, successCallback)
                } else {
                    handleSignInResult(user, error, successCallback)
                }
            }
        } else {
            val rootVC = getRootViewController()
            if (rootVC == null) {
                _state.value = GoogleDriveState.NoAccountSelected
                return
            }
            
            GIDSignIn.sharedInstance.signInWithPresentingViewController(
                rootVC, 
                null, 
                listOf(driveScope)
            ) { result, error ->
                handleSignInResult(result?.user, error, successCallback)
            }
        }
    }

    private fun getRootViewController(): UIViewController? {
        val window = UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
        return window?.rootViewController
    }

    private fun handleSignInResult(user: GIDGoogleUser?, error: NSError?, successCallback: (() -> Unit)?) {
        if (error != null) {
            val code = error.code
            if (code == -5L || code == -1L) {
                _state.value = GoogleDriveState.NoAccountSelected
            } else {
                _state.value = GoogleDriveState.ScopeDenied(Exception(error.localizedDescription))
            }
            return
        }

        if (user != null) {
            _credentials = user
            driveService = GTLRDriveService().apply {
                // Bridge authorization protocol between pods
                val auth = user.fetcherAuthorizer()
                authorizer = (auth as Any) as? cocoapods.GoogleAPIClientForREST.GTMFetcherAuthorizationProtocolProtocol
            }
            _state.value = GoogleDriveState.Ready
            successCallback?.invoke()
        } else {
            _state.value = GoogleDriveState.NoAccountSelected
        }
    }

    actual fun logout(account: Account, successCallback: (() -> Unit)?) {
        GIDSignIn.sharedInstance.signOut()
        _credentials = null
        driveService = null
        _state.value = GoogleDriveState.LoggedOut
        successCallback?.invoke()
    }

    actual fun backup(files: List<GoogleDriveBackupFile.UploadFile>, onlyKeepMostRecent: Boolean): SharedFlow<BackupEvent> {
        val events = MutableSharedFlow<BackupEvent>(replay = 1, extraBufferCapacity = 10)
        
        val job = scope.launch(Dispatchers.Default) {
            if (state.value != GoogleDriveState.Ready) {
                events.emit(BackupEvent.Failed(Exception("Not Ready for Backup")))
                return@launch
            }

            try {
                withContext(Dispatchers.Main) { _state.emit(GoogleDriveState.Busy) }
                events.emit(BackupEvent.Started)

                var bytesTotal = 0L
                files.forEach { bytesTotal += it.size ?: 0 }
                var bytesSent = 0L
                var fileSent = 0

                for (f in files) {
                    if (!isActive) throw CancellationException()
                    
                    val driveFile = GTLRDrive_File()
                    driveFile.name = f.name
                    driveFile.parents = listOf("appDataFolder")

                    val data = f.inputSource.readAllToNSData()
                    val uploadParameters = GTLRUploadParameters.uploadParametersWithData(data, f.mimeType ?: "application/octet-stream")
                    
                    val query = (GTLRDriveQuery_FilesCreate.queryWithObject(driveFile, uploadParameters) as Any) as GTLRQueryProtocolProtocol
                    
                    val params = GTLRServiceExecutionParameters()
                    params.uploadProgressBlock = { _: GTLRServiceTicket?, written: ULong, _: ULong ->
                        this@GoogleDriveBackup.scope.launch {
                            events.emit(BackupEvent.Progress(f.name, fileSent + 1, files.size, bytesSent + written.toLong(), bytesTotal))
                        }
                    }
                    query.executionParameters = params
                    
                    suspendCancellableCoroutine { continuation: CancellableContinuation<GTLRDrive_File> ->
                        val ticket = driveService!!.executeQuery(query) { _: GTLRServiceTicket?, result: Any?, error: NSError? ->
                            if (error != null) {
                                continuation.resumeWithException(Exception(error.localizedDescription))
                            } else {
                                continuation.resume(result as GTLRDrive_File)
                            }
                        } as? GTLRServiceTicket
                        
                        continuation.invokeOnCancellation {
                            ticket?.cancelTicket()
                        }
                    }
                    
                    bytesSent += f.size ?: 0
                    fileSent++
                    events.emit(BackupEvent.Progress(f.name, fileSent, files.size, bytesSent, bytesTotal))
                }

                if (onlyKeepMostRecent) {
                    deletePreviousBackups()
                }

                events.emit(BackupEvent.Success)
            } catch (e: CancellationException) {
                events.emit(BackupEvent.Cancelled)
            } catch (e: Exception) {
                events.emit(BackupEvent.Failed(e))
            } finally {
                withContext(Dispatchers.Main) { _state.emit(GoogleDriveState.Ready) }
                coroutineContext[Job]?.let { jobs.remove(it) }
            }
        }
        
        jobs.add(job)
        return events
    }

    actual suspend fun deletePreviousBackups(): Result<Unit> = withContext(Dispatchers.Default) {
        val service = driveService ?: return@withContext Result.failure(Exception("Not logged in"))
        
        try {
            val filesAvailable = listBackedUpFiles()
            val grouped = filesAvailable.groupBy { it.name }
            val olderFiles = grouped.flatMap { (_, group) ->
                val sorted = group.sortedByDescending { it.modifiedTime?.date?.timeIntervalSince1970 ?: 0.0 }
                if (sorted.size > 1) sorted.drop(1) else emptyList()
            }

            for (f in olderFiles) {
                val query = (GTLRDriveQuery_FilesDelete.queryWithFileId(f.identifier!!) as Any) as GTLRQueryProtocolProtocol
                suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
                    service.executeQuery(query) { _: GTLRServiceTicket?, _: Any?, error: NSError? ->
                        if (error != null) continuation.resumeWithException(Exception(error.localizedDescription))
                        else continuation.resume(Unit)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun listBackedUpFiles(): List<GTLRDrive_File> {
        val service = driveService ?: throw Exception("No credentials")
        val queryObj = GTLRDriveQuery_FilesList.query() as Any
        val query = queryObj as GTLRDriveQuery_FilesList
        query.spaces = "appDataFolder"
        query.fields = "files(id, name, mimeType, parents, modifiedTime, size)"
        
        return suspendCancellableCoroutine { continuation: CancellableContinuation<List<GTLRDrive_File>> ->
            service.executeQuery(query as GTLRQueryProtocolProtocol) { _: GTLRServiceTicket?, result: Any?, error: NSError? ->
                if (error != null) {
                    continuation.resumeWithException(Exception(error.localizedDescription))
                } else {
                    val fileList = result as? GTLRDrive_FileList
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(fileList?.files as? List<GTLRDrive_File> ?: emptyList())
                }
            }
        }
    }

    actual fun cancel() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    actual fun restore(fileWanted: List<GoogleDriveBackupFile.DownloadFile>, onlyMostRecent: Boolean): SharedFlow<RestoreEvent> {
        val events = MutableSharedFlow<RestoreEvent>(replay = 1, extraBufferCapacity = 10)
        
        val job = scope.launch(Dispatchers.Default) {
            if (state.value != GoogleDriveState.Ready) {
                events.emit(RestoreEvent.Failed(Exception("Not Ready for Restore")))
                return@launch
            }

            try {
                withContext(Dispatchers.Main) { _state.emit(GoogleDriveState.Busy) }
                events.emit(RestoreEvent.Started)

                val filesAvailable = listBackedUpFiles()
                if (filesAvailable.isEmpty()) {
                    events.emit(RestoreEvent.Empty)
                    return@launch
                }

                var filesToDownload = filesAvailable
                    .filter { f -> fileWanted.any { fw -> fw.name == f.name } }
                    .map { f -> 
                        val fw = fileWanted.find { it.name == f.name }!!
                        GoogleDriveBackupFile.DownloadFile(
                            f.name ?: "",
                            fw.outputSink,
                            f.mimeType,
                            f.size?.longValue,
                            f.identifier,
                            f.modifiedTime?.date?.let { Instant.fromEpochMilliseconds((it.timeIntervalSince1970 * 1000).toLong()) }
                        )
                    }

                if (onlyMostRecent) {
                    filesToDownload = filesToDownload
                        .sortedBy { it.modifiedTime }
                        .associateBy { it.name }
                        .values.toList()
                }

                var bytesTotal = 0L
                filesToDownload.forEach { bytesTotal += it.size ?: 0 }
                var bytesReceived = 0L
                var fileReceived = 0

                for (f in filesToDownload) {
                    if (!isActive) throw CancellationException()
                    
                    val query = (GTLRDriveQuery_FilesGet.queryForMediaWithFileId(f.id!!) as Any) as GTLRQueryProtocolProtocol
                    
                    val data = suspendCancellableCoroutine { continuation: CancellableContinuation<NSData> ->
                        val ticket = driveService!!.executeQuery(query) { _: GTLRServiceTicket?, result: Any?, error: NSError? ->
                            if (error != null) {
                                continuation.resumeWithException(Exception(error.localizedDescription))
                            } else {
                                val dataObject = result as? GTLRDataObject
                                if (dataObject != null) {
                                    continuation.resume(dataObject.data)
                                } else {
                                    continuation.resumeWithException(Exception("No data received"))
                                }
                            }
                        } as? GTLRServiceTicket
                        
                        continuation.invokeOnCancellation {
                            ticket?.cancelTicket()
                        }
                    }
                    
                    f.outputSink.write(data.toByteArray())
                    f.outputSink.flush()
                    
                    bytesReceived += f.size ?: 0
                    fileReceived++
                    events.emit(RestoreEvent.Progress(f.name, fileReceived, filesToDownload.size, bytesReceived, bytesTotal))
                }

                events.emit(RestoreEvent.Success(filesToDownload))
            } catch (e: CancellationException) {
                events.emit(RestoreEvent.Cancelled)
            } catch (e: Exception) {
                events.emit(RestoreEvent.Failed(e))
            } finally {
                withContext(Dispatchers.Main) { _state.emit(GoogleDriveState.Ready) }
                coroutineContext[Job]?.let { jobs.remove(it) }
            }
        }
        
        jobs.add(job)
        return events
    }

    companion object {
        private const val TAG = "GoogleDriveBackup"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(pinned.addressOf(0), size.toULong())!!
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val byteArray = ByteArray(size)
    if (size > 0) {
        byteArray.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
    return byteArray
}

@OptIn(ExperimentalForeignApi::class)
private fun Source.readAllToNSData(): NSData {
    val data = platform.Foundation.NSMutableData.data() as platform.Foundation.NSMutableData
    val buffer = ByteArray(8192)
    while (true) {
        val read = this.readAtMostTo(buffer, 0, 8192)
        if (read <= 0L) break
        buffer.usePinned { pinned ->
            val chunk = NSData.dataWithBytes(pinned.addressOf(0), read.toULong())!!
            data.appendData(chunk)
        }
    }
    return data
}
