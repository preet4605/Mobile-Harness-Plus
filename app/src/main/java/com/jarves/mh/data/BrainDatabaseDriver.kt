package com.jarves.mh.data

import java.io.Closeable
import java.io.File

interface SqlRow {
    fun getString(columnName: String): String?
    fun getLong(columnName: String): Long?
    fun getDouble(columnName: String): Double?
    fun getInt(columnName: String): Int?
}

interface BrainDatabaseDriver : Closeable {
    fun execute(sql: String, bindArgs: List<Any?> = emptyList())
    fun <T> query(sql: String, bindArgs: List<Any?> = emptyList(), mapper: (SqlRow) -> T): List<T>
    fun transaction(block: () -> Unit)
}

class AndroidSqliteDriver(private val db: android.database.sqlite.SQLiteDatabase) : BrainDatabaseDriver {
    override fun execute(sql: String, bindArgs: List<Any?>) {
        if (bindArgs.isEmpty()) {
            db.execSQL(sql)
        } else {
            db.execSQL(sql, bindArgs.map { it?.toString() }.toTypedArray())
        }
    }

    override fun <T> query(sql: String, bindArgs: List<Any?>, mapper: (SqlRow) -> T): List<T> {
        val args = if (bindArgs.isEmpty()) null else bindArgs.map { it?.toString() ?: "" }.toTypedArray()
        val cursor = db.rawQuery(sql, args)
        val result = mutableListOf<T>()
        cursor.use {
            val row = AndroidCursorRow(cursor)
            while (cursor.moveToNext()) {
                result.add(mapper(row))
            }
        }
        return result
    }

    override fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        if (db.isOpen) {
            db.close()
        }
    }
}

class AndroidCursorRow(private val cursor: android.database.Cursor) : SqlRow {
    override fun getString(columnName: String): String? {
        val idx = cursor.getColumnIndex(columnName)
        if (idx == -1 || cursor.isNull(idx)) return null
        return cursor.getString(idx)
    }

    override fun getLong(columnName: String): Long? {
        val idx = cursor.getColumnIndex(columnName)
        if (idx == -1 || cursor.isNull(idx)) return null
        return cursor.getLong(idx)
    }

    override fun getDouble(columnName: String): Double? {
        val idx = cursor.getColumnIndex(columnName)
        if (idx == -1 || cursor.isNull(idx)) return null
        return cursor.getDouble(idx)
    }

    override fun getInt(columnName: String): Int? {
        val idx = cursor.getColumnIndex(columnName)
        if (idx == -1 || cursor.isNull(idx)) return null
        return cursor.getInt(idx)
    }
}

class JdbcSqliteDriver(private val connection: java.sql.Connection) : BrainDatabaseDriver {
    override fun execute(sql: String, bindArgs: List<Any?>) {
        connection.prepareStatement(sql).use { stmt ->
            bindArgs.forEachIndexed { i, arg ->
                stmt.setObject(i + 1, arg)
            }
            stmt.execute()
        }
    }

    override fun <T> query(sql: String, bindArgs: List<Any?>, mapper: (SqlRow) -> T): List<T> {
        connection.prepareStatement(sql).use { stmt ->
            bindArgs.forEachIndexed { i, arg ->
                stmt.setObject(i + 1, arg)
            }
            stmt.executeQuery().use { rs ->
                val row = JdbcResultSetRow(rs)
                val list = mutableListOf<T>()
                while (rs.next()) {
                    list.add(mapper(row))
                }
                return list
            }
        }
    }

    override fun transaction(block: () -> Unit) {
        val prevAutoCommit = connection.autoCommit
        try {
            connection.autoCommit = false
            block()
            connection.commit()
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = prevAutoCommit
        }
    }

    override fun close() {
        if (!connection.isClosed) {
            connection.close()
        }
    }
}

class JdbcResultSetRow(private val rs: java.sql.ResultSet) : SqlRow {
    override fun getString(columnName: String): String? {
        return try {
            rs.getString(columnName)
        } catch (_: Exception) {
            null
        }
    }

    override fun getLong(columnName: String): Long? {
        return try {
            val v = rs.getLong(columnName)
            if (rs.wasNull()) null else v
        } catch (_: Exception) {
            null
        }
    }

    override fun getDouble(columnName: String): Double? {
        return try {
            val v = rs.getDouble(columnName)
            if (rs.wasNull()) null else v
        } catch (_: Exception) {
            null
        }
    }

    override fun getInt(columnName: String): Int? {
        return try {
            val v = rs.getInt(columnName)
            if (rs.wasNull()) null else v
        } catch (_: Exception) {
            null
        }
    }
}

object BrainDatabaseDriverFactory {
    fun createDriver(dbFile: File): BrainDatabaseDriver {
        dbFile.parentFile?.mkdirs()
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            AndroidSqliteDriver(db)
        } catch (_: Throwable) {
            val url = "jdbc:sqlite:${dbFile.absolutePath}"
            val conn = java.sql.DriverManager.getConnection(url)
            JdbcSqliteDriver(conn)
        }
    }

    fun createInMemoryDriver(): BrainDatabaseDriver {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        return JdbcSqliteDriver(conn)
    }
}
