package app.opaquevault.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import app.opaquevault.vault.VaultMediaReader
import java.io.IOException
import kotlin.math.min

/**
 * Feeds ExoPlayer directly from the encrypted vault. Each open() decrypts only the authenticated
 * segments around the requested position, so seeking works and decrypted video never touches storage.
 */
@OptIn(UnstableApi::class)
class EncryptedVaultDataSource(
    private val openReader: () -> VaultMediaReader,
) : BaseDataSource(/* isNetwork = */ false) {
    private var reader: VaultMediaReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val source = try {
            openReader()
        } catch (error: Exception) {
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        reader = source
        if (dataSpec.position > source.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            min(dataSpec.length, source.size - position)
        } else {
            source.size - position
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = reader ?: return C.RESULT_END_OF_INPUT
        val count = try {
            source.read(position, buffer, offset, min(length.toLong(), bytesRemaining).toInt())
        } catch (error: IOException) {
            // A segment failed authentication or the vault locked: stop rather than play bad data.
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (count <= 0) return C.RESULT_END_OF_INPUT
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
