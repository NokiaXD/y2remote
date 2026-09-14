package com.nokia_xd.y2remote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.nokia_xd.y2remote.bluetooth.BluetoothConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class ArtworkCache(
    context: Context,
    private val connection: BluetoothConnectionManager,
    memoryBytes: Int = 8 * 1024 * 1024
) {
    private val memory = object : LruCache<Long, Bitmap>(memoryBytes) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    // Use persistent internal files directory so artwork persists across restarts and cache clears
    private val diskDir = File(context.filesDir, "artwork").apply { mkdirs() }
    private val inFlight = ConcurrentHashMap<Long, Deferred<Bitmap?>>()
    private val networkSemaphore = Semaphore(2) // Max 2 concurrent RFCOMM artwork fetches

    suspend fun get(trackId: Long): Bitmap? {
        memory.get(trackId)?.let { return it }

        val diskFile = diskDir.resolve("$trackId.jpg")
        if (diskFile.exists()) {
            val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
            if (bmp != null) {
                memory.put(trackId, bmp)
                return bmp
            }
        }

        // Only fetch via RFCOMM if connected
        if (connection.currentState !is BluetoothConnectionManager.ConnectionState.Connected) {
            return null
        }

        val deferred = inFlight.getOrPut(trackId) {
            CoroutineScope(Dispatchers.IO).async {
                networkSemaphore.withPermit {
                    val resp = connection.requestLibraryArtwork(trackId)
                    if (resp != null && resp.base64.isNotEmpty()) {
                        val bytes = runCatching { Base64.decode(resp.base64, Base64.DEFAULT) }.getOrNull()
                        if (bytes != null) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } else null
                    } else null
                }
            }
        }

        val bmp = deferred.await()
        inFlight.remove(trackId)
        if (bmp != null) {
            memory.put(trackId, bmp)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    FileOutputStream(diskDir.resolve("$trackId.jpg")).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
            }
        }
        return bmp
    }

    fun hasDiskArtwork(trackId: Long): Boolean {
        return diskDir.resolve("$trackId.jpg").exists()
    }

    fun copyDiskArtwork(sourceTrackId: Long, targetTrackId: Long): Boolean {
        val src = diskDir.resolve("$sourceTrackId.jpg")
        val dst = diskDir.resolve("$targetTrackId.jpg")
        if (!src.exists() || dst.exists()) return false
        return runCatching {
            src.copyTo(dst, overwrite = false)
            true
        }.getOrDefault(false)
    }

    suspend fun downloadAndCache(trackId: Long): Boolean {
        if (hasDiskArtwork(trackId)) return true
        if (connection.currentState !is BluetoothConnectionManager.ConnectionState.Connected) return false

        val resp = connection.requestLibraryArtwork(trackId)
        if (resp != null && resp.base64.isNotEmpty()) {
            val bytes = runCatching { Base64.decode(resp.base64, Base64.DEFAULT) }.getOrNull() ?: return false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            memory.put(trackId, bmp)
            return runCatching {
                FileOutputStream(diskDir.resolve("$trackId.jpg")).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                true
            }.getOrDefault(false)
        }
        return false
    }

    fun cancel(trackId: Long) {
        inFlight.remove(trackId)?.cancel()
    }

    fun evictAll() {
        memory.evictAll()
    }

    fun clearDiskCache() {
        memory.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
    }
}
