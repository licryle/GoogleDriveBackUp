@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package fr.berliat.googledrivebackup

import cocoapods.GoogleSignIn.GIDGoogleUser
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual typealias Account = GIDGoogleUser

@OptIn(ExperimentalForeignApi::class)
actual typealias GoogleCredentials = GIDGoogleUser
