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
    fun profileEndpointDeserializesEnvelopeAndDisplayFields() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"profile":{"full_name":"Citizen Name","avatar_emoji":"star","role":"President","citizenship":"Citizen","party_name":"Party A","news_notifications_enabled":false}}"""
        ))
        val response = service.getProfile("Bearer profile-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(response.isSuccessful)
        assertEquals("Bearer profile-token", request?.getHeader("Authorization"))
        assertEquals("Citizen Name", response.body()?.profile?.fullName)
        assertEquals("Party A", response.body()?.profile?.partyName)
        assertEquals(false, response.body()?.profile?.newsNotificationsEnabled)
    }

    @Test
    fun notificationSummaryDeserializesUnreadCount() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"notifications":[],"unread_count":7}"""
        ))
        val response = service.getNotificationSummary("Bearer profile-token")
        assertTrue(response.isSuccessful)
        assertEquals(7, response.body()?.unreadCount)
    }


    @Test
    fun electionsEndpointDeserializesBallotsAndResults() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"presidential":[{"id":7,"kind":"presidential","title":"President election","options":["Candidate A"],"status":"active","is_open":true,"has_voted":false,"candidate_details":[{"name":"Candidate A","full_name":"Citizen A","avatar_emoji":"⭐"}]}],"duma":[{"id":8,"kind":"duma","title":"Duma election","options":["Party A"],"status":"closed","has_voted":true,"vote_counts":{"Party A":3},"votes_total":3,"party_lists":[{"party_name":"Party A","party_emoji":"🏛️","color":"#8B7CFF","candidates":["Citizen A"]}]}]}"""
        ))
        val response = service.getElections("Bearer election-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(response.isSuccessful)
        assertEquals("/api/votes", request?.path)
        assertEquals("Bearer election-token", request?.getHeader("Authorization"))
        assertEquals("Citizen A", response.body()?.presidential?.firstOrNull()?.candidateDetails?.firstOrNull()?.fullName)
        assertEquals(3, response.body()?.duma?.firstOrNull()?.votesTotal)
        assertEquals("Party A", response.body()?.duma?.firstOrNull()?.partyLists?.firstOrNull()?.partyName)
    }

    @Test
    fun partiesEndpointDeserializesMembersAndPermissions() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"parties":[{"id":2,"name":"Party A","emoji":"🏛️","description":"Description","color":"#8B7CFF","status":"active","members_count":1,"members":[{"member_id":11,"name":"Citizen A","username":"citizen_a","is_leader":true}],"is_member":true,"can_manage":true}],"me":{"is_citizen":true,"party_id":2,"is_admin":false}}"""
        ))
        val response = service.getParties("Bearer party-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(response.isSuccessful)
        assertEquals("/api/parties", request?.path)
        assertEquals(true, response.body()?.me?.isCitizen)
        assertEquals(2, response.body()?.me?.partyId)
        assertEquals("Citizen A", response.body()?.parties?.firstOrNull()?.members?.firstOrNull()?.name)
        assertEquals(true, response.body()?.parties?.firstOrNull()?.canManage)
    }

    @Test
    fun newsEndpointDeserializesPublicArticleMetadata() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"news":[{"id":9,"title":"DKD update","text":"Official text","created_at":"2026-11-07T12:00:00","category":"government","importance":"important","media_type":"photo"}]}"""
        ))
        val response = service.getNews("Bearer news-token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(response.isSuccessful)
        assertEquals("/api/news", request?.path)
        assertEquals("DKD update", response.body()?.news?.firstOrNull()?.title)
        assertEquals("government", response.body()?.news?.firstOrNull()?.category)
        assertEquals("photo", response.body()?.news?.firstOrNull()?.mediaType)
    }


    @Test
    fun presidentialVoteUsesTypedRequestAndReturnsServerConfirmation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"ok":true,"message":"Your vote was saved."}"""
        ))
        val response = service.castVote(
            "Bearer voter-token", "presidential", 12, CastVoteRequest("Candidate A")
        )
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(response.isSuccessful)
        assertEquals("/api/votes/presidential/12/cast", request?.path)
        assertEquals("Bearer voter-token", request?.getHeader("Authorization"))
        assertEquals("""{"option":"Candidate A"}""", request?.body?.readUtf8())
        assertEquals(true, response.body()?.ok)
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
