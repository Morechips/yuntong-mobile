package cn.yuntong.companion.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "records")
data class Record(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val content: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface Records {
    @Query("SELECT * FROM records ORDER BY createdAt DESC") fun observe(): Flow<List<Record>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: Record)

    @Query("DELETE FROM records WHERE id=:id") suspend fun delete(id: String)

    @Query("DELETE FROM records") suspend fun clear()
}

@Database(entities = [Record::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): Records
}

val Context.settingsStore by preferencesDataStore("native_settings")

data class Preferences(
    val large: Float = 1f,
    val contrast: Boolean = false,
    val rate: Float = 1f,
    val feedback: Boolean = true,
    val cooperative: Boolean = false,
    val phoneOutput: Boolean = false,
    val mobileData: Boolean = false,
    val checks: String = "",
    val checkDay: String = "",
    val mapConsent: Boolean = false,
    val autoFallSms: Boolean = false,
    val emergencyContactId: String = "",
)

class Settings(private val context: Context) {
    val flow =
        context.settingsStore.data.map {
            Preferences(
                it[floatPreferencesKey("large")] ?: 1f,
                it[booleanPreferencesKey("contrast")] ?: false,
                it[floatPreferencesKey("rate")] ?: 1f,
                it[booleanPreferencesKey("feedback")] ?: true,
                it[booleanPreferencesKey("cooperative")] ?: false,
                it[booleanPreferencesKey("phoneOutput")] ?: false,
                it[booleanPreferencesKey("mobileData")] ?: false,
                it[stringPreferencesKey("checks")] ?: "",
                it[stringPreferencesKey("checkDay")] ?: "",
                it[booleanPreferencesKey("mapConsent")] ?: false,
                it[booleanPreferencesKey("autoFallSms")] ?: false,
                it[stringPreferencesKey("emergencyContactId")] ?: "",
            )
        }

    suspend fun bool(key: String, value: Boolean) {
        context.settingsStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    suspend fun number(key: String, value: Float) {
        context.settingsStore.edit { it[floatPreferencesKey(key)] = value }
    }

    suspend fun checks(values: Set<String>, day: String) {
        context.settingsStore.edit {
            it[stringPreferencesKey("checks")] = values.sorted().joinToString(",")
            it[stringPreferencesKey("checkDay")] = day
        }
    }

    suspend fun text(key: String, value: String) {
        context.settingsStore.edit { it[stringPreferencesKey(key)] = value }
    }
}
