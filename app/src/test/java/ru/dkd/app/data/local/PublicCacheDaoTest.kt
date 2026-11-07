package ru.dkd.app.data.local

import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PublicCacheDaoTest {
    private lateinit var database: DkdDatabase
    private lateinit var dao: PublicCacheDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DkdDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.publicCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesAndReadsPublicSnapshot() {
        val snapshot = PublicCacheEntity(
            cacheKey = "public_parliament_roster",
            payload = """{"formed":true,"seats_total":5}""",
            updatedAtEpochMs = 1234L
        )
        kotlinx.coroutines.runBlocking { dao.put(snapshot) }

        val restored = kotlinx.coroutines.runBlocking { dao.get(snapshot.cacheKey) }
        assertNotNull(restored)
        assertEquals(snapshot, restored)
    }

    @Test
    fun replacesSnapshotWithLatestValue() {
        kotlinx.coroutines.runBlocking {
            dao.put(PublicCacheEntity("parties", "[]", 1L))
            dao.put(PublicCacheEntity("parties", """[{"id":7}]""", 2L))
        }

        val restored = kotlinx.coroutines.runBlocking { dao.get("parties") }
        assertEquals("""[{"id":7}]""", restored?.payload)
        assertEquals(2L, restored?.updatedAtEpochMs)
    }
}
