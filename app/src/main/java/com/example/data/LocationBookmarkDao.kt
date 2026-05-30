package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationBookmarkDao {
    @Query("SELECT * FROM location_bookmarks ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<LocationBookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: LocationBookmark): Long

    @Update
    suspend fun updateBookmark(bookmark: LocationBookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: LocationBookmark)

    @Query("DELETE FROM location_bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Int)

    @Query("SELECT * FROM location_bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: Int): LocationBookmark?
}
