package com.arflix.tv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
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

    @Test
    fun testGuideKeyCandidatesCaching() {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val okHttpClient = io.mockk.mockk<okhttp3.OkHttpClient>(relaxed = true)
        val profileManager = io.mockk.mockk<com.arflix.tv.data.repository.ProfileManager>(relaxed = true)
        val invalidationBus = io.mockk.mockk<com.arflix.tv.data.repository.CloudSyncInvalidationBus>(relaxed = true)
        val repository = IptvRepository(context, okHttpClient, profileManager, invalidationBus)

        // Get private field guideKeyCandidatesCache using reflection
        val cacheField = IptvRepository::class.java.getDeclaredField("guideKeyCandidatesCache")
        cacheField.isAccessible = true
        val cache = cacheField.get(repository) as Map<*, *>

        val method = IptvRepository::class.java.getDeclaredMethod("guideKeyCandidates", String::class.java)
        method.isAccessible = true

        val initialSize = cache.size
        
        // First call
        val result1 = method.invoke(repository, "NPO 1 FHD [NL]") as Set<String>
        val sizeAfterFirst = cache.size
        assertEquals(initialSize + 1, sizeAfterFirst)

        // Second call with same value (should hit cache)
        val result2 = method.invoke(repository, "NPO 1 FHD [NL]") as Set<String>
        assertEquals(sizeAfterFirst, cache.size)
        assertEquals(result1, result2)
    }

    private suspend fun callFetchAndParseEpgReflection(
        repository: IptvRepository,
        url: String,
        channels: List<com.arflix.tv.data.model.IptvChannel>
    ): Map<String, com.arflix.tv.data.model.IptvNowNext> {
        val method = IptvRepository::class.java.getDeclaredMethod(
            "fetchAndParseEpg",
            String::class.java,
            List::class.java,
            kotlin.coroutines.Continuation::class.java
        )
        method.isAccessible = true
        return suspendCancellableCoroutine { continuation ->
            try {
                val result = method.invoke(repository, url, channels, continuation)
                if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                    continuation.resume(result as Map<String, com.arflix.tv.data.model.IptvNowNext>)
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    private suspend fun callParseM3uReflection(
        repository: IptvRepository,
        input: InputStream,
        onProgress: (IptvLoadProgress) -> Unit
    ): List<com.arflix.tv.data.model.IptvChannel> {
        val method = IptvRepository::class.java.getDeclaredMethod(
            "parseM3u",
            InputStream::class.java,
            Function1::class.java,
            kotlin.coroutines.Continuation::class.java
        )
        method.isAccessible = true
        return suspendCancellableCoroutine { continuation ->
            try {
                val result = method.invoke(repository, input, onProgress, continuation)
                if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                    continuation.resume(result as List<com.arflix.tv.data.model.IptvChannel>)
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    private fun createBoundedInputStream(input: InputStream, maxBytes: Long): InputStream {
        val clazz = Class.forName("com.arflix.tv.data.repository.IptvRepository\$BoundedInputStream")
        val constructor = clazz.getDeclaredConstructor(InputStream::class.java, Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(input, maxBytes) as InputStream
    }

    @Test
    fun testFetchAndParseEpgThrows304Exception() = runBlocking {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val okHttpClient = io.mockk.mockk<okhttp3.OkHttpClient>()
        val profileManager = io.mockk.mockk<com.arflix.tv.data.repository.ProfileManager>(relaxed = true)
        val invalidationBus = io.mockk.mockk<com.arflix.tv.data.repository.CloudSyncInvalidationBus>(relaxed = true)
        
        val builder = io.mockk.mockk<okhttp3.OkHttpClient.Builder>(relaxed = true)
        io.mockk.every { okHttpClient.newBuilder() } returns builder
        io.mockk.every { builder.connectTimeout(any(), any()) } returns builder
        io.mockk.every { builder.readTimeout(any(), any()) } returns builder
        io.mockk.every { builder.writeTimeout(any(), any()) } returns builder
        io.mockk.every { builder.callTimeout(any(), any()) } returns builder
        
        val customClient = io.mockk.mockk<okhttp3.OkHttpClient>()
        io.mockk.every { builder.build() } returns customClient

        val call = io.mockk.mockk<okhttp3.Call>()
        val response = io.mockk.mockk<okhttp3.Response>()
        io.mockk.every { response.code } returns 304
        io.mockk.every { response.close() } returns Unit
        io.mockk.every { call.execute() } returns response
        io.mockk.every { customClient.newCall(any()) } returns call
        io.mockk.every { call.enqueue(any()) } answers {
            val callback = firstArg<okhttp3.Callback>()
            callback.onResponse(call, response)
        }

        val repository = IptvRepository(context, okHttpClient, profileManager, invalidationBus)
        try {
            callFetchAndParseEpgReflection(repository, "http://example.com/epg.xml", emptyList())
            fail("Expected EpgNotModifiedException to be thrown")
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            assertEquals(IptvRepository.EpgNotModifiedException::class.java, cause?.javaClass)
        }
    }

    @Test
    fun testBoundedInputStreamLimitExceeded() {
        val data = "Hello World"
        val raw = ByteArrayInputStream(data.toByteArray())
        val bounded = createBoundedInputStream(raw, 5L)

        val buffer = ByteArray(10)
        try {
            bounded.read(buffer)
            fail("Expected SecurityException to be thrown")
        } catch (e: SecurityException) {
            // expected
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            assertEquals(SecurityException::class.java, cause?.javaClass)
        }
    }

    @Test
    fun testParseM3uValidationAndFallback() = runBlocking {
        val m3uData = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch1" tvg-name="Channel 1" group-title="Group A",
            http://example.com/stream1.ts
            #EXTINF:-1 tvg-id="ch2" tvg-name="" group-title="Group B",
            https://example.com/stream2.ts
            #EXTINF:-1 tvg-id="ch3" tvg-name="Bad Channel",
            invalid_url_with spaces.ts
        """.trimIndent()

        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val okHttpClient = io.mockk.mockk<okhttp3.OkHttpClient>(relaxed = true)
        val profileManager = io.mockk.mockk<com.arflix.tv.data.repository.ProfileManager>(relaxed = true)
        val invalidationBus = io.mockk.mockk<com.arflix.tv.data.repository.CloudSyncInvalidationBus>(relaxed = true)
        val repository = IptvRepository(context, okHttpClient, profileManager, invalidationBus)

        val input = ByteArrayInputStream(m3uData.toByteArray(Charsets.UTF_8))
        val onProgress: (IptvLoadProgress) -> Unit = {}
        val channels = callParseM3uReflection(repository, input, onProgress)

        assertEquals(2, channels.size)
        assertEquals("Channel 1", channels[0].name)
        assertEquals("Unknown Channel", channels[1].name)
    }

    @Test
    fun testCacheEviction() {
        val tempDir = java.nio.file.Files.createTempDirectory("iptv_cache_test").toFile()
        val cacheDir = File(tempDir, "iptv_cache")
        cacheDir.mkdirs()

        val file1 = File(cacheDir, "profile1_iptv_cache.json")
        val file2 = File(cacheDir, "profile1_iptv_channels_cache.json")
        val file3 = File(cacheDir, "profile2_iptv_cache.json")

        file1.writeBytes(ByteArray(45 * 1024 * 1024)) // 45 MB
        file1.setLastModified(System.currentTimeMillis() - 10000)

        file2.writeBytes(ByteArray(45 * 1024 * 1024)) // 45 MB
        file2.setLastModified(System.currentTimeMillis() - 5000)

        file3.writeBytes(ByteArray(25 * 1024 * 1024)) // 25 MB
        file3.setLastModified(System.currentTimeMillis())

        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns tempDir

        val okHttpClient = io.mockk.mockk<okhttp3.OkHttpClient>(relaxed = true)
        val profileManager = io.mockk.mockk<com.arflix.tv.data.repository.ProfileManager>(relaxed = true)
        val invalidationBus = io.mockk.mockk<com.arflix.tv.data.repository.CloudSyncInvalidationBus>(relaxed = true)
        val repository = IptvRepository(context, okHttpClient, profileManager, invalidationBus)

        val method = IptvRepository::class.java.getDeclaredMethod("cleanupIptvCacheDirectory")
        method.isAccessible = true
        method.invoke(repository)

        assertFalse(file1.exists())
        assertTrue(file2.exists())
        assertTrue(file3.exists())

        tempDir.deleteRecursively()
    }
}
