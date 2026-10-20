package com.aasif.watchdog.core

import com.aasif.watchdog.core.merchant.BrandApps
import com.aasif.watchdog.core.merchant.MerchantCatalog
import com.aasif.watchdog.core.merchant.brandId
import com.aasif.watchdog.core.merchant.brandPackage
import com.aasif.watchdog.core.merchant.verifyPlayCandidate
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.usage.TrackedApps
import java.math.BigDecimal
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrandAppsTest {
    @Test
    fun everyBrandIsACatalogMerchant() {
        val ids = MerchantCatalog.all.map { it.id }.toSet()
        assertEquals(emptySet(), BrandApps.packages.keys - ids)
    }

    @Test
    fun trackedAppsUseTheSamePackageAsTheirLogo() {
        for (app in TrackedApps.all) assertTrue(BrandApps.packages[app.merchantId] in app.packages, app.merchantId)
    }

    @Test
    fun manifestCanSeeEveryBrandApp() {
        // Without a <queries> entry Android 11+ hides the installed app, and its own icon could never be shown.
        val manifest = File("../app/src/main/AndroidManifest.xml").readText()
        val missing = BrandApps.packages.values.filter { "<package android:name=\"$it\" />" !in manifest }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun aKnownMerchantWearsItsLogo() {
        val sub = sub(merchantId = "spotify", name = "Spotify")
        assertEquals("spotify", sub.brandId())
        assertEquals("com.spotify.music", sub.brandPackage())
    }

    @Test
    fun aHandAddedServiceIsMatchedByName() {
        assertEquals("spotify", sub(merchantId = "manual:1", name = "Spotify family").brandId())
        assertNull(sub(merchantId = "manual:2", name = "Gym").brandId())
    }

    @Test
    fun aSharedBillingChargeHasNoLogo() {
        assertNull(sub(merchantId = "googleplay", name = "Google Play", sharedBilling = true).brandId())
    }

    @Test
    fun aPlayHitIsUsedOnlyWhenTitleAndPackageBothNameTheService() {
        assertTrue(verifyPlayCandidate("Headspace", "com.getsomeheadspace.android", "Headspace: Meditation & Sleep").not())
        assertTrue(verifyPlayCandidate("Headspace", "com.headspace.android", "Headspace: Meditation & Sleep"))
        assertTrue(verifyPlayCandidate("Calm", "com.calm.android", "Calm - Sleep, Meditate, Relax"))
    }

    @Test
    fun lookalikesAndVagueNamesAreRejected() {
        // A clone copies the title but can't take the real package id.
        assertFalse(verifyPlayCandidate("Headspace", "com.clone.meditation", "Headspace"))
        // The name is only part of a different app's title.
        assertFalse(verifyPlayCandidate("Gym", "com.gym.tracker", "Gym Workout Tracker"))
        assertFalse(verifyPlayCandidate("Fitness", "com.fitness.app", "Fitness Coach"))
    }

    private fun sub(merchantId: String, name: String, sharedBilling: Boolean = false) = Subscription(
        key = merchantId, merchantId = merchantId, name = name, category = "Test",
        amount = Money(BigDecimal(199)), frequency = Frequency.MONTHLY, nextRenewal = null,
        renewalSource = RenewalSource.MANDATE, confidence = Confidence.HIGH, status = SubscriptionStatus.ACTIVE,
        charges = emptyList(), cancelUrl = null, sharedBilling = sharedBilling,
    )
}
