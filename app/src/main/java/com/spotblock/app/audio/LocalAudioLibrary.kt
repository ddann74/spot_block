package com.spotblock.app.audio

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

/** Outcome of trying to enumerate a configured ad-music folder - distinct
  * states so a caller (and Stats/Diagnostic Log) can say exactly why
  * nothing played, never collapsing different problems into one vague
  * message (same "never lie about the outcome" principle as
  * SkipOutcome/DownloadOutcome/MuteOutcome). [Files] is the only state
  * playback can actually happen from. */
sealed class LocalAudioFolderResult {
    data class Files(val uris: List<Uri>) : LocalAudioFolderResult()
    object PermissionRevoked : LocalAudioFolderResult()
    object FolderEmpty : LocalAudioFolderResult()
}

/**
 * Enumerates the audio files in a user-picked folder (a Storage Access
 * Framework tree Uri from ACTION_OPEN_DOCUMENT_TREE) - the queue for
 * local-music-during-ads (docs/TODO.md's resolved queue design: folder,
 * filename order).
 *
 * Uses the raw android.provider.DocumentsContract API directly rather
 * than the androidx.documentfile convenience wrapper most SAF code
 * reaches for. DocumentFile is a thin wrapper over exactly these same
 * calls - writing the raw version was a deliberate choice, not an
 * oversight: it let this file be compiled and verified against a real
 * Android API jar in the sandbox this was built in, which could reach
 * the core framework's DocumentsContract class (bundled with the
 * platform) but not the separate androidx.documentfile artifact - both
 * androidx.documentfile and androidx.media3 (used elsewhere in this
 * feature) live only on Google's Maven repo, which this sandbox cannot
 * reach; see AdMusicPlaybackService's doc comment for the parts of this
 * feature that could NOT be verified this same way.
 *
 * Re-enumerated fresh each time it's needed, not cached, since files can
 * be added/removed on disk between ad breaks.
 */
object LocalAudioLibrary {

    fun listAudioFiles(context: Context, folderUri: Uri): LocalAudioFolderResult {
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == folderUri && it.isReadPermission
        }
        if (!hasPermission) return LocalAudioFolderResult.PermissionRevoked

        val treeDocumentId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocumentId)

        val entries = mutableListOf<Pair<String, String>>() // (displayName, documentId) of audio files only
        val cursor: Cursor? = try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )
        } catch (e: Exception) {
            // Folder deleted, provider gone, or some other real-world SAF
            // failure - treated the same as "nothing found" rather than
            // crashing an accessibility service over a missing folder.
            null
        }

        cursor?.use {
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (it.moveToNext()) {
                val mime = it.getString(mimeIndex) ?: continue
                if (!mime.startsWith("audio/")) continue
                val docId = it.getString(idIndex) ?: continue
                val name = it.getString(nameIndex).orEmpty()
                entries.add(name to docId)
            }
        }

        val sortedUris = entries
            .sortedBy { (name, _) -> name }
            .map { (_, docId) -> DocumentsContract.buildDocumentUriUsingTree(folderUri, docId) }

        return if (sortedUris.isEmpty()) LocalAudioFolderResult.FolderEmpty else LocalAudioFolderResult.Files(sortedUris)
    }
}
