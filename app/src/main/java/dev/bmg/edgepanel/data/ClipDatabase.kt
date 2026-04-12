// data/ClipDatabase.kt
package dev.bmg.edgepanel.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import android.util.Base64

@Database(entities = [ClipEntity::class], version = 2, exportSchema = false)
abstract class ClipDatabase : RoomDatabase() {

    abstract fun clipDao(): ClipDao

    companion object {
        private const val DB_NAME = "clip_history.db"
        private const val PREFS_NAME = "clip_db_prefs"
        private const val KEY_PASSPHRASE = "db_passphrase"

        @Volatile
        private var INSTANCE: ClipDatabase? = null

        fun getInstance(context: Context): ClipDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context): ClipDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = getOrCreatePassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                ClipDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration(true)
                .build()
        }

        private fun getOrCreatePassphrase(context: Context): ByteArray {
            // MasterKey uses Android Keystore internally — but we never call
            // getEncoded() on it, so null is never an issue
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // Return existing passphrase or generate and store a new one
            val existing = prefs.getString(KEY_PASSPHRASE, null)
            if (existing != null) {
                return Base64.decode(existing, Base64.DEFAULT)
            }

            val newPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit()
                .putString(KEY_PASSPHRASE, Base64.encodeToString(newPassphrase, Base64.DEFAULT))
                .apply()
            return newPassphrase
        }
    }
}