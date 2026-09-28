# GoogleDriveBackUp

## What is it?
GoogleDriveBackUp, as it stands, is a very simple library to handle your Android app backup onto the
Google Drive of your users.

It stems from the need of a simple example to solve this use case. Example that I couldn't find 
anywhere online, including Google's documentation, despite many people struggling to find an answer.

### Why use GoogleDriveBackUp ?
It does so while respecting the privacy of users.
It does so in a way you can simply implement in your app.
It does so with the latest Google non-deprecated APIs (as of Aug 2025).

### What's supported
- DRIVE_APPDATA scope only - meaning you never get the user's Google account's data in your hands
- Upload of any files given
- Callback for all events, including progress of upload

### What's not supported
- Download (as of Aug 20th 2025 - coming soon)
- Reconciliation strategy, that's up to you to determine which version if more up to date,
especially if the user has several devices.

## How to?
### How to set-up in Google Cloud
Before using this library, you need to:
- [Create a Google Cloud project](https://developers.google.com/workspace/guides/create-project)
- [Enable the Drive API](https://console.cloud.google.com/flows/enableapi?apiid=drive.googleapis.com) with the DRIVE_APPDATA scope.
- [Create 2 ClientIds: one for the DEBUG, and one for the RELEASE version of your app](https://developers.google.com/workspace/guides/create-credentials)

### How to install

First, pull the library from GitHub
```
$ cd MyAndroidRootProjectFolder
$ git submodule add https://github.com/licryle/GoogleDriveBackUp.git googledrivebackup
```

Add a dependency to your Root folder build.grade.kts (the app one)
```
depedencies {
    implementation(project(":googledrivebackup"))
}
```

### How to use
Very simply, import the classes:

```
import fr.berliat.googledrivebackup.GoogleDriveBackup
import fr.berliat.googledrivebackup.GoogleDriveBackupFile
```

Construct it anywhere, it is cheap and only needs your app name. Then bind it
to your Activity's `onCreate` (before STARTED), so it can register its
account-picker launcher. Re-attach after activity recreation:

```
class MainActivity : FragmentActivity() {
    private lateinit var gDriveBackup: GoogleDriveBackup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ...
        gDriveBackup = GoogleDriveBackup(getString(R.string.app_name))
        gDriveBackup.attachActivity(this)
        ...
```

The instance holds no Activity reference: all Drive calls run on the
application context, only the account picker and Play-services resolution
use the attached Activity at call time.

Then, observe the state and call login(). `login(onlyFromCache = true)` only
succeeds if an account was already authorized, otherwise it leads to
`NoAccountSelected` and you should call `login()` to show the account picker:
```
        lifecycleScope.launch {
            gDriveBackup.state.collect { state ->
                when (state) {
                    is GoogleDriveState.Ready -> { /* list files, enable backup */ }
                    is GoogleDriveState.NoAccountSelected -> gDriveBackup.login()
                    ...
                }
            }
        }

        gDriveBackup.login(onlyFromCache = true)
```

Once `Ready`, back up or restore. Both return a `SharedFlow` of events,
including progress:
```
        gDriveBackup.login {
            val sourceFile = File("${filesDir.path}/file_to_backup")

            gDriveBackup.backup(
                listOf(GoogleDriveBackupFile.UploadFile(
                    "database.sqlite",
                    FileInputStream(sourceFile).asInputStream(),
                    "application/octet-stream",
                    sourceFile.length())),
                onlyKeepMostRecent = true)
        }
```
or
```
        gDriveBackup.login {
            val destFile = File("${cacheDir.path}/restore/restored_file")
            destFile.parentFile?.mkdirs()

            gDriveBackup.restore(
                listOf(GoogleDriveBackupFile.DownloadFile(
                    "database.sqlite",
                    FileOutputStream(destFile).asOutputStream()
                ))
            )
        }
```

States and events at the moment:
```
sealed class GoogleDriveState {
    object LoggedOut
    object Ready
    object Busy
    data class NoGoogleAPI(val exception: Exception)
    object NoAccountSelected
    data class ScopeDenied(val exception: Exception)
}

sealed class BackupEvent {
    object Started
    data class Progress(val fileName: String, val fileIndex: Int, val fileCount: Int, val bytesSent: Long, val bytesTotal: Long)
    object Success
    object Cancelled
    data class Failed(val exception: Exception)
}

sealed class RestoreEvent {
    object Empty
    object Started
    data class Progress(val fileName: String, val fileIndex: Int, val fileCount: Int, val bytesReceived: Long, val bytesTotal: Long)
    data class Success(val files: List<GoogleDriveBackupFile.DownloadFile>)
    object Cancelled
    data class Failed(val exception: Exception)
}
```