package com.jasongzy.cleanupwatcher

import java.io.InputStream

internal fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val bytes = ByteArray(maxBytes + 1)
    var count = 0
    while (count < bytes.size) {
        val size = read(bytes, count, bytes.size - count)
        if (size < 0) break
        check(size > 0) { "read" }
        count += size
    }
    require(count <= maxBytes) { "limit" }
    return bytes.copyOf(count)
}
