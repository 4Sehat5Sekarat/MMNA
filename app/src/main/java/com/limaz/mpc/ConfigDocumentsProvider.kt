package com.limaz.mpc

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Set by MprisService. The provider and the service run in the same process,
 * so a plain static flag is enough.
 */
object AppState {
    @Volatile var running = false
}

/** Exposes filesDir/config as a read/write root. Hidden while the app is running. */
class ConfigDocumentsProvider : DocumentsProvider() {

    private val rootId = "root"
    private val baseDir: File by lazy { File(context!!.filesDir, "config") }
    private val rootTitle: String by lazy { context!!.getString(R.string.root_title) }

    private val rootColumns = arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID
    )
    private val docColumns = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
    )

    override fun onCreate(): Boolean {
        baseDir.mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: rootColumns)
        // Root is hidden while the app is running
        if (!AppState.running) {
            result.newRow()
                .add(Root.COLUMN_ROOT_ID, rootId)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_TITLE, rootTitle)
                .add(Root.COLUMN_DOCUMENT_ID, rootId)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        if (AppState.running) throw FileNotFoundException("app is running")
        val result = MatrixCursor(projection ?: docColumns)
        includeFile(result, documentId, fileForId(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<String>?, sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: docColumns)
        if (AppState.running) return result
        fileForId(parentDocumentId).listFiles()?.forEach { f ->
            includeFile(result, "$parentDocumentId/${f.name}", f)
        }
        return result
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (AppState.running) throw FileNotFoundException("app is running")
        return ParcelFileDescriptor.open(fileForId(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String, mimeType: String, displayName: String
    ): String {
        if (AppState.running) throw FileNotFoundException("app is running")
        val file = File(fileForId(parentDocumentId), displayName)
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        return "$parentDocumentId/$displayName"
    }

    override fun deleteDocument(documentId: String) {
        if (AppState.running) throw FileNotFoundException("app is running")
        if (documentId == rootId) throw FileNotFoundException("root cannot be deleted")
        val file = fileForId(documentId)
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!ok) throw FileNotFoundException("failed to delete $documentId")
    }

    /** "root" maps to baseDir, "root/config.json" maps to baseDir/config.json. Blocks path escape. */
    private fun fileForId(docId: String): File {
        val rel = docId.removePrefix(rootId).trimStart('/')
        val file = File(baseDir, rel).canonicalFile
        if (!file.path.startsWith(baseDir.canonicalPath)) throw FileNotFoundException()
        return file
    }

    private fun includeFile(cursor: MatrixCursor, docId: String, file: File) {
        val isDir = file.isDirectory
        val baseFlags = if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, docId)
            .add(Document.COLUMN_DISPLAY_NAME, if (docId == rootId) rootTitle else file.name)
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_MIME_TYPE, when {
                isDir -> Document.MIME_TYPE_DIR
                file.extension == "json" -> "application/json"
                else -> "text/plain"
            })
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, baseFlags or Document.FLAG_SUPPORTS_DELETE)
    }
}