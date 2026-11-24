package cloud.micoten.camlink.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import cloud.micoten.camlink.camera.CameraFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Publishes only completed camera downloads. RAW files go to Downloads. */
object MediaStoreSaver {
    private const val DIR = "CAMLink"

    data class Saved(val uri: Uri, val name: String, val skipped: Boolean = false)

    fun mimeOf(file: CameraFile): String =
        when {
            file.isCr3 -> "image/x-canon-cr3"
            file.isRaw -> "image/x-canon-cr2"
            file.name.endsWith(".mov", ignoreCase = true) || file.format in 0xB104..0xB105 ->
                "video/quicktime"
            file.isMovie -> "video/mp4"
            file.format == 0x3801 || file.name.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
            file.format == 0x380D -> "image/tiff"
            else -> "application/octet-stream"
        }

    suspend fun save(
        context: Context,
        name: String,
        mime: String,
        size: Long,
        write: suspend (OutputStream) -> Long,
    ): Saved {
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") { "相机文件名无效" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, safeName, mime, size, write)
        } else {
            saveToPublicDir(context, safeName, mime, size, write)
        }
    }

    private fun isAlbumImage(mime: String) =
        mime.startsWith("image/") && !mime.startsWith("image/x-canon-")

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveViaMediaStore(
        context: Context,
        name: String,
        mime: String,
        size: Long,
        write: suspend (OutputStream) -> Long,
    ): Saved {
        val isVideo = mime.startsWith("video/")
        val isImage = isAlbumImage(mime)
        val collection =
            when {
                isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val base =
            if (isVideo || isImage) Environment.DIRECTORY_DCIM else Environment.DIRECTORY_DOWNLOADS
        val relative = "$base/$DIR/"
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("手机未能创建保存条目")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("手机未能打开保存条目")
            val (written, hash) = writeAndCheck(stream, size, write)
            val existing = findMediaCopy(context, collection, uri, relative, written, hash)
            if (existing != null) {
                if (resolver.delete(uri, null, null) != 1) throw IOException("手机未能清理重复的保存条目")
                return existing
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("手机未能发布已保存的文件")
            val savedName = runCatching {
                resolver
                    .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
            }
                .getOrNull()
            return Saved(uri, savedName ?: name)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun findMediaCopy(
        context: Context,
        collection: Uri,
        pending: Uri,
        relative: String,
        written: Long,
        hash: ByteArray,
    ): Saved? {
        val resolver = context.contentResolver
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val where =
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        return runCatching {
            resolver
                .query(collection, cols, where, arrayOf(relative, written.toString()), null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val candidate = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        if (candidate == pending) continue
                        if (sameContent(hash) { resolver.openInputStream(candidate) }) {
                            return@use Saved(candidate, cursor.getString(1), skipped = true)
                        }
                    }
                    null
                }
        }
            .getOrNull()
    }

    private suspend fun saveToPublicDir(
        context: Context,
        name: String,
        mime: String,
        size: Long,
        write: suspend (OutputStream) -> Long,
    ): Saved {
        val base =
            if (isAlbumImage(mime) || mime.startsWith("video/")) {
                Environment.DIRECTORY_DCIM
            } else {
                Environment.DIRECTORY_DOWNLOADS
            }
        val dir = File(Environment.getExternalStoragePublicDirectory(base), DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("手机未能创建保存目录")
        val temporary = File.createTempFile(".download-", ".part", dir)
        try {
            val (written, hash) = writeAndCheck(temporary.outputStream(), size, write)
            val existing =
                dir.listFiles()?.firstOrNull {
                    it != temporary &&
                        it.isFile &&
                        it.length() == written &&
                        sameContent(hash) { it.inputStream() }
                }
            if (existing != null)
                return Saved(Uri.fromFile(existing), existing.name, skipped = true)
            val target = publishFile(temporary, dir, name)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(mime),
                null,
            )
            return Saved(Uri.fromFile(target), target.name)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private suspend fun writeAndCheck(
        output: OutputStream,
        size: Long,
        write: suspend (OutputStream) -> Long,
    ): Pair<Long, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val written = DigestOutputStream(output, digest).use { write(it) }
        if (written <= 0L || (size in 1L until 0xFFFFFFFFL && written != size)) {
            throw IOException("保存长度异常（已写入 $written 字节）")
        }
        return written to digest.digest()
    }

    private fun sameContent(hash: ByteArray, open: () -> InputStream?): Boolean = runCatching {
        val input = open() ?: return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256")
        input.use {
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val size = it.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        digest.digest().contentEquals(hash)
    }
        .getOrDefault(false)

    private fun publishFile(temporary: File, directory: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (index in 0..999) {
            val target = File(directory, if (index == 0) name else "$stem-$index$ext")
            try {
                Files.move(temporary.toPath(), target.toPath())
                return target
            } catch (_: FileAlreadyExistsException) {
                continue
            }
        }
        throw IOException("文件名重复过多，无法保存")
    }
}
