package com.example.data

import kotlinx.coroutines.flow.Flow

class ScriptModuleRepository(private val dao: ScriptModuleDao) {
    val allModules: Flow<List<ScriptModule>> = dao.getAllModules()

    suspend fun insert(module: ScriptModule) {
        dao.insertModule(module)
    }

    suspend fun update(module: ScriptModule) {
        dao.updateModule(module)
    }

    suspend fun delete(module: ScriptModule) {
        dao.deleteModule(module)
    }
}
