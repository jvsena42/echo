package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [LocalKeyStore], mirroring the platform implementations' observable behaviour: custody
 * is derived from the stored key, [markBackedUp] is additive, and [clear] is unguarded.
 */
internal class FakeLocalKeyStore(initial: LocalKey? = null) : LocalKeyStore {

    private var stored: LocalKey? = initial

    private val _custody = MutableStateFlow(stored?.toCustody() ?: KeyCustody.External)
    override val custody: Flow<KeyCustody> = _custody.asStateFlow()

    /** Counts writes, so a test can assert a redundant markBackedUp does not churn the keystore. */
    var writes = 0
        private set

    override suspend fun save(key: LocalKey) {
        stored = key
        writes++
        _custody.update { key.toCustody() }
    }

    override suspend fun current(): LocalKey? = stored

    override suspend fun markBackedUp(method: BackupMethod) {
        val existing = stored ?: return
        if (method in existing.backedUpBy) return
        val updated = existing.copy(backedUpBy = existing.backedUpBy + method)
        stored = updated
        writes++
        _custody.update { updated.toCustody() }
    }

    override suspend fun clear() {
        stored = null
        _custody.update { KeyCustody.External }
    }
}
