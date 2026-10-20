package com.hashim.notepad.data

internal object PageSortKeyPlanner {
    private const val MINIMUM_GAP = 1e-6

    data class MovePlan(
        val order: List<Int>,
        val sortKeys: List<Double>,
        val renumbered: Boolean,
    )

    data class InsertPlan(
        val sortKey: Double,
        val renumberedKeys: List<Double>? = null,
    )

    fun move(sortKeys: List<Double>, fromIndex: Int, toIndex: Int): MovePlan {
        require(fromIndex in sortKeys.indices)
        require(toIndex in sortKeys.indices)

        val order = sortKeys.indices.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        if (fromIndex == toIndex) return MovePlan(order, order.map(sortKeys::get), false)

        val position = order.indexOf(fromIndex)
        val previous = order.getOrNull(position - 1)?.let(sortKeys::get)
        val next = order.getOrNull(position + 1)?.let(sortKeys::get)
        if (previous != null && next != null && next - previous < MINIMUM_GAP) {
            return MovePlan(order, order.indices.map { it + 1.0 }, true)
        }

        val movedKey = when {
            previous == null -> requireNotNull(next) - 1.0
            next == null -> previous + 1.0
            else -> previous + (next - previous) / 2.0
        }
        return MovePlan(
            order = order,
            sortKeys = order.map { originalIndex ->
                if (originalIndex == fromIndex) movedKey else sortKeys[originalIndex]
            },
            renumbered = false,
        )
    }

    fun insertAfter(sortKeys: List<Double>, afterIndex: Int?): InsertPlan {
        if (sortKeys.isEmpty()) return InsertPlan(1.0)
        val index = afterIndex?.takeIf { it in sortKeys.indices } ?: sortKeys.lastIndex
        val next = sortKeys.getOrNull(index + 1)
        if (next == null) return InsertPlan(sortKeys[index] + 1.0)

        val current = sortKeys[index]
        if (next - current < MINIMUM_GAP) {
            return InsertPlan(index + 1.5, sortKeys.indices.map { it + 1.0 })
        }
        return InsertPlan(current + (next - current) / 2.0)
    }
}
