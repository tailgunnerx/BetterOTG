package com.tailgunnerx.betterotg.media

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val durationMs: Long = 0L
)

object GalleryRepository {
    fun loadItems(context: Context): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        items += queryImages(context)
        items += queryVideos(context)
        return items.sortedByDescending { it.dateAddedSec }
    }

    private fun queryImages(context: Context): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val args = arrayOf("%BetterOTG%")
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                list += GalleryItem(
                    id = id,
                    uri = uri,
                    name = cursor.getString(nameCol) ?: "Photo",
                    isVideo = false,
                    dateAddedSec = cursor.getLong(dateCol),
                    sizeBytes = cursor.getLong(sizeCol)
                )
            }
        }
        return list
    }

    private fun queryVideos(context: Context): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?"
        }
        val args = arrayOf("%BetterOTG%")
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                list += GalleryItem(
                    id = id,
                    uri = uri,
                    name = cursor.getString(nameCol) ?: "Video",
                    isVideo = true,
                    dateAddedSec = cursor.getLong(dateCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    durationMs = cursor.getLong(durCol)
                )
            }
        }
        return list
    }

    fun loadThumbnail(context: Context, item: GalleryItem): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
            } else {
                @Suppress("DEPRECATION")
                if (item.isVideo) {
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                } else {
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null
                    )
                }
            }
        }.getOrNull()
    }

    fun deleteItems(context: Context, items: List<GalleryItem>): Int {
        var deleted = 0
        for (item in items) {
            deleted += runCatching { context.contentResolver.delete(item.uri, null, null) }.getOrDefault(0)
        }
        return deleted
    }

    fun shareItem(context: Context, item: GalleryItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (item.isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${item.name}"))
    }
}
