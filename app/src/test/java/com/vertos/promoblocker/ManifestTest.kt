package com.vertos.promoblocker

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManifestTest {
    @Test fun serviceIsSystemProtectedAndAppUsesHttpsForCloudSync() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = context.packageManager.getServiceInfo(ComponentName(context, PromoCallScreeningService::class.java), 0)
        assertTrue(info.exported)
        assertEquals("android.permission.BIND_SCREENING_SERVICE", info.permission)
        val app = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(app.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertEquals(0, app.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertEquals(35, app.applicationInfo!!.targetSdkVersion)
    }
}
