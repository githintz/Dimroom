package com.dimroom.data.repository

import com.dimroom.data.db.PresetDao
import com.dimroom.data.db.PresetEntity
import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.BuiltInPresets
import com.dimroom.domain.model.EditSerialization
import com.dimroom.domain.model.EditStack
import com.dimroom.domain.model.Preset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetRepository @Inject constructor(
    private val presetDao: PresetDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    fun observePresets(): Flow<List<Preset>> = presetDao.observeAll().map { rows ->
        rows.map { it.toPreset() }
    }

    /** Idempotently writes the starter presets; safe to call on every launch. */
    suspend fun seedBuiltInsIfNeeded() = withContext(ioDispatcher) {
        val entities = BuiltInPresets.ALL.map { preset ->
            PresetEntity(
                id = preset.id,
                name = preset.name,
                editJson = EditSerialization.encodeStack(preset.edits),
                isBuiltIn = true,
                createdAtMs = preset.createdAtMs,
            )
        }
        presetDao.upsertAll(entities)
    }

    /** Saves the tonal part of [stack] as a named user preset; geometry is deliberately dropped. */
    suspend fun savePreset(name: String, stack: EditStack): Preset = withContext(ioDispatcher) {
        val entity = PresetEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Untitled preset" },
            editJson = EditSerialization.encodeStack(stack.asPresetPayload()),
            isBuiltIn = false,
            createdAtMs = System.currentTimeMillis(),
        )
        presetDao.upsert(entity)
        entity.toPreset()
    }

    suspend fun deletePreset(presetId: String) = withContext(ioDispatcher) {
        presetDao.deleteUserPreset(presetId)
    }

    suspend fun getPreset(presetId: String): Preset? = withContext(ioDispatcher) {
        presetDao.findById(presetId)?.toPreset()
    }

    private fun PresetEntity.toPreset() = Preset(
        id = id,
        name = name,
        edits = EditSerialization.decodeStack(editJson),
        isBuiltIn = isBuiltIn,
        createdAtMs = createdAtMs,
    )
}
