package com.emilio.ytmusic.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

class StorageManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ytmusic_storage_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FOLDER_PATH = "download_folder_path"
        private const val KEY_FOLDER_URI = "download_folder_uri"
        private const val KEY_FIRST_LAUNCH = "first_launch_done"

        @Volatile
        private var instance: StorageManager? = null

        fun getInstance(context: Context): StorageManager {
            return instance ?: synchronized(this) {
                instance ?: StorageManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Retorna la ruta absoluta actual para guardar la música.
     * Prioriza la carpeta elegida por el usuario, luego /storage/emulated/0/Music,
     * y por último el almacenamiento privado de la app.
     */
    fun getDownloadFolderPath(): String {
        val savedPath = prefs.getString(KEY_FOLDER_PATH, null)
        if (!savedPath.isNullOrBlank()) {
            val file = File(savedPath)
            if (file.exists() || file.mkdirs()) {
                return file.absolutePath
            }
        }

        // Intento 1: Carpeta pública estándar de Música (/storage/emulated/0/Music)
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (publicMusic != null && (publicMusic.exists() || publicMusic.mkdirs())) {
            return publicMusic.absolutePath
        }

        // Intento 2: Carpeta de música privada de la aplicación
        val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (appMusic != null && (appMusic.exists() || appMusic.mkdirs())) {
            return appMusic.absolutePath
        }

        // Intento 3: Directorio raíz de almacenamiento
        return Environment.getExternalStorageDirectory().absolutePath
    }

    /**
     * Guarda la nueva carpeta seleccionada por el usuario.
     */
    fun setDownloadFolder(path: String, uriString: String? = null) {
        val file = File(path)
        if (!file.exists()) {
            file.mkdirs()
        }
        prefs.edit()
            .putString(KEY_FOLDER_PATH, path)
            .apply {
                if (uriString != null) {
                    putString(KEY_FOLDER_URI, uriString)
                }
            }
            .apply()
    }

    /**
     * Retorna el URI SAF persistido si existe.
     */
    fun getDownloadFolderUri(): String? {
        return prefs.getString(KEY_FOLDER_URI, null)
    }

    /**
     * Verifica si el usuario ya realizó la configuración inicial de la carpeta.
     */
    fun isFirstLaunch(): Boolean {
        return !prefs.getBoolean(KEY_FIRST_LAUNCH, false)
    }

    fun setFirstLaunchDone() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
    }

    /**
     * Verifica si se cuenta con el permiso completo de almacenamiento (incluyendo Android 11 a 16).
     */
    fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // En Android <= 10 se maneja por permisos estándar en tiempo de ejecución
        }
    }

    /**
     * Abre los ajustes del sistema para conceder "Acceso a todos los archivos" (MANAGE_EXTERNAL_STORAGE).
     */
    fun openManageAllFilesSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    /**
     * Resuelve un URI de árbol SAF (ACTION_OPEN_DOCUMENT_TREE) a una ruta POSIX real.
     */
    fun resolveDocTreeUriToPath(uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
            val parts = docId.split(":")
            val type = parts[0]
            val relativePath = if (parts.size > 1) parts[1] else ""

            if ("primary".equals(type, ignoreCase = true)) {
                val root = Environment.getExternalStorageDirectory()
                return if (relativePath.isNotEmpty()) {
                    File(root, relativePath).absolutePath
                } else {
                    root.absolutePath
                }
            } else {
                // Posible tarjeta SD externa o almacenamiento secundario
                val extDirs = context.getExternalFilesDirs(null)
                for (dir in extDirs) {
                    if (dir != null) {
                        val abs = dir.absolutePath
                        val idx = abs.indexOf(type)
                        if (idx != -1) {
                            val base = abs.substring(0, idx + type.length)
                            return if (relativePath.isNotEmpty()) {
                                File(base, relativePath).absolutePath
                            } else {
                                base
                            }
                        }
                    }
                }
                // Fallback para /storage/XXXX-XXXX
                val candidate = File("/storage/$type", relativePath)
                if (candidate.exists() || candidate.parentFile?.exists() == true) {
                    return candidate.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
