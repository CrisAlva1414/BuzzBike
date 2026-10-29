package com.buzzbike.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.SenderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for [SharedPrefsSyncConfigStore] against Robolectric's
 * `SharedPreferences` implementation.
 *
 * A fresh store instance is used for reads so every assertion goes through
 * disk (the real preferences file), not an in-memory field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPrefsSyncConfigStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        newStore().clear()
    }

    @Test
    fun given_noConfiguration_when_current_then_null() {
        assertNull(newStore().current())
    }

    @Test
    fun given_savedConfiguration_when_newInstanceReads_then_roundTripIsExact() {
        val config =
            HermesConfig(
                endpoint = "https://hermes.example/sync",
                method = "PUT",
                timeoutSeconds = 45,
                retryCount = 7,
                senderType = SenderType.REST,
                healthCheckUrl = "https://hermes.example/health",
            )

        newStore().save(config)

        assertEquals(config, newStore().current())
    }

    @Test
    fun given_savedConfiguration_when_clear_then_currentIsNull() {
        newStore().save(CONFIG)

        newStore().clear()

        assertNull(newStore().current())
    }

    @Test
    fun given_savedConfigurationWithoutHealthCheck_when_current_then_null() {
        newStore().save(HermesConfig(endpoint = "https://hermes.example/sync"))

        assertNull(newStore().current())
    }

    private fun newStore(): SharedPrefsSyncConfigStore = SharedPrefsSyncConfigStore(context)

    private companion object {
        val CONFIG =
            HermesConfig(
                endpoint = "https://hermes.example/sync",
                healthCheckUrl = "https://hermes.example/health",
            )
    }
}
