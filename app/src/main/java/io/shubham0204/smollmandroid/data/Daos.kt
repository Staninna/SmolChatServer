/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: Long): ModelEntity?

    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    suspend fun getAll(): List<ModelEntity>

    @Insert
    suspend fun insert(model: ModelEntity): Long

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PairedAppDao {
    @Query("SELECT * FROM paired_apps ORDER BY COALESCE(lastUsedAt, pairedAt) DESC")
    fun observeAll(): Flow<List<PairedAppEntity>>

    @Query("SELECT * FROM paired_apps WHERE tokenHash = :hash LIMIT 1")
    suspend fun findByTokenHash(hash: String): PairedAppEntity?

    @Upsert
    suspend fun upsert(app: PairedAppEntity)

    @Query(
        "UPDATE paired_apps SET lastUsedAt = :time, requestCount = requestCount + 1 " +
            "WHERE packageName = :packageName"
    )
    suspend fun recordUse(packageName: String, time: Long)

    @Query("DELETE FROM paired_apps WHERE packageName = :packageName")
    suspend fun revoke(packageName: String)

    @Query("SELECT * FROM paired_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun get(packageName: String): PairedAppEntity?

    @Query("UPDATE paired_apps SET certDigest = :digest WHERE packageName = :packageName")
    suspend fun setCertDigest(packageName: String, digest: String)
}

@Dao
interface RequestLogDao {
    @Query("SELECT * FROM request_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<RequestLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RequestLogEntity)

    @Query("DELETE FROM request_log WHERE id NOT IN (SELECT id FROM request_log ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 5000)

    /** Rows for statistics: no bodies, oldest first. */
    @Query(
        "SELECT timestamp, clientPackage, clientLabel, path, status, errorCode, promptTokens, completionTokens, " +
            "durationMs, tokensPerSecond, queueMs, loadMs, promptMs, generationMs, cachedTokens " +
            "FROM request_log WHERE timestamp >= :since ORDER BY timestamp ASC"
    )
    fun observeStatRows(since: Long): Flow<List<StatRow>>

    @Query("UPDATE request_log SET requestBody = NULL, responseBody = NULL")
    suspend fun clearBodies()

    @Query("DELETE FROM request_log")
    suspend fun clear()
}

@Dao
interface JobDao {
    @Upsert
    suspend fun upsert(job: JobEntity)

    @Query("SELECT * FROM jobs ORDER BY createdAt ASC")
    suspend fun getAll(): List<JobEntity>

    @Query("UPDATE jobs SET checkpointText = :text, checkpointTokens = :tokens, phase = :phase WHERE id = :id")
    suspend fun checkpoint(id: String, text: String, tokens: Int, phase: String)

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM jobs WHERE finishedAt IS NOT NULL AND finishedAt < :before")
    suspend fun deleteFinishedBefore(before: Long)
}
