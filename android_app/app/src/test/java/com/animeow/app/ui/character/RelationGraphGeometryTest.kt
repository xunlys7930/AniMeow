package com.animeow.app.ui.character

import org.junit.Assert.*
import org.junit.Test

class RelationGraphGeometryTest {
    @Test
    fun everyRelationshipIsReachableAndPagesIgnoreQueryOrder() {
        val ids = (1L..23L).toList().reversed() + listOf(2L, 3L)
        val pages = (0..2).map { relationGraphPage(ids, it) }
        assertEquals((1L..23L).toList(), pages.flatMap { it.ids })
        assertEquals(listOf(8, 8, 7), pages.map { it.ids.size })
        assertEquals(17, pages.last().first)
        assertEquals(23, pages.last().last)
        assertEquals(23, pages.last().total)
        assertEquals(relationGraphPage(ids, 1), relationGraphPage(ids.sorted(), 1))
    }

    @Test
    fun deletingLastPageClampsToRemainingPageAndEmptyIsValid() {
        val reduced = relationGraphPage((1L..8L).toList(), 2)
        assertEquals(0, reduced.index)
        assertEquals(8, reduced.ids.size)
        val empty = relationGraphPage(emptyList(), -9)
        assertEquals(0, empty.first)
        assertEquals(0, empty.last)
        assertEquals(1, empty.count)
        assertTrue(empty.ids.isEmpty())
    }

    @Test
    fun sphereSlotsAreStableAndUseBothDepths() {
        val positions = (0 until RELATION_GRAPH_PAGE_SIZE).map { relationGraphPoint(it, 8, true) }
        positions.forEach { point ->
            assertEquals(1f, point.x * point.x + point.y * point.y + point.z * point.z, 0.0001f)
        }
        assertTrue(positions.any { it.z > 0.2f })
        assertTrue(positions.any { it.z < -0.2f })
        assertEquals(relationGraphPoint(0, 1, true), relationGraphPoint(0, 8, true))
        assertEquals(8, positions.distinct().size)
    }

    @Test
    fun rotatingChangesSpatialCoordinatesButNotFlatCoordinates() {
        val point = relationGraphPoint(2, 5, true)
        val initial = project(point, RelationGraphCamera(yaw = 0f, pitch = 0f))
        val rotated = project(point, RelationGraphCamera(yaw = 90f, pitch = 30f))
        assertNotEquals(initial.x, rotated.x)
        assertNotEquals(initial.depth, rotated.depth)
        val flat = relationGraphPoint(2, 5, false)
        assertEquals(project(flat, RelationGraphCamera(), false), project(flat, RelationGraphCamera(yaw = 90f, pitch = -30f), false))
    }

    @Test
    fun frontNodesAppearLargerAndCenterDoesNotDriftWhenRotating() {
        val camera = RelationGraphCamera(yaw = 0f, pitch = 0f)
        val front = project(RelationGraphPoint(0f, 0f, 1f), camera)
        val back = project(RelationGraphPoint(0f, 0f, -1f), camera)
        assertTrue(front.scale > back.scale)
        val center = project(RelationGraphPoint(0f, 0f, 0f), RelationGraphCamera(yaw = 142f, pitch = 55f, zoom = 2.5f))
        assertEquals(160f, center.x, 0.001f)
        assertEquals(200f, center.y, 0.001f)
        assertEquals(0f, center.depth, 0f)
    }

    @Test
    fun singleFingerRotatesSpaceAndTwoFingersPanWithoutRotating() {
        val initial = RelationGraphCamera(yaw = 0f, pitch = 0f)
        val rotated = moveRelationGraphCamera(initial, true, false, 0.25f, 0.1f)
        assertEquals(45f, rotated.yaw, 0.001f)
        assertEquals(12f, rotated.pitch, 0.001f)
        assertEquals(0f, rotated.panX, 0f)
        val moved = moveRelationGraphCamera(rotated, true, true, 0.1f, -0.1f, 1.5f)
        assertEquals(rotated.yaw, moved.yaw, 0f)
        assertEquals(rotated.pitch, moved.pitch, 0f)
        assertEquals(1.5f, moved.zoom, 0.001f)
        assertEquals(0.1f, moved.panX, 0.001f)
        val flat = moveRelationGraphCamera(initial, false, false, 0.2f, 0.1f)
        assertEquals(0f, flat.yaw, 0f)
        assertEquals(0.2f, flat.panX, 0.001f)
    }

    @Test
    fun pinchKeepsTheTouchedLocationFixedAndRespectsZoomLimit() {
        val initial = RelationGraphCamera(panX = 0.1f, panY = -0.1f)
        val zoomed = moveRelationGraphCamera(initial, true, true, 0f, 0f, 2f, centroidX = 0.25f, centroidY = -0.25f)
        assertEquals(-0.05f, zoomed.panX, 0.001f)
        assertEquals(0.05f, zoomed.panY, 0.001f)
        val limited = moveRelationGraphCamera(initial.copy(zoom = 2f), true, true, 0f, 0f, 9f, centroidX = 0.25f)
        assertEquals(2.5f, limited.zoom, 0.001f)
        assertEquals(0.0625f, limited.panX, 0.001f)
    }

    @Test
    fun invalidAndExtremeGesturesCannotLoseTheGraph() {
        val invalid = RelationGraphCamera(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NEGATIVE_INFINITY).normalized()
        assertEquals(RelationGraphCamera(), invalid)
        assertEquals(RelationGraphCamera(), moveRelationGraphCamera(RelationGraphCamera(), true, true, Float.NaN, 0f))
        val extreme = moveRelationGraphCamera(RelationGraphCamera(), true, true, 100f, -100f, 99f)
        assertEquals(2.5f, extreme.zoom, 0f)
        assertEquals(0.65f, extreme.panX, 0f)
        assertEquals(-0.65f, extreme.panY, 0f)
        val projection = project(RelationGraphPoint(1f, 0f, 0f), invalid)
        assertTrue(projection.x.isFinite() && projection.y.isFinite() && projection.scale.isFinite())
    }

    private fun project(point: RelationGraphPoint, camera: RelationGraphCamera, spatial: Boolean = true) =
        projectRelationGraphPoint(point, camera, 320f, 400f, 44f, spatial)
}
