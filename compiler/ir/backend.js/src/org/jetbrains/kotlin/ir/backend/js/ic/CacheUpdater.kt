/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.backend.common.serialization.DeserializationStrategy
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.konan.kotlinLibrary
import org.jetbrains.kotlin.ir.backend.js.*
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.konan.properties.propertyList
import org.jetbrains.kotlin.library.KLIB_PROPERTY_DEPENDS
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addToStdlib.getOrPut
import java.io.File


fun interface CacheExecutor2 {
    fun execute(
        currentModule: IrModuleFragment,
        dependencies: Collection<IrModuleFragment>,
        deserializer: JsIrLinker,
        configuration: CompilerConfiguration,
        dirtyFiles: Collection<String>?, // if null consider the whole module dirty
        artifactCache: ArtifactCache,
        exportedDeclarations: Set<FqName>,
        mainArguments: List<String>?,
    )
}

sealed class CacheUpdateStatus2 {
    object FastPath : CacheUpdateStatus2()
    class NoDirtyFiles(val removed: Set<String>) : CacheUpdateStatus2()
    class Dirty(val removed: Set<String>, val updated: Set<String>, val updatedAll: Boolean) : CacheUpdateStatus2()
}

class CacheUpdater2(
    private val mainModule: String,
    private val allModules: Collection<String>,
    private val compilerConfiguration: CompilerConfiguration,
    private val icCachePaths: Collection<String>,
    private val irFactory: () -> IrFactory,
    private val mainArguments: List<String>?,
    private val executor: CacheExecutor2
) {
    private fun KotlinLibrary.moduleCanonicalName() = libraryFile.canonicalPath

    private inner class KLibCacheUpdater(
        private val library: KotlinLibrary,
        private val dependencyGraph: Map<KotlinLibrary, List<KotlinLibrary>>,
        private val incrementalCache: IncrementalCache,
        private val klibIncrementalCaches: Map<KotlinLibrary, IncrementalCache>
    ) {
        private fun invalidateCacheForModule(externalHashes: Map<IdSignature, ICHash>): Pair<Set<String>, Map<String, ICHash>> {
            val fileFingerPrints = mutableMapOf<String, ICHash>()
            val dirtyFiles = mutableSetOf<String>()

            if (incrementalCache.klibUpdated) {
                for ((index, file) in incrementalCache.srcFilesInOrderFromKLib.withIndex()) {
                    // 1. get cached fingerprints
                    val fileOldFingerprint = incrementalCache.srcFingerprints[file] ?: 0

                    // 2. calculate new fingerprints
                    val fileNewFingerprint = library.fingerprint(index)

                    if (fileOldFingerprint != fileNewFingerprint) {
                        fileFingerPrints[file] = fileNewFingerprint
                        incrementalCache.invalidateForSrcFile(file)

                        // 3. form initial dirty set
                        dirtyFiles.add(file)
                    }
                }
            }

            // 4. extend dirty set with inline functions
            do {
                if (dirtyFiles.size == incrementalCache.srcFilesInOrderFromKLib.size) break

                val oldSize = dirtyFiles.size
                for (file in incrementalCache.srcFilesInOrderFromKLib) {

                    if (file in dirtyFiles) continue

                    // check for clean file
                    val usedInlineFunctions = incrementalCache.usedFunctions[file] ?: emptyMap()

                    for ((sig, oldHash) in usedInlineFunctions) {
                        val actualHash = externalHashes[sig] ?: incrementalCache.implementedFunctions.firstNotNullOfOrNull { it[sig] }
                        // null means inline function is from dirty file, could be a bit more optimal
                        if (actualHash == null || oldHash != actualHash) {
                            fileFingerPrints[file] = incrementalCache.srcFingerprints[file] ?: error("Cannot find fingerprint for $file")
                            incrementalCache.invalidateForSrcFile(file)
                            dirtyFiles.add(file)
                            break
                        }
                    }
                }
            } while (oldSize != dirtyFiles.size)

            // 5. invalidate file caches
            for (deleted in incrementalCache.deletedSrcFiles) {
                incrementalCache.invalidateForSrcFile(deleted)
            }

            return dirtyFiles to fileFingerPrints
        }

        private fun getDependencySubGraph(): Map<KotlinLibrary, List<KotlinLibrary>> {
            val subGraph = mutableMapOf<KotlinLibrary, List<KotlinLibrary>>()

            fun addDependsFor(library: KotlinLibrary) {
                if (library in subGraph) {
                    return
                }
                val dependencies = dependencyGraph[library] ?: error("Cannot find dependencies for ${library.libraryName}")
                subGraph[library] = dependencies
                for (dependency in dependencies) {
                    addDependsFor(dependency)
                }
            }
            addDependsFor(library)
            return subGraph
        }

        private fun buildCacheForModule(
            irModule: IrModuleFragment,
            deserializer: JsIrLinker,
            dependencies: Collection<IrModuleFragment>,
            dirtyFiles: Collection<String>,
            cleanInlineHashes: Map<IdSignature, ICHash>,
            fileFingerPrints: Map<String, ICHash>
        ) {
            val dirtyIrFiles = irModule.files.filter { it.fileEntry.name in dirtyFiles }

            val flatHashes = InlineFunctionFlatHashBuilder().apply {
                dirtyIrFiles.forEach { it.acceptVoid(this) }
            }.getFlatHashes()

            val hashProvider = object : InlineFunctionHashProvider {
                override fun hashForExternalFunction(declaration: IrFunction): ICHash? {
                    return declaration.symbol.signature?.let { cleanInlineHashes[it] }
                }
            }

            val hashBuilder = InlineFunctionHashBuilder(hashProvider, flatHashes)

            val hashes = hashBuilder.buildHashes(dirtyIrFiles)

            val splitPerFiles = hashes.entries.filter { !it.key.isFakeOverride && (it.key.symbol.signature?.visibleCrossFile ?: false) }
                .groupBy({ it.key.file }) {
                    val signature = it.key.symbol.signature ?: error("Unexpected private inline fun ${it.key.render()}")
                    signature to it.value
                }

            val inlineGraph = hashBuilder.buildInlineGraph(hashes)

            dirtyIrFiles.forEach { irFile ->
                val fileName = irFile.fileEntry.name
                incrementalCache.updateHashes(
                    srcPath = fileName,
                    fingerprint = fileFingerPrints[fileName] ?: error("No fingerprint found for file $fileName"),
                    usedFunctions = inlineGraph[irFile],
                    implementedFunctions = splitPerFiles[irFile]?.toMap()
                )
            }

            // TODO: actual way of building a cache could change in future
            executor.execute(
                irModule, dependencies, deserializer, compilerConfiguration, dirtyFiles, incrementalCache, emptySet(), mainArguments
            )
        }

        fun checkLibrariesHash(): Boolean {
            val flatHash = File(library.moduleCanonicalName()).fileHashForIC()
            val dependencies = dependencyGraph[library] ?: error("Cannot find dependencies for ${library.libraryName}")

            var transHash = flatHash
            for (dep in dependencies) {
                val depCache = klibIncrementalCaches[dep] ?: error("Cannot cache info for ${dep.libraryName}")
                transHash = transHash.combineWith(depCache.klibTransitiveHash)
            }
            return incrementalCache.checkAndUpdateCacheFastInfo(flatHash, transHash)
        }

        fun actualizeCacheForModule(): CacheUpdateStatus2 {
            // 1. Invalidate
            val dependencies = dependencyGraph[library]!!

            val incrementalCache = klibIncrementalCaches[library] ?: error("No cache provider for $library")

            val sigHashes = mutableMapOf<IdSignature, ICHash>()
            dependencies.forEach { lib ->
                klibIncrementalCaches[lib]?.let { libCache ->
                    libCache.fetchCacheDataForDependency()
                    libCache.implementedFunctions.forEach { sigHashes.putAll(it) }
                }
            }

            incrementalCache.fetchFullCacheData()
            val (dirtySet, fileFingerPrints) = invalidateCacheForModule(sigHashes)
            val removed = incrementalCache.deletedSrcFiles

            if (dirtySet.isEmpty()) {
                // up-to-date
                incrementalCache.commitCacheForRemovedSrcFiles()
                return CacheUpdateStatus2.NoDirtyFiles(removed)
            }

            // 2. Build
            val jsIrLinkerProcessor = JsIrLinkerLoader(compilerConfiguration, library, getDependencySubGraph(), irFactory())
            val (jsIrLinker, currentIrModule, irModules) = jsIrLinkerProcessor.processJsIrLinker(dirtySet)

            val currentModuleDeserializer = jsIrLinker.moduleDeserializer(currentIrModule.descriptor)

            incrementalCache.implementedFunctions.forEach { sigHashes.putAll(it) }

            for (dirtySrcFile in dirtySet) {
                val signatureMapping = currentModuleDeserializer.signatureDeserializerForFile(dirtySrcFile).signatureToIndexMapping()
                incrementalCache.updateSignatureToIdMapping(dirtySrcFile, signatureMapping)
            }

            buildCacheForModule(currentIrModule, jsIrLinker, irModules, dirtySet, sigHashes, fileFingerPrints)

            val updatedAll = dirtySet.size == incrementalCache.srcFilesInOrderFromKLib.size
            incrementalCache.commitCacheForRebuiltSrcFiles(currentIrModule.name.asString())
            // invalidated and re-built
            return CacheUpdateStatus2.Dirty(removed, dirtySet, updatedAll)
        }
    }

    private fun loadLibraries(): Map<KotlinLibraryFile, KotlinLibrary> {
        val allResolvedDependencies = jsResolveLibraries(
            allModules,
            compilerConfiguration[JSConfigurationKeys.REPOSITORIES] ?: emptyList(),
            compilerConfiguration[IrMessageLogger.IR_MESSAGE_LOGGER].toResolverLogger()
        )

        return allResolvedDependencies.getFullList().associateBy { KotlinLibraryFile(it) }
    }

    private fun buildDependenciesGraph(libraries: Map<KotlinLibraryFile, KotlinLibrary>): Map<KotlinLibrary, List<KotlinLibrary>> {
        val nameToKotlinLibrary: Map<String, KotlinLibrary> = libraries.values.associateBy { it.moduleName }
        return libraries.values.associateWith {
            it.manifestProperties.propertyList(KLIB_PROPERTY_DEPENDS, escapeInQuotes = true).map { depName ->
                nameToKotlinLibrary[depName] ?: error("No Library found for $depName")
            }
        }
    }

//    fun actualizeCaches(callback: (CacheUpdateStatus2, String) -> Unit): List<ModuleArtifact> {
//        val libraries = loadLibraries()
//        val dependencyGraph = buildDependenciesGraph(libraries)
//        val configHash = compilerConfiguration.configHashForIC()
//
//        val cacheMap = libraries.values.zip(icCachePaths).toMap()
//
//        val klibIncrementalCaches = mutableMapOf<KotlinLibrary, IncrementalCache>()
//
//        val rootModuleCanonical = File(mainModule).canonicalPath
//        val mainLibrary = libraries[rootModuleCanonical] ?: error("Main library not found in libraries: $rootModuleCanonical")
//
//        val visitedLibraries = mutableSetOf<KotlinLibrary>()
//        fun visitDependency(library: KotlinLibrary) {
//            if (library in visitedLibraries) return
//            visitedLibraries.add(library)
//
//            val libraryDeps = dependencyGraph[library] ?: error("Unknown library ${library.libraryName}")
//            libraryDeps.forEach { visitDependency(it) }
//
//            val cachePath = cacheMap[library] ?: error("Unknown cache for library ${library.libraryName}")
//            val incrementalCache = IncrementalCache(library, cachePath)
//            klibIncrementalCaches[library] = incrementalCache
//
//            incrementalCache.invalidateCacheForNewConfig(configHash)
//            val cacheUpdater = KLibCacheUpdater(library, dependencyGraph, incrementalCache, klibIncrementalCaches)
//            val updateStatus = when {
//                cacheUpdater.checkLibrariesHash() -> CacheUpdateStatus2.FastPath
//                else -> cacheUpdater.actualizeCacheForModule()
//            }
//            callback(updateStatus, library.libraryFile.path)
//        }
//
//        visitDependency(mainLibrary)
//        return klibIncrementalCaches.map { it.value.fetchArtifacts() }
//    }

    private val libraries = loadLibraries()
    private val dependencyGraph = buildDependenciesGraph(libraries)
    private val configHash = compilerConfiguration.configHashForIC()

    private val cacheMap = libraries.values.zip(icCachePaths).toMap()

    private val mainLibraryFile = KotlinLibraryFile(File(mainModule).canonicalPath)
    private val mainLibrary = libraries[mainLibraryFile] ?: error("Main library not found in libraries: ${mainLibraryFile.path}")

    private val incrementalCaches = libraries.entries.associate { (libFile, lib) ->
        val cachePath = cacheMap[lib] ?: error("Cannot find cache path for library ${lib.libraryName}")
        libFile to IncrementalCache(lib, cachePath).apply { invalidateCacheForNewConfig(configHash) }
    }

    private fun buildLoadingSymbols(
        loadingDirtyFiles: Map<KotlinLibraryFile, Map<KotlinSourceFile, KotlinSourceFileDependencies>>
    ): Map<KotlinLibraryFile, Map<KotlinSourceFile, Set<IdSignature>>> {
        fun isSourceFileModified(libFile: KotlinLibraryFile, sourceFile: KotlinSourceFile): Boolean {
            return loadingDirtyFiles[libFile]?.containsKey(sourceFile) == true
        }

        val loadingSignatures = mutableMapOf<KotlinLibraryFile, MutableMap<KotlinSourceFile, MutableSet<IdSignature>>>()

        for ((lib, files) in loadingDirtyFiles) {
            if (lib == mainLibraryFile) {
                continue
            }
            val loadingLibSignatures: MutableMap<KotlinSourceFile, MutableSet<IdSignature>> by lazy {
                loadingSignatures.getOrPut(lib) { mutableMapOf() }
            }
            for ((srcFile, dependencies) in files) {
                val loadingFileSignatures: MutableSet<IdSignature> by lazy {
                    loadingLibSignatures.getOrPut(srcFile) { mutableSetOf() }
                }
                for ((dependentLib, dependentFiles) in dependencies.reverseDependencies) {
                    val dependentCache = incrementalCaches[dependentLib] ?: error("TODO message")
                    for (dependentFile in dependentFiles) {
                        if (!isSourceFileModified(dependentLib, dependentFile)) {
                            val signatures = dependentCache.loadSourceFileSignatures(dependentFile)
                            loadingFileSignatures += signatures.importedSymbols
                        }
                    }
                }
            }
        }
        return loadingSignatures
    }

    fun loadCachedModules(): List<String> {
        val modifiedFiles = incrementalCaches.entries.associate { (lib, cache) -> lib to cache.collectModifiedFiles() }

        val loadingSignatures = buildLoadingSymbols(modifiedFiles)
        // get reverse depends for modified files


        var s1 = System.currentTimeMillis()

        val jsIrLinkerLoader = JsIrLinkerLoader(compilerConfiguration, mainLibrary, dependencyGraph, irFactory())
        val (jsIrLinker, irModules) = jsIrLinkerLoader.loadIr(loadingSignatures)

        var s2 = System.currentTimeMillis()

        //  KotlinFileHeader.rebuildDependencies(jsIrLinker, irModules, modifiedFiles)

        var s3 = System.currentTimeMillis()

        return listOf(
            "Ir loading: ${s2 - s1}ms", "Graph building: ${s3 - s2}ms"
        )
    }
}


// Used for tests only
fun rebuildCacheForDirtyFiles2(
    library: KotlinLibrary,
    configuration: CompilerConfiguration,
    dependencyGraph: Map<KotlinLibrary, List<KotlinLibrary>>,
    dirtyFiles: Collection<String>?,
    artifactCache: ArtifactCache,
    irFactory: IrFactory,
    exportedDeclarations: Set<FqName>,
    mainArguments: List<String>?,
): String {
    val jsIrLinkerProcessor = JsIrLinkerLoader(configuration, library, dependencyGraph, irFactory)
    val (jsIrLinker, currentIrModule, irModules) = jsIrLinkerProcessor.processJsIrLinker(dirtyFiles)

    buildCacheForModuleFiles(
        currentIrModule, irModules, jsIrLinker, configuration, dirtyFiles, artifactCache, exportedDeclarations, mainArguments
    )
    return currentIrModule.name.asString()
}

@Suppress("UNUSED_PARAMETER")
fun buildCacheForModuleFiles2(
    currentModule: IrModuleFragment,
    dependencies: Collection<IrModuleFragment>,
    deserializer: JsIrLinker,
    configuration: CompilerConfiguration,
    dirtyFiles: Collection<String>?,
    artifactCache: ArtifactCache,
    exportedDeclarations: Set<FqName>,
    mainArguments: List<String>?,
) {
    compileWithIC(
        currentModule,
        configuration = configuration,
        deserializer = deserializer,
        dependencies = dependencies,
        mainArguments = mainArguments,
        exportedDeclarations = exportedDeclarations,
        filesToLower = dirtyFiles?.toSet(),
        artifactCache = artifactCache,
    )
}
