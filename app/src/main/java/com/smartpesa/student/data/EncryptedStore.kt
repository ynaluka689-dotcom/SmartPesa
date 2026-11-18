package com.smartpesa.student.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A random database password is wrapped by a non-exportable Android Keystore AES key. */
object EncryptedStore {
    private const val ALIAS = "SmartPesa.database.v1"
    private val lock = Any()
    fun factory(context: Context) = SupportSQLiteOpenHelper.Factory { config ->
        // Room creates its helper during build on the UI thread. Delay crypto until an IO query opens it.
        object : SupportSQLiteOpenHelper {
            private var delegate: SupportSQLiteOpenHelper? = null
            private var wal = false
            override val databaseName: String? get() = config.name
            private fun opened(): SupportSQLiteOpenHelper = synchronized(lock) {
                delegate ?: run {
                    System.loadLibrary("sqlcipher")
                    val password = secret(context)
                    migratePlaintext(context, password)
                    SupportOpenHelperFactory(password).create(config).also { it.setWriteAheadLoggingEnabled(wal); delegate = it }
                }
            }
            override val writableDatabase: androidx.sqlite.db.SupportSQLiteDatabase get() = opened().writableDatabase
            override val readableDatabase: androidx.sqlite.db.SupportSQLiteDatabase get() = opened().readableDatabase
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(lock) { wal = enabled; delegate?.setWriteAheadLoggingEnabled(enabled); Unit }
            override fun close() = synchronized(lock) { delegate?.close(); delegate = null; Unit }
        }
    }
    internal fun secret(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("database_key", Context.MODE_PRIVATE)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = prefs.getString("wrapped", null)
        // Losing a key must never reset or replace an existing ledger.
        if (wrapped != null) {
            val key = store.getKey(ALIAS, null) as? SecretKey ?: error("Stored ledger key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        check(!context.getDatabasePath("smartpesa_secure.db").exists()) { "Ledger key unavailable" }
        val key = if (store.containsAlias(ALIAS)) store.getKey(ALIAS, null) as SecretKey else KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        // Printable random passphrase avoids ambiguous raw-key encodings across SQLCipher APIs.
        val secret = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        check(prefs.edit().putString("wrapped", Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
        return secret
    }
    /** Convert into a verified temporary database, preserving the original until rename succeeds. */
    internal fun migratePlaintext(context: Context, password: ByteArray) {
        val original = context.getDatabasePath("smartpesa.db")
        if (!original.exists()) return
        val target = context.getDatabasePath("smartpesa_secure.db")
        val temporary = File(target.path + ".migrating")
        val plain = SQLiteDatabase.openDatabase(original.path, "", null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null)
        try {
            plain.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
            fun counts(db: SQLiteDatabase): List<Long> = listOf("transactions", "budgets").map { table ->
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst()); it.getLong(0) }
            }
            val tables = plain.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            // Compare complete ordered row values, not merely counts. Never include values in logs.
            fun fingerprint(db: SQLiteDatabase): ByteArray {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                fun feed(bytes: ByteArray) { digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
                tables.forEach { table ->
                    val columns = plain.rawQuery("PRAGMA table_info(\"$table\")", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
                    val names = columns.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
                    feed(table.toByteArray())
                    db.rawQuery("SELECT $names FROM \"$table\" ORDER BY $names", null).use { cursor ->
                        while (cursor.moveToNext()) columns.indices.forEach { column ->
                            digest.update(cursor.getType(column).toByte())
                            feed(if (cursor.isNull(column)) byteArrayOf() else if (cursor.getType(column) == android.database.Cursor.FIELD_TYPE_BLOB) cursor.getBlob(column) else cursor.getString(column).toByteArray())
                        }
                    }
                }
                return digest.digest()
            }
            val expected = counts(plain)
            val expectedFingerprint = fingerprint(plain)
            val version = plain.version
            if (!target.exists()) {
                if (temporary.exists()) check(temporary.delete())
                val escapedPath = temporary.path.replace("'", "''")
                val escapedPassword = password.toString(Charsets.UTF_8).replace("'", "''")
                plain.execSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedPassword'")
                try { plain.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { check(it.moveToFirst()) }; plain.execSQL("PRAGMA encrypted.user_version=$version") }
                finally { plain.execSQL("DETACH DATABASE encrypted") }
                SQLiteDatabase.openDatabase(temporary.path, password, null, SQLiteDatabase.OPEN_READWRITE, null).use { encrypted ->
                    check(counts(encrypted) == expected && encrypted.version == version && fingerprint(encrypted).contentEquals(expectedFingerprint))
                    encrypted.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
                }
                java.io.RandomAccessFile(temporary, "rw").use { it.fd.sync() }
                check(temporary.renameTo(target)) { "Unable to install encrypted ledger" }
            } else {
                // Recovery after rename but before plaintext cleanup: verify before removing anything.
                SQLiteDatabase.openDatabase(target.path, password, null, SQLiteDatabase.OPEN_READWRITE, null).use { check(counts(it) == expected && fingerprint(it).contentEquals(expectedFingerprint)) }
            }
        } finally { plain.close() }
        if (temporary.exists()) check(temporary.delete()) { "Unable to remove migration temporary file" }
        check(context.deleteDatabase("smartpesa.db")) { "Unable to remove old ledger copy" }
    }
}
