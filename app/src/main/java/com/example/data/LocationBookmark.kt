package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_bookmarks")
data class LocationBookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val directionAngle: Float = 0f, // Direction bearing in degrees when bookmarked
    val address: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
