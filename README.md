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
import fr.berliat.googledrivebackup.GoogleDriveBackupInterface
```

In your Fragment or Application's onCreate or onCreateView, initialize it with a listener
```
class ConfigFragment : Fragment(), GoogleDriveBackupInterface {
    private lateinit var gDriveBackUp : GoogleDriveBackup
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ...
        gDriveBackUp = GoogleDriveBackup(this, requireActivity(), getString(R.string.app_name))
        gDriveBackUp.addListener(this)
        ...
```

Then, when you want to start the action, call login()
```
        gDriveBackUp.login()
```

The callbacks will lead you to the rest, in particular onReady() is a good place to have the files
listed for upload, for example:
```
    override fun onReady() {
        val sourcePath =
            "${requireContext().filesDir.path}/../databases/${ChineseWordsDatabase.DATABASE_FILE}"

        gDriveBackUp.backup(
            listOf(GoogleDriveBackupFile(
                "database.sqlite",
                "application/octet-stream",
                FileInputStream(sourcePath),
                File(sourcePath).length()))
        )
    }
```

Callbacks at the moment:
```
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
```