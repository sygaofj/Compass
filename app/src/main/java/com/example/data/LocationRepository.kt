package com.example.data

import kotlinx.coroutines.flow.Flow

class LocationRepository(private val dao: LocationBookmarkDao) {
    val allBookmarks: Flow<List<LocationBookmark>> = dao.getAllBookmarks()

    suspend fun insertBookmark(bookmark: LocationBookmark): Long {
        return dao.insertBookmark(bookmark)
    }

    suspend fun updateBookmark(bookmark: LocationBookmark) {
        dao.updateBookmark(bookmark)
    }

    suspend fun deleteBookmark(bookmark: LocationBookmark) {
        dao.deleteBookmark(bookmark)
    }

    suspend fun deleteBookmarkById(id: Int) {
        dao.deleteBookmarkById(id)
    }

    suspend fun getBookmarkById(id: Int): LocationBookmark? {
        return dao.getBookmarkById(id)
    }
}
