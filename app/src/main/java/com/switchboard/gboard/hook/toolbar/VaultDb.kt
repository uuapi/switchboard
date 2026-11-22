package com.switchboard.gboard.hook.toolbar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * 剪贴板保险库（移植自 Gboard+ ClipboardDatabase 的文本部分）。
 * 存于 Gboard 进程私有数据库，供工具栏检索粘贴。
 */
class VaultDb(context: Context) {
    private val db: SQLiteDatabase =
        context.openOrCreateDatabase("switchboard_vault.db", Context.MODE_PRIVATE, null).also {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS clips(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "text TEXT NOT NULL," +
                    "ts INTEGER NOT NULL," +
                    "pinned INTEGER NOT NULL DEFAULT 0)"
            )
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_clips_ts ON clips(ts DESC)"
            )
        }

    data class Clip(val id: Long, val text: String, val ts: Long, val pinned: Boolean)

    fun insert(text: String, now: Long = System.currentTimeMillis()): Long {
        if (text.isBlank()) return -1
        // 相同内容置顶更新（去重）
        val existing = db.rawQuery(
            "SELECT id FROM clips WHERE text = ? LIMIT 1", arrayOf(text)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        return if (existing != -1L) {
            db.execSQL("UPDATE clips SET ts = ? WHERE id = ?", arrayOf(now, existing))
            existing
        } else {
            val cv = ContentValues().apply {
                put("text", text)
                put("ts", now)
                put("pinned", 0)
            }
            db.insert("clips", null, cv)
        }
    }

    /** search 为空时返回最近条目；置顶优先，其余按时间倒序 */
    fun list(search: String?, limit: Int = 50): List<Clip> {
        val q = search?.takeIf { it.isNotBlank() }
        val sql = if (q == null) {
            "SELECT id, text, ts, pinned FROM clips " +
                "ORDER BY pinned DESC, ts DESC LIMIT $limit"
        } else {
            "SELECT id, text, ts, pinned FROM clips WHERE text LIKE ? " +
                "ORDER BY pinned DESC, ts DESC LIMIT $limit"
        }
        val args = if (q == null) null else arrayOf("%$q%")
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Clip>()
            while (c.moveToNext()) {
                out.add(
                    Clip(
                        c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) != 0
                    )
                )
            }
            out
        }
    }

    fun togglePin(id: Long): Boolean {
        val cur = db.rawQuery("SELECT pinned FROM clips WHERE id=?", arrayOf("$id")).use {
            if (it.moveToFirst()) it.getInt(0) != 0 else return false
        }
        val next = if (cur) 0 else 1
        db.execSQL("UPDATE clips SET pinned=? WHERE id=?", arrayOf(next, id))
        return next == 1
    }

    fun delete(id: Long) {
        db.execSQL("DELETE FROM clips WHERE id=?", arrayOf(id))
    }

    fun clear() {
        db.execSQL("DELETE FROM clips WHERE pinned=0")
    }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM clips", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- 图片库 ----------
    data class ImageClip(val id: Long, val path: String, val hash: String, val ts: Long, val bytes: Int)

    private fun ensureImageTable() {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS images(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "path TEXT NOT NULL," +
                "hash TEXT UNIQUE NOT NULL," +
                "ts INTEGER NOT NULL," +
                "bytes INTEGER NOT NULL)"
        )
    }

    /** 图片入库：hash 去重，落盘到 filesDir/switchboard_vault_images，返回 id（重复返回已有 id） */
    fun insertImage(context: Context, data: ByteArray, now: Long = System.currentTimeMillis()): Long {
        if (data.isEmpty()) return -1
        ensureImageTable()
        val hash = try {
            val md = java.security.MessageDigest.getInstance("MD5")
            md.digest(data).joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            return -1
        }
        val existing = db.rawQuery("SELECT id FROM images WHERE hash=?", arrayOf(hash)).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
        if (existing != -1L) return existing

        val dir = java.io.File(context.filesDir, "switchboard_vault_images").apply { mkdirs() }
        // 单图上限 4MB，目录预算 200MB（超出淘汰最旧）
        if (data.size > 4 * 1024 * 1024) return -1
        val file = java.io.File(dir, "$hash.png")
        file.writeBytes(data)
        val id = db.insert("images", null, ContentValues().apply {
            put("path", file.absolutePath)
            put("hash", hash)
            put("ts", now)
            put("bytes", data.size)
        })
        trimImages()
        return id
    }

    private fun trimImages() {
        val budget = 200L * 1024 * 1024
        db.rawQuery(
            "SELECT id, path, bytes FROM images ORDER BY ts DESC", null
        ).use { c ->
            var total = 0L
            val drop = mutableListOf<Pair<Long, String>>()
            while (c.moveToNext()) {
                val bytes = c.getLong(2)
                total += bytes
                if (total > budget) drop.add(c.getLong(0) to c.getString(1))
            }
            for ((id, path) in drop) deleteImage(id)
        }
    }

    fun listImages(limit: Int = 60): List<ImageClip> {
        ensureImageTable()
        return db.rawQuery(
            "SELECT id, path, hash, ts, bytes FROM images ORDER BY ts DESC LIMIT $limit", null
        ).use { c ->
            val out = ArrayList<ImageClip>()
            while (c.moveToNext()) {
                out.add(ImageClip(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
            }
            out
        }
    }

    fun deleteImage(id: Long) {
        ensureImageTable()
        db.rawQuery("SELECT path FROM images WHERE id=?", arrayOf("$id")).use { c ->
            if (c.moveToFirst()) {
                runCatching { java.io.File(c.getString(0)).delete() }
            }
        }
        db.execSQL("DELETE FROM images WHERE id=?", arrayOf(id))
    }
}
