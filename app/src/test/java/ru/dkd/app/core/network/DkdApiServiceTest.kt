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
import retrofit2.converter.gson.GsonConverterFactory
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
            .addConverterFactory(GsonConverterFactory.create())
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
    fun passportEndpointDeserializesTypedFields() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"has_passport":true,"passport":{"number":"DKD-001","full_name":"Ivanov Ivan","issued_at":"2026-11-01","issued_by":"DKD Service","division_code":"001","status":"active","status_label":"Valid","country":"DKD","citizenship":"Citizen","citizen_id":"hidden"}}"""
        ))
        val response = service.getPassport("Bearer test-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(response.isSuccessful)
        assertEquals("Bearer test-token", request?.getHeader("Authorization"))
        assertEquals(true, response.body()?.hasPassport)
        assertEquals("DKD-001", response.body()?.passport?.number)
        assertEquals("Ivanov Ivan", response.body()?.passport?.fullName)
    }

    @Test
    fun documentsEndpointDeserializesOfficialCollections() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"laws":[{"id":4,"title":"Law","description":"Text","status":"signed","created_at":"2026-11-01"}],"decrees":[],"mandates":[{"id":5,"title":"Deputy mandate","text":"Valid until: 2027-01-01"}],"president_certificate":[]}"""
        ))
        val response = service.getDocuments("Bearer test-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(response.isSuccessful)
        assertEquals("GET", request?.method)
        assertEquals("/api/documents", request?.path)
        assertEquals(4, response.body()?.laws?.firstOrNull()?.id)
        assertEquals("signed", response.body()?.laws?.firstOrNull()?.status)
        assertEquals("Deputy mandate", response.body()?.mandates?.firstOrNull()?.title)
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
