package ru.dkd.app.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DkdApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: DkdApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(
                OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .build()
            )
            .build()
            .create(DkdApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getSendsBearerTokenAndAcceptHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val response = service.get(server.url("/api/profile").toString(), "Bearer test-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertTrue(response.isSuccessful)
        assertEquals("GET", request?.method)
        assertEquals("/api/profile", request?.path)
        assertEquals("Bearer test-token", request?.getHeader("Authorization"))
    }

    @Test
    fun postSendsJsonBodyWithoutAutomaticRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Запрещено"}"""))
        val body = """{"vote":"candidate"}""".toRequestBody("application/json".toMediaType())

        val response = service.post(
            server.url("/api/votes/duma/1/cast").toString(),
            "Bearer test-token",
            body
        )
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertEquals(403, response.code())
        assertEquals("POST", request?.method)
        assertEquals("""{"vote":"candidate"}""", request?.body?.readUtf8())
        assertEquals(1, server.requestCount)
    }
}
