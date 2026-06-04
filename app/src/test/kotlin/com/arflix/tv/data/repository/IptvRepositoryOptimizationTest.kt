package com.arflix.tv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Constructor
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class IptvRepositoryOptimizationTest {

    // Helper to instantiate private class BackslashEscapeSanitizingInputStream using reflection
    private fun createSanitizingStream(input: InputStream): InputStream {
        val clazz = Class.forName("com.arflix.tv.data.repository.IptvRepository\$BackslashEscapeSanitizingInputStream")
        val constructor = clazz.getDeclaredConstructor(InputStream::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(input) as InputStream
    }

    @Test
    fun testBackslashSanitizationBulkRead() {
        // \" -> "
        // \n -> newline
        // control chars (0x01) -> space (0x20)
        // regular chars not escaped (e.g. \y) -> keep y
        val inputStr = "Hello\\\"World\\nTest\\yDone\u0001Control"
        val expectedStr = "Hello\"World\nTestyDone Control"
        
        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)
        
        val buffer = ByteArray(100)
        val readCount = sanitizingStream.read(buffer, 0, buffer.size)
        
        val result = String(buffer, 0, readCount, Charsets.UTF_8)
        assertEquals(expectedStr, result)
    }

    @Test
    fun testBackslashSanitizationByteByByteRead() {
        val inputStr = "Hello\\\"World\\nTest\\yDone\u0001Control"
        val expectedStr = "Hello\"World\nTestyDone Control"
        
        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)
        
        val sb = StringBuilder()
        while (true) {
            val b = sanitizingStream.read()
            if (b == -1) break
            sb.append(b.toChar())
        }
        
        assertEquals(expectedStr, sb.toString())
    }

    @Test
    fun testBackslashSanitizationBoundary() {
        // Backslash at the boundary of buffer size
        val inputStr = "A\\nB" // length 4
        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)
        
        // Read exactly up to the backslash first (2 bytes: 'A', '\')
        // Actually, let's read with buffer size 2
        val buffer = ByteArray(2)
        val read1 = sanitizingStream.read(buffer, 0, 2)
        assertEquals(2, read1)
        // Since '\' is followed by 'n', our stream reads lookahead 'n' from the underlying stream,
        // maps it to '\n' and puts it into buffer[1]. So buffer should have ['A', '\n'].
        assertEquals('A', buffer[0].toChar())
        assertEquals('\n', buffer[1].toChar())
        
        // Next read should get the remaining 'B'
        val read2 = sanitizingStream.read(buffer, 0, 2)
        assertEquals(1, read2)
        assertEquals('B', buffer[0].toChar())
    }

    @Test
    fun testBackslashSanitizationTrailingBackslash() {
        val inputStr = "A\\" // Traling backslash
        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)
        
        val buffer = ByteArray(5)
        val read = sanitizingStream.read(buffer, 0, 5)
        assertEquals(2, read)
        assertEquals('A', buffer[0].toChar())
        assertEquals('\\', buffer[1].toChar())
    }

    @Test
    fun testConcurrentPlaylistLoadThreadSafety() = runBlocking(Dispatchers.Default) {
        // Simulate synchronized list modification and copying concurrently
        val list = Collections.synchronizedList(mutableListOf<Int>())
        val exceptionCount = AtomicInteger(0)
        
        // Coroutines writing to the list and reading from it (calling toList())
        val writers = (1..10).map { id ->
            async {
                repeat(1000) {
                    list.add(it)
                }
            }
        }
        
        val readers = (1..10).map {
            async {
                repeat(1000) {
                    try {
                        // This must be synchronized to prevent ConcurrentModificationException
                        val currentCopy = synchronized(list) { list.toList() }
                        // Use the copy to prevent compiler optimizations dropping it
                        if (currentCopy.size < 0) {
                            fail()
                        }
                    } catch (e: ConcurrentModificationException) {
                        exceptionCount.incrementAndGet()
                    }
                }
            }
        }
        
        writers.awaitAll()
        readers.awaitAll()
        
        assertEquals("Should not encounter any ConcurrentModificationException", 0, exceptionCount.get())
    }
}
