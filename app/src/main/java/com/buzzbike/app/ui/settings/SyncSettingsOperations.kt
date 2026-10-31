package com.buzzbike.app.ui.settings

import com.buzzbike.app.domain.sync.ConnectivityChecker
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.OutboxStore
import com.buzzbike.app.domain.sync.SyncConfigStore
import com.buzzbike.app.domain.sync.SyncMutationGate
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import javax.inject.Inject

/**
 * Injectable bundle of the sync ports used by [SyncSettingsViewModel].
 *
 * The view-model needs six collaborators, so grouping them here keeps its
 * constructor small while preserving the exact dependency graph: every field
 * is the same process-wide singleton used by the rest of the sync stack,
 * including the shared [SyncMutationGate].
 */
class SyncSettingsOperations
    @Inject
    constructor(
        val store: SyncConfigStore,
        val secretManager: HmacSecretManager,
        val scheduler: SyncWorkScheduler,
        val outboxStore: OutboxStore,
        val connectivity: ConnectivityChecker,
        val gate: SyncMutationGate,
    )
