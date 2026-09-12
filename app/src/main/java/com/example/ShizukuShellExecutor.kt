package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShizukuShellExecutor {
    private const val TAG = "ShizukuShellExecutor"
    const val REQUEST_CODE_SHIZUKU = 1001

    // Verifica si la aplicación Shizuku se está ejecutando en el dispositivo
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            Log.e(TAG, "Error al hacer ping al binder de Shizuku", e)
            false
        }
    }

    // Verifica si nuestra app ya tiene permiso concedido de Shizuku
    fun hasShizukuPermission(): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            Log.e(TAG, "Error al comprobar permisos de Shizuku", e)
            false
        }
    }

    // Solicita permisos de Shizuku al usuario de forma nativa
    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        } catch (e: Throwable) {
            Log.e(TAG, "Error al solicitar permisos de Shizuku", e)
        }
    }

    // Concede de forma automática todos los permisos WRITE_SECURE_SETTINGS, DUMP, etc.
    // ejecutando pm grant en el shell seguro de Shizuku (nivel de privilegio ADB).
    suspend fun autoGrantAppPermissions(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) {
            Log.w(TAG, "No se puede auto-conceder permisos: Shizuku no está enlazado o permitido.")
            return@withContext false
        }
        val pkg = context.packageName
        val commands = listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $pkg android.permission.DUMP",
            "pm grant $pkg android.permission.BATTERY_STATS",
            "pm grant $pkg android.permission.PACKAGE_USAGE_STATS"
        )
        val result = executeCommands(commands)
        Log.d(TAG, "Resultados de auto-concesión: ${result.output}\nErrores: ${result.error}")
        return@withContext result.exitCode == 0
    }

    data class ExecutionResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    // Ejecuta secuencialmente una lista de comandos shell bajo el contexto privilegiado de Shizuku
    suspend fun executeCommands(commands: List<String>): ExecutionResult = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) {
            return@withContext ExecutionResult(-1, "", "Permiso de Shizuku no concedido.")
        }
        var process: java.lang.Process? = null
        try {
            // Inicia un nuevo proceso shell privilegiado vía reflexión para evitar restricciones de compilador en v13+
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            process = newProcessMethod.invoke(null, arrayOf("sh"), null, null) as java.lang.Process
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            
            for (cmd in commands) {
                writer.write(cmd)
                writer.write("\n")
            }
            writer.write("exit\n")
            writer.flush()
            writer.close()

            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = outputReader.readText()
            val error = errorReader.readText()

            val exitCode = process.waitFor()
            ExecutionResult(exitCode, output, error)
        } catch (e: Throwable) {
            Log.e(TAG, "Error de ejecución con Shizuku", e)
            ExecutionResult(-1, "", e.localizedMessage ?: "Error de proceso desconocido.")
        } finally {
            process?.destroy()
        }
    }

    // Ejecuta un único comando shell y retorna el resultado
    suspend fun executeCommand(command: String): ExecutionResult {
        return executeCommands(listOf(command))
    }
}
