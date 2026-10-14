package com.perch.cam.backup

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File as JavaFile

/** Outcome of talking to Drive, with the cases the app has to react to differently. */
sealed interface DriveResult<out T> {
    data class Success<T>(val value: T) : DriveResult<T>

    /** The user must (re-)authorize. [consentIntent] opens Google's consent screen when available. */
    data class AuthRequired(val consentIntent: Intent?) : DriveResult<Nothing>

    /** The Drive account has no space left; retrying will not help until space is freed. */
    object QuotaExceeded : DriveResult<Nothing>

    /** Anything transient (network, 5xx). Worth retrying later. */
    data class Failed(val message: String) : DriveResult<Nothing>
}

data class DriveQuota(val usedBytes: Long, val totalBytes: Long?)

data class DriveAccountInfo(val email: String?, val quota: DriveQuota?)

class DriveApiHelper(private val context: Context) {

    private val tag = "Perch:DriveApiHelper"
    private var driveService: Drive? = null
    private var perchFolderId: String? = null

    companion object {
        const val FOLDER_NAME = "Perch Recordings"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val QUOTA_REASONS = setOf("storageQuotaExceeded", "quotaExceeded", "teamDriveFileLimitExceeded")

        /** Builds the credential used both for the account picker and for API calls. */
        fun credential(context: Context): GoogleAccountCredential =
            GoogleAccountCredential.usingOAuth2(context.applicationContext, listOf(DriveScopes.DRIVE_FILE))
    }

    fun initializeWithAccount(accountName: String) {
        val credential = credential(context).apply { selectedAccountName = accountName }
        driveService = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Perch Security Camera")
            .build()
        perchFolderId = null
        Log.d(tag, "Google Drive service initialized for account: $accountName")
    }

    val isInitialized: Boolean
        get() = driveService != null

    /**
     * Makes a cheap authenticated call. This is what triggers Google's consent screen the first
     * time, so the Settings screen runs it right after the user picks an account.
     */
    suspend fun verifyAccess(): DriveResult<DriveAccountInfo> = call {
        val about = requireService().about().get()
            .setFields("user(emailAddress),storageQuota(limit,usage)")
            .execute()
        DriveAccountInfo(
            email = about.user?.emailAddress,
            quota = about.storageQuota?.let { q ->
                DriveQuota(usedBytes = q.usage ?: 0L, totalBytes = q.limit)
            }
        )
    }

    suspend fun fetchQuota(): DriveResult<DriveQuota?> = call {
        val q = requireService().about().get().setFields("storageQuota(limit,usage)").execute().storageQuota
        q?.let { DriveQuota(usedBytes = it.usage ?: 0L, totalBytes = it.limit) }
    }

    /** Uploads [localFile] into the "Perch Recordings" folder and returns the new Drive file id. */
    suspend fun uploadVideoFile(localFile: JavaFile): DriveResult<String> = call {
        val service = requireService()
        // A failure to find or create the folder aborts the upload: dumping recordings in the
        // Drive root would be worse than waiting for the next attempt.
        val folderId = getOrCreatePerchFolder(service)
        val metadata = File().apply {
            name = localFile.name
            parents = listOf(folderId)
        }
        val uploaded = service.files().create(metadata, FileContent("video/mp4", localFile))
            .setFields("id")
            .execute()
        Log.d(tag, "Uploaded ${localFile.name} to Drive, ID: ${uploaded.id}")
        uploaded.id
    }

    private fun getOrCreatePerchFolder(service: Drive): String {
        perchFolderId?.let { return it }

        val query = "mimeType = '$FOLDER_MIME' and name = '$FOLDER_NAME' and trashed = false"
        val existing = service.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
            .files
            ?.firstOrNull()
        if (existing != null) {
            perchFolderId = existing.id
            return existing.id
        }

        val folderMetadata = File().apply {
            name = FOLDER_NAME
            mimeType = FOLDER_MIME
        }
        val created = service.files().create(folderMetadata).setFields("id").execute()
        perchFolderId = created.id
        Log.d(tag, "Created Google Drive folder '$FOLDER_NAME' with ID ${created.id}")
        return created.id
    }

    private fun requireService(): Drive =
        driveService ?: throw IllegalStateException("DriveApiHelper not initialized with an account")

    private suspend fun <T> call(block: () -> T): DriveResult<T> = withContext(Dispatchers.IO) {
        try {
            DriveResult.Success(block())
        } catch (e: Exception) {
            classify(e)
        }
    }

    private fun classify(e: Exception): DriveResult<Nothing> = when (e) {
        is UserRecoverableAuthIOException -> {
            Log.w(tag, "Drive needs user consent")
            DriveResult.AuthRequired(e.intent)
        }
        is GoogleAuthIOException -> {
            Log.w(tag, "Drive authorization failed", e)
            DriveResult.AuthRequired(null)
        }
        is GoogleJsonResponseException -> when {
            e.statusCode == 401 -> DriveResult.AuthRequired(null)
            e.statusCode == 403 && e.details?.errors?.any { it.reason in QUOTA_REASONS } == true ->
                DriveResult.QuotaExceeded
            else -> {
                Log.e(tag, "Drive API error ${e.statusCode}", e)
                DriveResult.Failed("Drive error ${e.statusCode}: ${e.details?.message ?: e.statusMessage}")
            }
        }
        is IOException -> {
            Log.w(tag, "Drive network error", e)
            DriveResult.Failed(e.message ?: "Network error")
        }
        else -> {
            Log.e(tag, "Unexpected Drive error", e)
            DriveResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
