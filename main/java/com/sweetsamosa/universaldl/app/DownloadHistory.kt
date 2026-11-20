package com.sweetsamosa.universaldl.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

data class DownloadedItem(
    val id: String,
    val name: String,
    val path: String,
    val uri: Uri,
    val size: Long,
    val dateAdded: Long,
    val kind: Kind,
    val mimeType: String
)

val DownloadedItem.extTag: String
    get() = path.substringAfterLast('.', "").uppercase().ifBlank { kind.name }

val DownloadedItem.qualityTag: String?
    get() {
        if (kind == Kind.AUDIO) {
            val extension = extTag
            if (extension in setOf("FLAC", "WAV", "ALAC")) return "LOSSLESS"
            return null
        }
        if (kind == Kind.VIDEO) {
            val lower = name.lowercase()
            when {
                lower.contains("2160") || lower.contains("4k") -> return "4K"
                lower.contains("1440") || lower.contains("2k") -> return "2K"
                lower.contains("1080") -> return "1080p"
                lower.contains("720") -> return "720p"
                lower.contains("480") -> return "480p"
                lower.contains("360") -> return "360p"
            }
            return runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(path)
                val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                retriever.release()
                val h = hStr?.toIntOrNull() ?: 0
                when {
                    h >= 2160 -> "4K"
                    h >= 1440 -> "2K"
                    h >= 1080 -> "1080p"
                    h >= 720 -> "720p"
                    h >= 480 -> "480p"
                    h >= 360 -> "360p"
                    else -> null
                }
            }.getOrNull()
        }
        return null
    }

object DownloadHistory {

    fun load(context: Context): List<DownloadedItem> {
        val items = mutableListOf<DownloadedItem>()
        val seenPaths = mutableSetOf<String>()

        // 1. Query MediaStore for Media & Downloads
        val projections = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Kind.VIDEO,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Kind.IMAGE,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Kind.AUDIO,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to Kind.VIDEO
        )

        for ((baseUri, defaultKind) in collections) {
            try {
                val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                val selectionArgs = arrayOf("%UniversalDL%")
                context.contentResolver.query(
                    baseUri,
                    projections,
                    selection,
                    selectionArgs,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val path = if (dataCol != -1) cursor.getString(dataCol) else ""
                        if (path.isNotBlank() && seenPaths.add(path)) {
                            val id = if (idCol != -1) cursor.getLong(idCol) else 0L
                            val uri = ContentUris.withAppendedId(baseUri, id)
                            val name = if (nameCol != -1) cursor.getString(nameCol) ?: File(path).name else File(path).name
                            val size = if (sizeCol != -1) cursor.getLong(sizeCol) else File(path).length()
                            val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000 else System.currentTimeMillis()
                            val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "" else ""

                            val kind = determineKind(path, mime, defaultKind)
                            items.add(DownloadedItem(id.toString(), name, path, uri, size, date, kind, mime.ifBlank { getMime(path) }))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Direct file scan of UniversalDL folders (backup if MediaStore hasn't indexed yet)
        val folders = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UniversalDL"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "UniversalDL"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "UniversalDL"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "UniversalDL")
        )

        for (dir in folders) {
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.extension !in setOf("tmp", "part", "ytdl") && seenPaths.add(file.absolutePath)) {
                    val uri = getUriForFile(context, file)
                    val kind = determineKind(file.absolutePath, "", Kind.VIDEO)
                    items.add(
                        DownloadedItem(
                            id = file.hashCode().toString(),
                            name = file.name,
                            path = file.absolutePath,
                            uri = uri,
                            size = file.length(),
                            dateAdded = file.lastModified(),
                            kind = kind,
                            mimeType = getMime(file.absolutePath)
                        )
                    )
                }
            }
        }

        return items.sortedByDescending { it.dateAdded }
    }

    fun open(context: Context, item: DownloadedItem) {
        try {
            val uri = getUriForItem(context, item)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, item: DownloadedItem) {
        try {
            val uri = getUriForItem(context, item)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${item.name}"))
        } catch (_: Exception) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    fun delete(context: Context, item: DownloadedItem) {
        deleteMultiple(context, listOf(item))
    }

    fun shareMultiple(context: Context, items: List<DownloadedItem>) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            share(context, items.first())
            return
        }
        try {
            val uris = ArrayList(items.map { getUriForItem(context, it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${items.size} files"))
        } catch (_: Exception) {
            Toast.makeText(context, "Could not share selected files", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteMultiple(context: Context, items: List<DownloadedItem>) {
        val resolver = context.contentResolver
        items.forEach { item ->
            // 1. Physical file on disk
            runCatching {
                val f = File(item.path)
                if (f.exists()) f.delete()
            }
            // 2. MediaStore row by URI
            if (item.uri.scheme == "content" && !item.uri.authority.orEmpty().contains("fileprovider")) {
                runCatching { resolver.delete(item.uri, null, null) }
            }
            // 3. MediaStore row by DATA path fallback
            try {
                val selection = "${MediaStore.MediaColumns.DATA} = ?"
                val selectionArgs = arrayOf(item.path)
                val collections = listOf(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                )
                collections.forEach { collectionUri ->
                    runCatching { resolver.delete(collectionUri, selection, selectionArgs) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun getUriForItem(context: Context, item: DownloadedItem): Uri {
        return if (item.uri.scheme == "content") item.uri else getUriForFile(context, File(item.path))
    }

    private fun getUriForFile(context: Context, file: File): Uri {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun determineKind(path: String, mime: String, fallback: Kind): Kind {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "avi") -> Kind.VIDEO
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> Kind.IMAGE
            mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "opus", "flac", "wav", "aac", "ogg") -> Kind.AUDIO
            else -> fallback
        }
    }

    private fun getMime(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp4", "mkv", "webm" -> "video/$ext"
                "mp3", "m4a", "opus", "flac", "wav", "aac" -> "audio/$ext"
                "jpg", "jpeg", "png", "webp", "gif" -> "image/$ext"
                else -> "*/*"
            }
    }
}
