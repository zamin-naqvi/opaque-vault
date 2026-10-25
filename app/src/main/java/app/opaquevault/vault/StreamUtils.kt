package app.opaquevault.vault

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Tink closes its sink on close(); this keeps the real stream open so it can be fsynced first. */
internal class NonClosingOutputStream(target: OutputStream) : FilterOutputStream(target) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}

/** Exposes exactly [limit] bytes of [source] and never closes it. */
internal class LimitedInputStream(source: InputStream, private var remaining: Long) : FilterInputStream(source) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val count = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        if (skipped > 0) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun close() = Unit
}
