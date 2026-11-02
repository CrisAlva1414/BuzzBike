package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.ride.session.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [LocationPermissionUi]: the helper is pure Kotlin, so no
 * Robolectric is needed to cover every [LocationPermissionRequestResult] branch.
 */
class LocationPermissionUiTest {
    @Test
    fun given_noPermissionAndNotRequested_thenShowsGrantActionWithInitialMessage() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.NO_PERMISSION,
                result = LocationPermissionRequestResult.NOT_REQUESTED,
            )

        assertTrue(model.showGrantAction)
        assertEquals("DAR PERMISO", model.grantActionLabel)
        assertEquals("Falta permiso de ubicación para grabar.", model.message)
    }

    @Test
    fun given_searchingAndGranted_thenHidesGrantActionAndMessage() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.SEARCHING,
                result = LocationPermissionRequestResult.GRANTED,
            )

        assertFalse(model.showGrantAction)
        assertNull(model.message)
    }

    @Test
    fun given_searchingAndDenied_thenGpsStatusWinsAndHidesGrantAction() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.SEARCHING,
                result = LocationPermissionRequestResult.DENIED,
            )

        assertFalse(model.showGrantAction)
        assertNull(model.message)
    }

    @Test
    fun given_noPermissionAndDenied_thenShowsDeniedMessage() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.NO_PERMISSION,
                result = LocationPermissionRequestResult.DENIED,
            )

        assertTrue(model.showGrantAction)
        assertEquals("DAR PERMISO", model.grantActionLabel)
        assertEquals("Sin permiso de ubicación no se puede grabar un ride.", model.message)
    }

    @Test
    fun given_noPermissionAndGrantedDefensively_thenFallsBackToInitialMessage() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.NO_PERMISSION,
                result = LocationPermissionRequestResult.GRANTED,
            )

        assertTrue(model.showGrantAction)
        assertEquals("DAR PERMISO", model.grantActionLabel)
        assertEquals("Falta permiso de ubicación para grabar.", model.message)
    }

    @Test
    fun given_noPermissionAndPermanentDenial_thenOffersRetryAndOpenSettings() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.NO_PERMISSION,
                result = LocationPermissionRequestResult.DENIED_PERMANENTLY,
            )

        assertTrue(model.showGrantAction)
        assertEquals("DAR PERMISO", model.grantActionLabel)
        assertTrue(model.showOpenSettingsAction)
        assertEquals("ABRIR AJUSTES", model.openSettingsActionLabel)
        assertEquals(
            "El permiso de ubicación está bloqueado. Habilitalo en Ajustes y volvé a intentar.",
            model.message,
        )
    }

    @Test
    fun given_noPermissionAndRetryableDenial_thenDoesNotOfferOpenSettings() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.NO_PERMISSION,
                result = LocationPermissionRequestResult.DENIED,
            )

        assertTrue(model.showGrantAction)
        assertFalse(model.showOpenSettingsAction)
        assertEquals("ABRIR AJUSTES", model.openSettingsActionLabel)
    }

    @Test
    fun given_fineAndCoarseGranted_when_classify_thenGranted() {
        assertEquals(
            LocationPermissionRequestResult.GRANTED,
            LocationPermissionUi.classify(fineGranted = true, coarseGranted = true),
        )
    }

    @Test
    fun given_onlyCoarseGranted_when_classify_thenApproximate() {
        assertEquals(
            LocationPermissionRequestResult.APPROXIMATE,
            LocationPermissionUi.classify(fineGranted = false, coarseGranted = true),
        )
    }

    @Test
    fun given_noneGranted_when_classify_thenDenied() {
        assertEquals(
            LocationPermissionRequestResult.DENIED,
            LocationPermissionUi.classify(fineGranted = false, coarseGranted = false),
        )
    }

    @Test
    fun given_deniedWithoutRationale_when_classify_thenPermanentDenial() {
        assertEquals(
            LocationPermissionRequestResult.DENIED_PERMANENTLY,
            LocationPermissionUi.classify(
                fineGranted = false,
                coarseGranted = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun given_deniedWithRationale_when_classify_thenRetryableDenial() {
        assertEquals(
            LocationPermissionRequestResult.DENIED,
            LocationPermissionUi.classify(
                fineGranted = false,
                coarseGranted = false,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun given_onlyFineGranted_when_classify_thenGranted() {
        assertEquals(
            LocationPermissionRequestResult.GRANTED,
            LocationPermissionUi.classify(fineGranted = true, coarseGranted = false),
        )
    }

    @Test
    fun given_noPermissionAndApproximate_thenShowsApproximateMessage() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.NO_PERMISSION,
                result = LocationPermissionRequestResult.APPROXIMATE,
            )

        assertTrue(model.showGrantAction)
        assertEquals("DAR PERMISO", model.grantActionLabel)
        assertEquals(
            "Con ubicación aproximada no se puede grabar. Elegí «Precisa» en el permiso.",
            model.message,
        )
    }

    @Test
    fun given_fixAndNotRequested_thenHidesGrantActionAndMessage() {
        val model =
            LocationPermissionUi.resolve(
                gps = GpsStatus.FIX,
                result = LocationPermissionRequestResult.NOT_REQUESTED,
            )

        assertFalse(model.showGrantAction)
        assertNull(model.message)
    }
}
