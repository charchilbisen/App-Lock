package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locked_apps")
data class LockedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val dateAdded: Long = System.currentTimeMillis()
)
