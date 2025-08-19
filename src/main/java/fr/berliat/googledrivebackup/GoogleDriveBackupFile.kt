package fr.berliat.googledrivebackup

import java.io.InputStream

class GoogleDriveBackupFile(val name: String,
                            val mimeType: String,
                            val contentStream: InputStream,
                            val size: Long) {
}