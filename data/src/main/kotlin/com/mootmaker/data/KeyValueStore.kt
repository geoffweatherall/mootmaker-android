package com.mootmaker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** The small amount of state the app keeps on the device: the environment, its config and tokens. */
interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun get(key: String) = synchronized(values) { values[key] }
    override suspend fun put(key: String, value: String) = synchronized(values) { values[key] = value }
    override suspend fun remove(key: String) = synchronized(values) { values.remove(key); Unit }
}

private val Context.mootmakerStore by preferencesDataStore(name = "mootmaker")

class DataStoreKeyValueStore(context: Context) : KeyValueStore {
    private val store = context.applicationContext.mootmakerStore

    override suspend fun get(key: String): String? = store.data.first()[stringPreferencesKey(key)]
    override suspend fun put(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }
    override suspend fun remove(key: String) {
        store.edit { it.remove(stringPreferencesKey(key)) }
    }
}
