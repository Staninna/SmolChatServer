/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [ModelEntity::class, PairedAppEntity::class, RequestLogEntity::class, JobEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class ServerDatabase : RoomDatabase() {
    companion object {
        /** Every schema step, oldest first. */
        val MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * Forgets models whose file is gone. Backups leave the model files out, so after a restore
         * their rows point at nothing, and importing the same file again would give two rows with
         * one path. The active-model setting already falls back when its row is missing.
         */
        val FORGET_MISSING_MODELS =
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val missing = mutableListOf<Long>()
                    db.query("SELECT id, path FROM models").use { c ->
                        while (c.moveToNext()) {
                            if (!File(c.getString(1)).exists()) missing += c.getLong(0)
                        }
                    }
                    missing.forEach { db.execSQL("DELETE FROM models WHERE id = ?", arrayOf(it)) }
                }
            }

        /** Version 4 stores the signing certificate of each paired app, checked when it wakes the server. */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE paired_apps ADD COLUMN certDigest TEXT")
                }
            }

        // exactly what Room generates for [JobEntity]; a difference fails the schema check at start
        const val JOBS_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `jobs` (`id` TEXT NOT NULL, `requestId` TEXT, `clientPackage` TEXT, " +
                "`clientLabel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `requestJson` TEXT NOT NULL, " +
                "`phase` TEXT NOT NULL, `finishedAt` INTEGER, `model` TEXT, `promptTokens` INTEGER NOT NULL, " +
                "`cachedTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `content` TEXT, " +
                "`reasoning` TEXT, `finishReason` TEXT, `promptMs` REAL, `generationMs` REAL, " +
                "`tokensPerSecond` REAL, `errorCode` TEXT, `errorMessage` TEXT, `checkpointText` TEXT, " +
                "`checkpointTokens` INTEGER NOT NULL, `resumes` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        /** Version 3 adds the table of durable background jobs. */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(JOBS_TABLE_SQL)
                }
            }

        /** Version 2 adds per-phase timings to the request log. */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE request_log ADD COLUMN mode TEXT")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN model TEXT")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN queueMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN loadMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN promptMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN generationMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN cachedTokens INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN finishReason TEXT")
                }
            }
    }

    abstract fun models(): ModelDao

    abstract fun pairedApps(): PairedAppDao

    abstract fun requestLog(): RequestLogDao

    abstract fun jobs(): JobDao
}
