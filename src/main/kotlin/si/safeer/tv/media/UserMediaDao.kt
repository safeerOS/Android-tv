package si.safeer.tv.media

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserMediaDao {
    @Query("SELECT * FROM user_media ORDER BY addedAt DESC")
    fun all(): List<UserMediaItem>

    @Query("SELECT * FROM user_media WHERE type = :type ORDER BY title COLLATE NOCASE")
    fun byType(type: MediaType): List<UserMediaItem>

    @Query("SELECT * FROM user_media WHERE (',' || genres || ',') LIKE ('%,' || :genre || ',%') ORDER BY title COLLATE NOCASE")
    fun byGenre(genre: String): List<UserMediaItem>

    @Query("""
        SELECT * FROM user_media
        WHERE (:type IS NULL OR type = :type)
          AND (:genre IS NULL OR (',' || genres || ',') LIKE ('%,' || :genre || ',%'))
        ORDER BY title COLLATE NOCASE
    """)
    fun filter(type: MediaType?, genre: String?): List<UserMediaItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: UserMediaItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<UserMediaItem>)

    @Delete
    fun delete(item: UserMediaItem)
}
