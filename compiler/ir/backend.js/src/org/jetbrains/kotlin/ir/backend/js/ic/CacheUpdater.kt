/* * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
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
    class DirtyFileMetadata(
        val importedSignatures: Collection<IdSignature>,

        val oldDirectDependencies: KotlinSourceFileMap<*>,

        override val inverseDependencies: KotlinSourceFileMutableMap<MutableSet<IdSignature>> = KotlinSourceFileMutableMap(),
        override val directDependencies: KotlinSourceFileMutableMap<MutableSet<IdSignature>> = KotlinSourceFileMutableMap()
    ) : KotlinSourceFileMetadata {
        private fun KotlinSourceFileMutableMap<MutableSet<IdSignature>>.addSignature(
            lib: KotlinLibraryFile, src: KotlinSourceFile, signature: IdSignature
        ) = when (val signatures = this[lib, src]) {
            null -> this[lib, src] = mutableSetOf(signature)
            else -> signatures += signature
        }

        fun addInverseDependency(lib: KotlinLibraryFile, src: KotlinSourceFile, signature: IdSignature) =
            inverseDependencies.addSignature(lib, src, signature)

        fun addDirectDependency(lib: KotlinLibraryFile, src: KotlinSourceFile, signature: IdSignature) =
            directDependencies.addSignature(lib, src, signature)
    }

    class UpdatedInverseDependenciesMetadata(
        val oldInverseDependencies: KotlinSourceFileMap<Set<IdSignature>>,

        override val inverseDependencies: KotlinSourceFileMutableMap<Set<IdSignature>>,
        override val directDependencies: KotlinSourceFileMap<Set<IdSignature>>
    ) : KotlinSourceFileMetadata {
        fun KotlinSourceFileMap<Set<IdSignature>>.flatSignatures(): Set<IdSignature> {
            val allSignatures = mutableSetOf<IdSignature>()
            forEachFile { _, _, signatures -> allSignatures += signatures }
            return allSignatures
        }

        fun oldExportedSignatures() = oldInverseDependencies.flatSignatures()
        fun newExportedSignatures() = inverseDependencies.flatSignatures()
    }

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

    private fun collectExportedSymbolsForDirtyFiles(dirtyFiles: KotlinSourceFileMap<KotlinSourceFileMetadata>): KotlinSourceFileMutableMap<Collection<IdSignature>> {
        val exportedSymbols = KotlinSourceFileMutableMap<Collection<IdSignature>>()
        dirtyFiles.forEachFile { libFile, srcFile, srcFileMetadata ->
            val loadingFileSignatures = mutableSetOf<IdSignature>()
            for ((dependentLib, dependentFiles) in srcFileMetadata.inverseDependencies) {
                // TODO: inverse dependency lib could be removed?
                val dependentCache = incrementalCaches[dependentLib] ?: continue
                for (dependentFile in dependentFiles.keys) {
                    if (dependentFile !in dirtyFiles.libFiles(dependentLib)) {
                        val dependentSrcFileMetadata = dependentCache.fetchSourceFileFullMetadata(dependentFile)
                        dependentSrcFileMetadata.directDependencies[libFile, srcFile]?.let { loadingFileSignatures += it }
                    }
                }
            }
            exportedSymbols[libFile, srcFile] = loadingFileSignatures
        }
        return exportedSymbols
    }

    private fun rebuildDirtySourceMetadata(
        jsIrLinker: JsIrLinker, loadedFragments: Map<KotlinLibraryFile, IrModuleFragment>, dirtySrcFiles: KotlinSourceFileMap<*>
    ): KotlinSourceFileMap<DirtyFileMetadata> {
        val idSignatureToFile = mutableMapOf<IdSignature, Pair<KotlinLibraryFile, KotlinSourceFile>>()
        val updatedMetadata = KotlinSourceFileMutableMap<DirtyFileMetadata>()

        for ((lib, irModule) in loadedFragments) {
            val moduleDeserializer = jsIrLinker.moduleDeserializer(irModule.descriptor)
            val incrementalCache = incrementalCaches[lib] ?: error("TODO ERROR")
            for (fileDeserializer in moduleDeserializer.fileDeserializers()) {
                val libSrcFile = KotlinSourceFile(fileDeserializer.file)
                val allTopLevelSignatures = fileDeserializer.reversedSignatureIndex
                idSignatureToFile.putAll(allTopLevelSignatures.keys.asSequence().map { it to (lib to libSrcFile) })

                val reachableSignatures = fileDeserializer.symbolDeserializer.signatureDeserializer.signatureToIndexMapping()
                val importedSignatures = reachableSignatures.keys - allTopLevelSignatures.keys
                val metadata = incrementalCache.fetchSourceFileFullMetadata(libSrcFile)
                updatedMetadata[lib, libSrcFile] = DirtyFileMetadata(importedSignatures, metadata.directDependencies)
            }
        }

        updatedMetadata.forEachFile { libFile, srcFile, internalHeader ->
            if (srcFile in dirtySrcFiles.libFiles(libFile)) {
                for (importedSignature in internalHeader.importedSignatures) {
                    val (signatureFromLib, signatureFromFile) = idSignatureToFile[importedSignature] ?: continue
                    internalHeader.addDirectDependency(signatureFromLib, signatureFromFile, importedSignature)

                    val dependencyCacheData = updatedMetadata[signatureFromLib, signatureFromFile] ?: error("TODO ERROR")
                    dependencyCacheData.addInverseDependency(libFile, srcFile, importedSignature)
                }
            }
        }

        val result = KotlinSourceFileMutableMap<DirtyFileMetadata>()

        for ((libFile, sourceFiles) in dirtySrcFiles) {
            val incrementalCache = incrementalCaches[libFile] ?: error("TODO ERROR")
            val srcFileUpdatedMetadata = updatedMetadata[libFile] ?: error("TODO ERROR")
            for (sourceFile in sourceFiles.keys) {
                val srcMetadata = srcFileUpdatedMetadata[sourceFile] ?: error("TODO ERROR")
                incrementalCache.updateSourceFileMetadata(sourceFile, srcMetadata)
                result[libFile, sourceFile] = srcMetadata
            }
        }

        return result
    }

    private fun collectUpdatedExportsFromDependencies(
        loadedDirtyFiles: KotlinSourceFileMap<DirtyFileMetadata>
    ): KotlinSourceFileMap<Set<IdSignature>> {
        val filesWithNewExports = KotlinSourceFileMutableMap<UpdatedInverseDependenciesMetadata>()

        fun KotlinSourceFileMetadata.makeNewMetadata(
            libFile: KotlinLibraryFile, srcFile: KotlinSourceFile
        ): UpdatedInverseDependenciesMetadata {
            return filesWithNewExports[libFile, srcFile] ?: UpdatedInverseDependenciesMetadata(
                inverseDependencies, inverseDependencies.toMutable(), directDependencies
            ).also { filesWithNewExports[libFile, srcFile] = it }
        }

        loadedDirtyFiles.forEachFile { libFile, srcFile, srcFileMetadata ->
            // go through updated dependencies and collect modified
            for ((dependencyLibFile, dependencySrcFiles) in srcFileMetadata.directDependencies) {
                val dependencyCache = incrementalCaches[dependencyLibFile] ?: error("TODO message")
                for ((dependencySrcFile, newSignatures) in dependencySrcFiles) {
                    val dependencySrcMetadata = dependencyCache.fetchSourceFileFullMetadata(dependencySrcFile)
                    val oldSignatures = dependencySrcMetadata.inverseDependencies[libFile, srcFile] ?: emptySet()
                    if (oldSignatures == newSignatures) {
                        continue
                    }

                    val newMetadata = dependencySrcMetadata.makeNewMetadata(dependencyLibFile, dependencySrcFile)
                    newMetadata.inverseDependencies[libFile, srcFile] = newSignatures
                }
            }

            // go through old dependencies and look for removed dependencies
            srcFileMetadata.oldDirectDependencies.forEachFile { oldDependencyLibFile, oldDependencySrcFiles, _ ->
                if (srcFileMetadata.directDependencies[oldDependencyLibFile, oldDependencySrcFiles] == null) {
                    val dependencyCache = incrementalCaches[oldDependencyLibFile] ?: error("TODO message")
                    val dependencySrcMetadata = dependencyCache.fetchSourceFileFullMetadata(oldDependencySrcFiles)
                    if (dependencySrcMetadata.inverseDependencies[libFile, srcFile] != null) {
                        val newMetadata = dependencySrcMetadata.makeNewMetadata(oldDependencyLibFile, oldDependencySrcFiles)
                        newMetadata.inverseDependencies.removeFile(libFile, srcFile)
                    }
                }
            }
        }

        val exportedSymbols = KotlinSourceFileMutableMap<Set<IdSignature>>()

        filesWithNewExports.forEachFile { libFile, srcFile, srcFileMetadata ->
            val newExportedSignatures = srcFileMetadata.newExportedSignatures()
            if (newExportedSignatures != srcFileMetadata.oldExportedSignatures()) {
                // if exported signatures are updated - rebuild
                exportedSymbols[libFile, srcFile] = newExportedSignatures
            } else {
                // if exported signatures are the same - just update cache metadata
                val cache = incrementalCaches[libFile] ?: error("TODO message")
                cache.updateSourceFileMetadata(srcFile, srcFileMetadata)
            }
        }

        return exportedSymbols
    }

    private fun commitCacheMetadata(jsIrLinker: JsIrLinker, loadedFragments: Map<KotlinLibraryFile, IrModuleFragment>) {
        for ((lib, irModule) in loadedFragments) {
            val incrementalCache = incrementalCaches[lib] ?: error("TODO ERROR")
            val moduleDeserializer = jsIrLinker.moduleDeserializer(irModule.descriptor)
            for (fileDeserializer in moduleDeserializer.fileDeserializers()) {
                val signatureToIndexMapping = fileDeserializer.symbolDeserializer.signatureDeserializer.signatureToIndexMapping()
                incrementalCache.commitSourceFileMetadata(KotlinSourceFile(fileDeserializer.file), signatureToIndexMapping)
            }
        }
    }

    private fun loadModifiedFiles(): KotlinSourceFileMap<KotlinSourceFileMetadata> {
        return KotlinSourceFileMap(incrementalCaches.entries.associate { (lib, cache) -> lib to cache.collectModifiedFiles() })
    }

    fun loadCachedModules(): List<String> {
        val profile = mutableListOf<String>()
        var tp = System.currentTimeMillis()
        fun doMeasure(msg: String) {
            val nextTp = System.currentTimeMillis()
            profile += ">>>> $msg: ${nextTp - tp} ms"
            tp = nextTp
        }

        val modifiedFiles = loadModifiedFiles()

        doMeasure("loadModifiedFiles")


        val dirtyFileExports = collectExportedSymbolsForDirtyFiles(modifiedFiles)

        doMeasure("collectExportedSymbolsForDirtyFiles")

        val jsIrLinkerLoader = JsIrLinkerLoader(compilerConfiguration, mainLibrary, dependencyGraph, irFactory())
        var loadedIr = jsIrLinkerLoader.loadIr(dirtyFileExports)

        var cnt = 0
        doMeasure("load IR $cnt")


        var lastDirtyFiles: KotlinSourceFileMap<Collection<IdSignature>> = dirtyFileExports
        var hasUpdatedExports = false

        while (true) {
            val newDirtyFileHeaders = rebuildDirtySourceMetadata(loadedIr.linker, loadedIr.loadedFragments, lastDirtyFiles)
            doMeasure("rebuildDirtySourceMetadata $cnt")

            val dirtyExportFiles = collectUpdatedExportsFromDependencies(newDirtyFileHeaders)
            doMeasure("collectUpdatedExportsFromDependencies $cnt")

            if (dirtyExportFiles.isEmpty()) {
                break
            }

            hasUpdatedExports = true
            loadedIr = jsIrLinkerLoader.loadIr(dirtyExportFiles)
            cnt++

            doMeasure("load IR $cnt")

            lastDirtyFiles = dirtyExportFiles
            dirtyFileExports.consumeFiles(dirtyExportFiles)
            doMeasure("consumeFiles $cnt")
        }

        if (hasUpdatedExports) {
            loadedIr = jsIrLinkerLoader.loadIr(dirtyFileExports)
            doMeasure("load IR FINAL")
        }
        commitCacheMetadata(loadedIr.linker, loadedIr.loadedFragments)
        doMeasure("commitCacheMetadata")

        return profile
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
