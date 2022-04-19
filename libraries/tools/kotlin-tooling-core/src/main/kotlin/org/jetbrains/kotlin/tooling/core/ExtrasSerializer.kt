/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tooling.core

interface ExtrasSerializer<in T : Any> {
    fun serialize(value: T): ByteArray
}

interface ExtrasSerializationService {
    fun <T : Any> getSerializer(key: Extras.Key<T>): ExtrasSerializer<T>?
}

operator fun ExtrasSerializationService.plus(other: ExtrasSerializationService): ExtrasSerializationService {
    if (this is CompositeExtrasSerializationService && other is CompositeExtrasSerializationService) {
        return CompositeExtrasSerializationService(this.services + other.services)
    }

    if (this is CompositeExtrasSerializationService) {
        return CompositeExtrasSerializationService(this.services + other)
    }

    if (other is CompositeExtrasSerializationService) {
        return CompositeExtrasSerializationService(listOf(this) + other.services)
    }

    return CompositeExtrasSerializationService(listOf(this, other))
}

private class CompositeExtrasSerializationService(
    val services: List<ExtrasSerializationService>
) : ExtrasSerializationService {
    override fun <T : Any> getSerializer(key: Extras.Key<T>): ExtrasSerializer<T>? {
        return services.asReversed().asSequence().map { service -> service.getSerializer(key) }.filterNotNull().firstOrNull()
    }
}
