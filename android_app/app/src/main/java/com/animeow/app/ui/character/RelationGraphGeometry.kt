package com.animeow.app.ui.character

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal const val RELATION_GRAPH_PAGE_SIZE = 8

internal data class RelationGraphPoint(val x: Float, val y: Float, val z: Float)

internal data class RelationGraphProjection(
    val x: Float,
    val y: Float,
    val depth: Float,
    val scale: Float,
)

internal data class RelationGraphCamera(
    val yaw: Float = 28f,
    val pitch: Float = 18f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun normalized(): RelationGraphCamera = copy(
        yaw = when {
            !yaw.isFinite() -> 28f
            yaw in -180f..180f -> yaw
            else -> ((yaw + 180f) % 360f + 360f) % 360f - 180f
        },
        pitch = pitch.finiteOr(18f).coerceIn(-55f, 55f),
        zoom = zoom.finiteOr(1f).coerceIn(0.6f, 2.5f),
        panX = panX.finiteOr(0f).coerceIn(-0.65f, 0.65f),
        panY = panY.finiteOr(0f).coerceIn(-0.65f, 0.65f),
    )
}

internal data class RelationGraphPage(
    val ids: List<Long>,
    val index: Int,
    val count: Int,
    val total: Int,
    val first: Int,
    val last: Int,
)

/** All direct relationships are reachable, including after a relationship is deleted. */
internal fun relationGraphPage(ids: List<Long>, requestedPage: Int): RelationGraphPage {
    val ordered = ids.distinct().sorted()
    val count = maxOf(1, (ordered.size + RELATION_GRAPH_PAGE_SIZE - 1) / RELATION_GRAPH_PAGE_SIZE)
    val index = requestedPage.coerceIn(0, count - 1)
    val start = index * RELATION_GRAPH_PAGE_SIZE
    val visible = ordered.drop(start).take(RELATION_GRAPH_PAGE_SIZE)
    return RelationGraphPage(visible, index, count, ordered.size, if (visible.isEmpty()) 0 else start + 1, start + visible.size)
}

/** Fixed sphere slots keep positions independent of the camera and relationship wording. */
internal fun relationGraphPoint(slot: Int, count: Int, spatial: Boolean): RelationGraphPoint {
    if (!spatial) {
        val angle = 2.0 * PI * slot / maxOf(1, count) - PI / 2.0
        return RelationGraphPoint(cos(angle).toFloat(), sin(angle).toFloat(), 0f)
    }
    // Visit opposite latitudes first, so small graphs also use both sides of the sphere.
    val order = intArrayOf(1, 6, 3, 4, 0, 7, 2, 5)
    val index = order[slot.coerceIn(0, order.lastIndex)]
    val y = 1f - 2f * (index + 0.5f) / RELATION_GRAPH_PAGE_SIZE
    val radius = sqrt(1f - y * y)
    val angle = index * PI * (3.0 - sqrt(5.0))
    return RelationGraphPoint((cos(angle) * radius).toFloat(), y, (sin(angle) * radius).toFloat())
}

/** Projection only changes display coordinates; it never writes layout into user data. */
internal fun projectRelationGraphPoint(
    point: RelationGraphPoint,
    camera: RelationGraphCamera,
    width: Float,
    height: Float,
    inset: Float,
    spatial: Boolean,
): RelationGraphProjection {
    val view = camera.normalized()
    var x = point.x
    var y = point.y
    var z = 0f
    if (spatial) {
        val yaw = view.yaw * PI / 180.0
        val pitch = view.pitch * PI / 180.0
        x = (point.x * cos(yaw) + point.z * sin(yaw)).toFloat()
        val rotatedZ = (-point.x * sin(yaw) + point.z * cos(yaw)).toFloat()
        y = (point.y * cos(pitch) - rotatedZ * sin(pitch)).toFloat()
        z = (point.y * sin(pitch) + rotatedZ * cos(pitch)).toFloat()
    }
    val perspective = if (spatial) 4.5f / (4.5f - z) else 1f
    val radius = min((width - inset * 2f).coerceAtLeast(0f), (height - inset * 2f).coerceAtLeast(0f)) * 0.4f
    return RelationGraphProjection(
        x = width / 2f + x * radius * perspective * view.zoom + view.panX * width,
        y = height / 2f + y * radius * perspective * view.zoom + view.panY * height,
        depth = z,
        scale = perspective.coerceIn(0.8f, 1.3f),
    )
}

/** Single-finger rotation is distinct from two-finger pan/zoom. Flat mode never rotates. */
internal fun moveRelationGraphCamera(
    camera: RelationGraphCamera,
    spatial: Boolean,
    multiTouch: Boolean,
    deltaX: Float,
    deltaY: Float,
    zoomChange: Float = 1f,
    centroidX: Float = 0f,
    centroidY: Float = 0f,
): RelationGraphCamera {
    if (!deltaX.isFinite() || !deltaY.isFinite() || !zoomChange.isFinite() || zoomChange <= 0f) return camera.normalized()
    val current = camera.normalized()
    if (spatial && !multiTouch) return current.copy(
        yaw = current.yaw + deltaX * 180f,
        pitch = current.pitch + deltaY * 120f,
    ).normalized()
    val nextZoom = if (multiTouch) (current.zoom * zoomChange).coerceIn(0.6f, 2.5f) else current.zoom
    val appliedZoom = nextZoom / current.zoom
    return current.copy(
        zoom = nextZoom,
        panX = current.panX * appliedZoom + centroidX.finiteOr(0f) * (1f - appliedZoom) + deltaX,
        panY = current.panY * appliedZoom + centroidY.finiteOr(0f) * (1f - appliedZoom) + deltaY,
    ).normalized()
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
