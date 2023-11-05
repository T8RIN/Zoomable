/*
 * Copyright 2022 usuiat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.engawapg.lib.zoomable

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Customized transform gesture detector.
 *
 * A caller of this function can choose if the pointer events will be consumed.
 * And the caller can implement [onGestureStart] and [onGestureEnd] event.
 *
 * @param onGesture Gesture handler.
 * @param onGestureStart This lambda is called when a gesture starts.
 * @param onGestureEnd This lambda is called when a gesture ends.
 * @param onTap will be called when single tap is detected.
 * @param onDoubleTap will be called when double tap is detected.
 * @param enableOneFingerZoom If true, enable one finger zoom gesture, double tap followed by
 * vertical scrolling.
 */
private suspend fun PointerInputScope.detectTransformGestures(
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, timeMillis: Long) -> Unit,
    onGestureStart: () -> Unit = {},
    onGestureEnd: (dragVelocity: Velocity) -> Unit = {},
    onTap: (position: Offset) -> Unit = {},
    onDoubleTap: (position: Offset) -> Unit = {},
    enableOneFingerZoom: Boolean = true,
) = awaitEachGesture {
    val firstDown = awaitFirstDown(requireUnconsumed = false)
    onGestureStart()

    var firstUp: PointerInputChange = firstDown
    var hasMoved = false
    var isMultiTouch = false
    var isLongPressed = false
    val touchSlop = TouchSlop(viewConfiguration.touchSlop)
    val velocityTracker = VelocityTracker()
    forEachPointerEventUntilReleased { event ->
        velocityTracker.addPointerInputChange(event.changes[0])
        if (touchSlop.isPast(event)) {
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (zoomChange != 1f || panChange != Offset.Zero) {
                val centroid = event.calculateCentroid(useCurrent = false)
                val timeMillis = event.changes[0].uptimeMillis
                onGesture(centroid, panChange, zoomChange, timeMillis)
            }
            hasMoved = true
        }
        if (event.changes.size > 1) {
            isMultiTouch = true
        }
        firstUp = event.changes[0]
        event.consumePositionChanges()
    }

    if (firstUp.uptimeMillis - firstDown.uptimeMillis > viewConfiguration.longPressTimeoutMillis) {
        isLongPressed = true
    }

    val isTap = !hasMoved && !isMultiTouch && !isLongPressed
    // Vertical scrolling following a double tap is treated as a zoom gesture.
    if (isTap) {
        val secondDown = awaitSecondDown(firstUp)
        if (secondDown == null) {
            onTap(firstUp.position)
        } else {
            var isDoubleTap = true
            var secondUp: PointerInputChange = secondDown
            val secondTouchSlop = TouchSlop(viewConfiguration.touchSlop)
            forEachPointerEventUntilReleased { event ->
                if (secondTouchSlop.isPast(event)) {
                    if (enableOneFingerZoom) {
                        val panChange = event.calculatePan()
                        val zoomChange = 1f + panChange.y * 0.004f
                        if (zoomChange != 1f) {
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val timeMillis = event.changes[0].uptimeMillis
                            onGesture(centroid, Offset.Zero, zoomChange, timeMillis)
                        }
                    }
                    isDoubleTap = false
                }
                if (event.changes.size > 1) {
                    isDoubleTap = false
                }
                secondUp = event.changes[0]
                event.consumePositionChanges()
            }

            if (secondUp.uptimeMillis - secondDown.uptimeMillis > viewConfiguration.longPressTimeoutMillis) {
                isDoubleTap = false
            }

            if (isDoubleTap) {
                onDoubleTap(secondUp.position)
            }
        }
    }

    val isDrag = hasMoved && !isMultiTouch
    val dragVelocity = if (isDrag) velocityTracker.calculateVelocity() else Velocity.Zero
    onGestureEnd(dragVelocity)
}

/**
 * Invoke action for each PointerEvent until all pointers are released.
 *
 * @param action Callback function that will be called every PointerEvents occur.
 */
private suspend fun AwaitPointerEventScope.forEachPointerEventUntilReleased(
    action: (PointerEvent) -> Unit,
) {
    do {
        val event = awaitPointerEvent()
        if (event.changes.fastAny { it.isConsumed }) {
            break
        }
        action(event)
    } while (event.changes.fastAny { it.pressed })
}

/**
 * Await second down or timeout from first up
 *
 * @param firstUp The first up event
 * @return If the second down event comes before timeout, returns it. If not, returns null.
 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    firstUp: PointerInputChange
): PointerInputChange? = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
    val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
    var change: PointerInputChange
    // The second tap doesn't count if it happens before DoubleTapMinTime of the first tap
    do {
        change = awaitFirstDown()
    } while (change.uptimeMillis < minUptime)
    change
}

/**
 * Consume event if the position is changed.
 */
private fun PointerEvent.consumePositionChanges() {
    changes.fastForEach {
        if (it.positionChanged()) {
            it.consume()
        }
    }
}

/**
 * Touch slop detector.
 *
 * This class holds accumulated zoom and pan value to see if touch slop is past.
 *
 * @param threshold Threshold of movement of gesture after touch down. If the movement exceeds this
 * value, it is judged to be a swipe or zoom gesture.
 */
private class TouchSlop(private val threshold: Float) {
    private var zoom = 1f
    private var pan = Offset.Zero
    private var _isPast = false

    /**
     * Judge the touch slop is past.
     *
     * @param event Event that occurs this time.
     * @return True if the accumulated zoom or pan exceeds the threshold.
     */
    fun isPast(event: PointerEvent): Boolean {
        if (_isPast) {
            return true
        }

        zoom *= event.calculateZoom()
        pan += event.calculatePan()
        val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
        val panMotion = pan.getDistance()
        _isPast = zoomMotion > threshold || panMotion > threshold

        return _isPast
    }
}

/**
 * Modifier function that make the content zoomable.
 *
 * @param zoomState A [ZoomState] object.
 * @param enableOneFingerZoom If true, enable one finger zoom gesture, double tap followed by
 * vertical scrolling.
 * @param onTap will be called when single tap is detected on the element.
 * @param onDoubleTap will be called when double tap is detected on the element. This is a suspend
 * function and called in a coroutine scope. The default is to toggle the scale between 1.0f and
 * 2.5f with animation.
 */
fun Modifier.zoomable(
    zoomState: ZoomState,
    enableOneFingerZoom: Boolean = true,
    onTap: (position: Offset) -> Unit = {},
    onDoubleTap: suspend (position: Offset) -> Unit = { position -> zoomState.toggleScale(2.5f, position) },
): Modifier = composed(
    inspectorInfo = debugInspectorInfo {
        name = "zoomable"
        properties["zoomState"] = zoomState
    }
) {
    val scope = rememberCoroutineScope()
    val connection = remember { object : NestedScrollConnection{} }
    val dispatcher = remember { NestedScrollDispatcher() }
    var canConsume = false
    Modifier
        .onSizeChanged { size ->
            zoomState.setLayoutSize(size.toSize())
        }
        .nestedScroll(connection, dispatcher)
        .pointerInput(zoomState) {
            detectTransformGestures(
                onGestureStart = { zoomState.startGesture() },
                onGesture = { centroid, pan, zoom, timeMillis ->
                    canConsume = zoomState.canConsumeGesture(pan = pan, zoom = zoom)
                    if (canConsume) {
                        scope.launch {
                            zoomState.applyGesture(
                                pan = pan,
                                zoom = zoom,
                                position = centroid,
                                timeMillis = timeMillis,
                            )
                        }
                    } else {
                        dispatcher.dispatchPostScroll(Offset.Zero, pan, NestedScrollSource.Drag)
                    }
                },
                onGestureEnd = { dragVelocity ->
                    scope.launch {
                        zoomState.endGesture()
                    }
                    if (!canConsume) {
                        dispatcher.coroutineScope.launch {
                            dispatcher.dispatchPostFling(Velocity.Zero, dragVelocity)
                        }
                    }
                },
                onTap = onTap,
                onDoubleTap = { position ->
                    scope.launch {
                        onDoubleTap(position)
                    }
                },
                enableOneFingerZoom = enableOneFingerZoom,
            )
        }
        .graphicsLayer {
            scaleX = zoomState.scale
            scaleY = zoomState.scale
            translationX = zoomState.offsetX
            translationY = zoomState.offsetY
        }
}

/**
 * Toggle the scale between [targetScale] and 1.0f.
 *
 * @param targetScale Scale to be set if this function is called when the scale is 1.0f.
 * @param position Zoom around this point.
 * @param animationSpec The animation configuration.
 */
suspend fun ZoomState.toggleScale(
    targetScale: Float,
    position: Offset,
    animationSpec: AnimationSpec<Float> = spring(),
) {
    val newScale = if (scale == 1f) targetScale else 1f
    changeScale(newScale, position, animationSpec)
}
