package com.vythera.relay.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val platform: String,
    val fingerprint: String,
    val pairedAtMillis: Long,
    val autoAcceptTransfers: Boolean,
    val lastConnectedMillis: Long?,
)

/** One line of history per transfer, updated as it progresses to a final state. */
@Entity(tableName = "transfers", indices = [Index("peerId"), Index("createdAtMillis")])
data class TransferRecordEntity(
    @PrimaryKey val id: String,
    /** "outgoing" or "incoming". */
    val direction: String,
    val peerId: String,
    val peerName: String,
    /** Content category, see `ContentCategory`. */
    val category: String,
    val title: String,
    val itemCount: Int,
    val totalBytes: Long,
    /** "completed", "failed", "cancelled", "declined". */
    val outcome: String,
    val failure: String?,
    val createdAtMillis: Long,
    val finishedAtMillis: Long,
)

/** Something received that the user can come back to. */
@Entity(tableName = "inbox", indices = [Index("receivedAtMillis"), Index("category")])
data class InboxItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromDeviceId: String,
    val fromName: String,
    val category: String,
    val title: String,
    /** content:// URI for files, null for text and links. */
    val uri: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    /** The text or URL for text and link items. */
    val text: String?,
    val transferId: String?,
    val receivedAtMillis: Long,
)

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices")
    fun observeAll(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices")
    suspend fun getAll(): List<TrustedDeviceEntity>

    @Upsert
    suspend fun upsert(device: TrustedDeviceEntity)

    @Query("DELETE FROM trusted_devices WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TransferRecordDao {
    @Query("SELECT * FROM transfers ORDER BY finishedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransferRecordEntity>>

    @Query("SELECT * FROM transfers WHERE peerId = :peerId ORDER BY finishedAtMillis DESC LIMIT :limit")
    fun observeForPeer(peerId: String, limit: Int): Flow<List<TransferRecordEntity>>

    @Upsert
    suspend fun upsert(record: TransferRecordEntity)

    @Query("DELETE FROM transfers")
    suspend fun clear()
}

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox ORDER BY receivedAtMillis DESC")
    fun observeAll(): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox WHERE id = :id")
    suspend fun get(id: Long): InboxItemEntity?

    @Upsert
    suspend fun upsert(item: InboxItemEntity): Long

    @Query("DELETE FROM inbox WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [TrustedDeviceEntity::class, TransferRecordEntity::class, InboxItemEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun trustedDevices(): TrustedDeviceDao
    abstract fun transfers(): TransferRecordDao
    abstract fun inbox(): InboxDao

    companion object {
        fun create(context: Context): RelayDatabase =
            Room.databaseBuilder(context, RelayDatabase::class.java, "relay.db").build()
    }
}
