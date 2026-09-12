package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "script_modules")
data class ScriptModule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String,
    val script: String,
    val isEnabled: Boolean = false,
    val isSystem: Boolean = false
)
