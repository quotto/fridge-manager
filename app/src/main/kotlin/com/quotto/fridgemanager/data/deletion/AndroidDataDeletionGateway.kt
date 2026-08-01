package com.quotto.fridgemanager.data.deletion

import android.content.Context
import com.quotto.fridgemanager.data.local.InventoryDatabase
import com.quotto.fridgemanager.domain.auth.AuthCoordinator
import com.quotto.fridgemanager.presentation.settings.DataDeletionGateway
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDataDeletionGateway(
    context: Context,
    private val database: InventoryDatabase,
    private val authCoordinator: AuthCoordinator,
    private val listChildren: (File) -> Array<File>? = File::listFiles,
) : DataDeletionGateway {
    private val cacheDirectory = context.applicationContext.cacheDir

    override suspend fun deleteLocalInventory() = withContext(Dispatchers.IO) {
        database.clearAllTables()
    }

    override suspend fun deleteTemporaryImages() = withContext(Dispatchers.IO) {
        val root = cacheDirectory.canonicalFile
        val rootChildren = checkNotNull(listChildren(root)) {
            "Temporary image directory cannot be inspected"
        }
        rootChildren.forEach { candidate ->
            when {
                candidate.isFile && TEMPORARY_PREFIXES.any(candidate.name::startsWith) ->
                    check(candidate.delete() || !candidate.exists()) { "Temporary image deletion failed" }
                candidate.isDirectory && candidate.name == CAPTURE_DIRECTORY ->
                    deleteCaptureDirectory(candidate, root)
            }
        }
    }

    override suspend fun deleteAnonymousUser() {
        authCoordinator.deleteAnonymousUser()
    }

    private fun deleteCaptureDirectory(directory: File, root: File) {
        val canonicalDirectory = directory.canonicalFile
        check(canonicalDirectory.parentFile == root && canonicalDirectory.name == CAPTURE_DIRECTORY)
        val captureFiles = checkNotNull(listChildren(canonicalDirectory)) {
            "Temporary image directory cannot be inspected"
        }
        captureFiles.forEach { file ->
            check(file.isFile && file.canonicalFile.parentFile == canonicalDirectory)
            check(file.delete() || !file.exists()) { "Temporary image deletion failed" }
        }
        check(canonicalDirectory.delete() || !canonicalDirectory.exists()) {
            "Temporary image directory deletion failed"
        }
    }

    private companion object {
        val TEMPORARY_PREFIXES = listOf("image-source-", "image-upload-")
        const val CAPTURE_DIRECTORY = "image-capture"
    }
}
