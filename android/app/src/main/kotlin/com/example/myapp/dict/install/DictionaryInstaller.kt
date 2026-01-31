package com.example.myapp.dict.install

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.db.DictionaryDbHelper
import java.io.File
import java.io.FileOutputStream

class DictionaryInstaller(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "DictionaryInstallerPrefs"
        private const val KEY_INSTALLED_HASH = "installed_db_sha256"
    }

    @Volatile
    var debugInfo: String = "未开始"

    /**
     * 确保 assets/dictionary.db 已部署到 databases/ 目录。
     * - 当本地不存在/为空/损坏/或 assets 指纹变化时，会强制覆盖安装。
     */
    fun ensureInstalled(force: Boolean = false): Boolean {
        val dbFile = context.getDatabasePath(DictionaryDbHelper.DATABASE_NAME)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val targetHash = DictDbBuildInfo.ASSET_DB_SHA256
        val installedHash = prefs.getString(KEY_INSTALLED_HASH, null)

        val fileLooksOk = dbFile.exists() && dbFile.length() > 0
        val hashOk = installedHash == targetHash
        val dbOpensOk = fileLooksOk && canOpenDb(dbFile)

        val needReinstall = force || !fileLooksOk || !hashOk || !dbOpensOk

        if (!needReinstall) {
            debugInfo = "词库已存在(hash=${installedHash?.take(8)})"
            return true
        }

        return try {
            debugInfo = "正在部署词库(hash=${targetHash.take(8)})..."
            copyFromAssets(dbFile)

            // 再做一次快速可打开性校验，避免复制中断导致的“空壳文件”
            if (!canOpenDb(dbFile)) {
                debugInfo = "词库校验失败：无法打开数据库"
                return false
            }

            prefs.edit().putString(KEY_INSTALLED_HASH, targetHash).apply()
            debugInfo = "词库部署完成(hash=${targetHash.take(8)})"
            true
        } catch (e: Exception) {
            e.printStackTrace()
            debugInfo = "词库部署失败: ${e.message}"
            false
        }
    }

    private fun copyFromAssets(destFile: File) {
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()

        context.assets.open(DictionaryDbHelper.DATABASE_NAME).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
                output.flush()
            }
        }
    }

    private fun canOpenDb(dbFile: File): Boolean {
        return try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            db.close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
