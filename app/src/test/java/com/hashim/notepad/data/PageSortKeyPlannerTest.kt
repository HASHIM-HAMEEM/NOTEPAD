package com.hashim.notepad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSortKeyPlannerTest {
    @Test
    fun moveToFirstUsesOneLessThanTheNextPage() {
        val plan = PageSortKeyPlanner.move(listOf(1.0, 2.0, 3.0), fromIndex = 2, toIndex = 0)

        assertEquals(listOf(2, 0, 1), plan.order)
        assertEquals(listOf(0.0, 1.0, 2.0), plan.sortKeys)
        assertFalse(plan.renumbered)
    }

    @Test
    fun moveToLastUsesOneMoreThanThePreviousPage() {
        val plan = PageSortKeyPlanner.move(listOf(1.0, 2.0, 3.0), fromIndex = 0, toIndex = 2)

        assertEquals(listOf(1, 2, 0), plan.order)
        assertEquals(listOf(2.0, 3.0, 4.0), plan.sortKeys)
        assertFalse(plan.renumbered)
    }

    @Test
    fun moveToMiddleUsesTheNeighbourMidpoint() {
        val plan = PageSortKeyPlanner.move(listOf(1.0, 2.0, 3.0, 4.0), fromIndex = 3, toIndex = 1)

        assertEquals(listOf(0, 3, 1, 2), plan.order)
        assertEquals(listOf(1.0, 1.5, 2.0, 3.0), plan.sortKeys)
        assertFalse(plan.renumbered)
    }

    @Test
    fun tinyNeighbourGapRenumbersTheWholeMovedOrder() {
        val plan = PageSortKeyPlanner.move(listOf(1.0, 1.0 + 1e-7, 2.0), fromIndex = 2, toIndex = 1)

        assertEquals(listOf(0, 2, 1), plan.order)
        assertEquals(listOf(1.0, 2.0, 3.0), plan.sortKeys)
        assertTrue(plan.renumbered)
    }

    @Test
    fun insertAfterUsesMidpointAndRenumbersWhenTheGapIsTiny() {
        val midpoint = PageSortKeyPlanner.insertAfter(listOf(1.0, 3.0), afterIndex = 0)
        assertEquals(2.0, midpoint.sortKey, 0.0)
        assertEquals(null, midpoint.renumberedKeys)

        val tinyGap = PageSortKeyPlanner.insertAfter(listOf(1.0, 1.0 + 1e-7, 2.0), afterIndex = 0)
        assertEquals(1.5, tinyGap.sortKey, 0.0)
        assertEquals(listOf(1.0, 2.0, 3.0), tinyGap.renumberedKeys)
    }
}
