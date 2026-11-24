@file:OptIn(ExperimentalAtomicApi::class)

package org.hnau.remotestate

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

class IdFactory<out T>(
    private val create: (Long) -> T,
) {

    private val lastId: AtomicLong = AtomicLong(0)

    fun next(): T = create(lastId.incrementAndFetch())
}
