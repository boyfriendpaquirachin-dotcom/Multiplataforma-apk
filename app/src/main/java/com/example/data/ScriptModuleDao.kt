package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptModuleDao {
    @Query("SELECT * FROM script_modules ORDER BY isSystem DESC, id ASC")
    fun getAllModules(): Flow<List<ScriptModule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModule(module: ScriptModule)

    @Update
    suspend fun updateModule(module: ScriptModule)

    @Delete
    suspend fun deleteModule(module: ScriptModule)

    @Query("SELECT COUNT(*) FROM script_modules")
    suspend fun getCount(): Int
}
