package com.vertos.promoblocker

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchArtworkTest {
    @Test fun logoAndTitleRemainSeparatedWhenTheSystemSizesTheSplash() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Application>(),
            R.style.Theme_PromoBlocker_Launch,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            for (size in listOf(144, 192, 288)) {
                assertLogoAboveTitle(context, android.R.attr.windowSplashScreenAnimatedIcon, size, size, size / 18)
            }
        } else {
            assertLogoAboveTitle(context, android.R.attr.windowBackground, 360, 640, 16)
        }
    }

    private fun assertLogoAboveTitle(
        context: ContextThemeWrapper,
        attribute: Int,
        width: Int,
        height: Int,
        minimumGap: Int,
    ) {
        val attributes = context.obtainStyledAttributes(intArrayOf(attribute))
        val artwork = checkNotNull(attributes.getDrawable(0))
        attributes.recycle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        artwork.setBounds(0, 0, width, height)
        artwork.draw(Canvas(bitmap))
        val background = bitmap.getPixel(0, 0)
        val occupiedRows = (0 until height).filter { y ->
            (0 until width).any { x -> bitmap.getPixel(x, y) != background }
        }
        val gaps = occupiedRows.zipWithNext().map { (previous, next) -> next - previous - 1 }
            .filter { it >= minimumGap }

        assertTrue("Logo and title must both render at ${width}×${height}", occupiedRows.isNotEmpty())
        assertEquals("One clear gap must separate the logo and title at ${width}×${height}", 1, gaps.size)
    }
}
