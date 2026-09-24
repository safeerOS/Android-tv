package si.safeer.tv.media

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class MediaTypeConverters {
    @TypeConverter fun fromType(value: MediaType): String = value.name
    @TypeConverter fun toType(value: String): MediaType =
        runCatching { MediaType.valueOf(value) }.getOrDefault(MediaType.OTHER)
}

@Database(entities = [UserMediaItem::class], version = 1, exportSchema = false)
@TypeConverters(MediaTypeConverters::class)
abstract class UserMediaDatabase : RoomDatabase() {
    abstract fun userMediaDao(): UserMediaDao

    companion object {
        @Volatile private var instance: UserMediaDatabase? = null

        fun get(context: Context): UserMediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                UserMediaDatabase::class.java,
                "safeer_user_media.db"
            ).build().also { instance = it }
        }
    }
}
