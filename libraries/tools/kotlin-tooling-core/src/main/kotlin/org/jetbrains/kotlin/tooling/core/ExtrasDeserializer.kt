/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tooling.core

interface ExtrasDeserializer<out T : Any> {
    fun deserialize(data: ByteArray): T?
}

interface ExtrasDeserializationService {
    fun <T : Any> getDeserializer(key: Extras.Key<T>): ExtrasDeserializer<T>?
}

operator fun ExtrasDeserializationService.plus(other: ExtrasDeserializationService): ExtrasDeserializationService {
    if (this is CompositeExtrasDeserializationService && other is CompositeExtrasDeserializationService) {
        return CompositeExtrasDeserializationService(this.services + other.services)
    }

    if (this is CompositeExtrasDeserializationService) {
        return CompositeExtrasDeserializationService(this.services + other)
    }

    if (other is CompositeExtrasDeserializationService) {
        return CompositeExtrasDeserializationService(listOf(this) + other.services)
    }

    return CompositeExtrasDeserializationService(listOf(this, other))
}

private class CompositeExtrasDeserializationService(
    val services: List<ExtrasDeserializationService>
) : ExtrasDeserializationService {
    override fun <T : Any> getDeserializer(key: Extras.Key<T>): ExtrasDeserializer<T>? {
        return services.asReversed().asSequence().map { service -> service.getDeserializer(key) }.filterNotNull().firstOrNull()
    }
}
