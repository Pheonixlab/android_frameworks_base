/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.decor

import android.content.Context
import android.util.Log
import android.view.DisplayCutout
import android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM
import android.view.DisplayCutout.BOUNDS_POSITION_LEFT
import android.view.DisplayCutout.BOUNDS_POSITION_RIGHT
import android.view.DisplayCutout.BOUNDS_POSITION_TOP
import android.view.DisplayInfo
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.FaceScanningOverlay
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.statusbar.StatusBarStateController
import javax.inject.Inject

@SysUISingleton
class FaceScanningProviderFactory @Inject constructor(
    private val authController: AuthController,
    private val context: Context,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val featureFlags: FeatureFlags
) : DecorProviderFactory() {
    private val display = context.display
    private val displayInfo = DisplayInfo()

    override val hasProviders: Boolean
        get() {
            // update display info:
            display?.getDisplayInfo(displayInfo) ?: run {
                Log.w(TAG, "display is null, can't update displayInfo")
            }
            val hasDisplayCutout = DisplayCutout.getFillBuiltInDisplayCutout(
                    context.resources, displayInfo.uniqueId)
            return hasDisplayCutout &&
                    authController.faceAuthSensorLocation != null &&
                    featureFlags.isEnabled(Flags.FACE_SCANNING_ANIM)
        }

    override val providers: List<DecorProvider>
        get() {
            if (!hasProviders) {
                return emptyList()
            }

            return ArrayList<DecorProvider>().also { list ->
                // displayInfo must be updated before using it; however it will already have
                // been updated when accessing the hasProviders field above
                displayInfo.displayCutout?.getBoundBaseOnCurrentRotation()?.let { bounds ->
                    // Add a face scanning view for each screen orientation.
                    // Cutout drawing is updated in ScreenDecorations#updateCutout
                    for (bound in bounds) {
                        list.add(
                                FaceScanningOverlayProviderImpl(
                                        bound.baseOnRotation0(displayInfo.rotation),
                                        authController,
                                        statusBarStateController,
                                        keyguardUpdateMonitor)
                        )
                    }
                }
            }
        }

    fun canShowFaceScanningAnim(): Boolean {
        return hasProviders && keyguardUpdateMonitor.isFaceEnrolled
    }

    fun shouldShowFaceScanningAnim(): Boolean {
        return canShowFaceScanningAnim() && keyguardUpdateMonitor.isFaceScanning
    }
}

class FaceScanningOverlayProviderImpl(
    override val alignedBound: Int,
    private val authController: AuthController,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor
) : BoundDecorProvider() {
    override val viewId: Int = com.android.systemui.R.id.face_scanning_anim

    override fun onReloadResAndMeasure(
        view: View,
        reloadToken: Int,
        rotation: Int,
        displayUniqueId: String?
    ) {
        // no need to handle rotation changes
    }

    override fun inflateView(
        context: Context,
        parent: ViewGroup,
        @Surface.Rotation rotation: Int
    ): View {
        val view = FaceScanningOverlay(
                context,
                alignedBound,
                statusBarStateController,
                keyguardUpdateMonitor)
        view.id = viewId
        view.visibility = View.INVISIBLE // only show this view when face scanning is happening
        var heightLayoutParam = ViewGroup.LayoutParams.MATCH_PARENT
        authController.faceAuthSensorLocation?.y?.let {
            heightLayoutParam = (it * 3).toInt()
        }
        parent.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightLayoutParam,
                Gravity.TOP or Gravity.START))
        return view
    }
}

fun DisplayCutout.getBoundBaseOnCurrentRotation(): List<Int> {
    return ArrayList<Int>().also {
        if (!boundingRectLeft.isEmpty) {
            it.add(BOUNDS_POSITION_LEFT)
        }
        if (!boundingRectTop.isEmpty) {
            it.add(BOUNDS_POSITION_TOP)
        }
        if (!boundingRectRight.isEmpty) {
            it.add(BOUNDS_POSITION_RIGHT)
        }
        if (!boundingRectBottom.isEmpty) {
            it.add(BOUNDS_POSITION_BOTTOM)
        }
    }
}

fun Int.baseOnRotation0(@DisplayCutout.BoundsPosition currentRotation: Int): Int {
    return when (currentRotation) {
        Surface.ROTATION_0 -> this
        Surface.ROTATION_90 -> when (this) {
            BOUNDS_POSITION_LEFT -> BOUNDS_POSITION_TOP
            BOUNDS_POSITION_TOP -> BOUNDS_POSITION_RIGHT
            BOUNDS_POSITION_RIGHT -> BOUNDS_POSITION_BOTTOM
            else /* BOUNDS_POSITION_BOTTOM */ -> BOUNDS_POSITION_LEFT
        }
        Surface.ROTATION_270 -> when (this) {
            BOUNDS_POSITION_LEFT -> BOUNDS_POSITION_BOTTOM
            BOUNDS_POSITION_TOP -> BOUNDS_POSITION_LEFT
            BOUNDS_POSITION_RIGHT -> BOUNDS_POSITION_TOP
            else /* BOUNDS_POSITION_BOTTOM */ -> BOUNDS_POSITION_RIGHT
        }
        else /* Surface.ROTATION_180 */ -> when (this) {
            BOUNDS_POSITION_LEFT -> BOUNDS_POSITION_RIGHT
            BOUNDS_POSITION_TOP -> BOUNDS_POSITION_BOTTOM
            BOUNDS_POSITION_RIGHT -> BOUNDS_POSITION_LEFT
            else /* BOUNDS_POSITION_BOTTOM */ -> BOUNDS_POSITION_TOP
        }
    }
}

private const val TAG = "FaceScanningProvider"
