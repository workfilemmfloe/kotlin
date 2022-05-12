/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.kpm.idea

import org.gradle.api.Project
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.gradle.internal.prepareCompilerArguments
import org.jetbrains.kotlin.gradle.plugin.mpp.pm20.KotlinGradleFragment
import org.jetbrains.kotlin.gradle.plugin.mpp.pm20.metadataCompilationRegistryByModuleId
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

interface IdeaKotlinCompilerArgumentsResolver {
    fun resolve(fragment: KotlinGradleFragment): CommonCompilerArguments?

    object Empty : IdeaKotlinCompilerArgumentsResolver {
        override fun resolve(fragment: KotlinGradleFragment): CommonCompilerArguments? = null
    }
}

fun IdeaKotlinCompilerArgumentsResolver(project: Project): IdeaKotlinCompilerArgumentsResolver =
    IdeaKotlinCompilerArgumentsResolverImpl(project)

private class IdeaKotlinCompilerArgumentsResolverImpl(private val project: Project) : IdeaKotlinCompilerArgumentsResolver {
    private val metadataCompilationRegistryByModuleId by lazy { project.metadataCompilationRegistryByModuleId }
    override fun resolve(fragment: KotlinGradleFragment): CommonCompilerArguments? {
        val compileTaskName = metadataCompilationRegistryByModuleId[fragment.containingModule.moduleIdentifier]
            ?.getForFragmentOrNull(fragment)
            ?.compileKotlinTaskName ?: return null
        return project.tasks.findByName(compileTaskName)
            ?.safeAs<AbstractKotlinCompileTool<CommonCompilerArguments>>()
            ?.prepareCompilerArguments()
    }
}