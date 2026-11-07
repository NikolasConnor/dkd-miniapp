package ru.dkd.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "public_cache")
internal data class PublicCacheEntity(
    @PrimaryKey val cacheKey: String,
    val payload: String,
    val updatedAtEpochMs: Long
)

@Dao
internal interface PublicCacheDao {
    @Query("SELECT * FROM public_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): PublicCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: PublicCacheEntity)

    @Query("DELETE FROM public_cache")
    suspend fun clear()
}

@Database(entities = [PublicCacheEntity::class], version = 1, exportSchema = false)
internal abstract class DkdDatabase : RoomDatabase() {
    abstract fun publicCacheDao(): PublicCacheDao

    companion object {
        @Volatile private var instance: DkdDatabase? = null

        fun get(context: Context): DkdDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DkdDatabase::class.java,
                    "dkd-public-cache.db"
                ).build().also { instance = it }
            }
    }
}
