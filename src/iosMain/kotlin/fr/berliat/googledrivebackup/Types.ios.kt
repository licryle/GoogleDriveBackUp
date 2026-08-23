@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package fr.berliat.googledrivebackup

import swiftPMImport.fr.berliat.googledrivebackup.googledrivebackup.GIDGoogleUser
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual typealias Account = GIDGoogleUser

@OptIn(ExperimentalForeignApi::class)
actual typealias GoogleCredentials = GIDGoogleUser
