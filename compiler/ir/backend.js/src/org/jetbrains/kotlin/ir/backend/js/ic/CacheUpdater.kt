/* * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.config.CompilerConfiguration
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
import java.io.File


fun interface CacheExecutor2 {
    fun execute(
        currentModule: IrModuleFragment,
        allModules: Collection<IrModuleFragment>,
        deserializer: JsIrLinker,
        configuration: CompilerConfiguration,
        dirtyFiles: Collection<IrFile>,
        exportedDeclarations: Set<FqName>,
        mainArguments: List<String>?
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
    private class DirtyFileMetadata(
        val importedSignatures: Collection<IdSignature>,

        val oldDirectDependencies: KotlinSourceFileMap<*>,

        override val inverseDependencies: KotlinSourceFileMutableMap<MutableSet<IdSignature>> = KotlinSourceFileMutableMap(),
        override val directDependencies: KotlinSourceFileMutableMap<MutableSet<IdSignature>> = KotlinSourceFileMutableMap(),

        override val importedInlineFunctions: MutableMap<IdSignature, ICHash> = mutableMapOf()
    ) : KotlinSourceFileMetadata {
        fun addInverseDependency(lib: KotlinLibraryFile, src: KotlinSourceFile, signature: IdSignature) =
            inverseDependencies.addSignature(lib, src, signature)

        fun addDirectDependency(lib: KotlinLibraryFile, src: KotlinSourceFile, signature: IdSignature) =
            directDependencies.addSignature(lib, src, signature)
    }

    private class UpdatedDependenciesMetadata(oldMetadata: KotlinSourceFileMetadata) : KotlinSourceFileMetadata {
        private val oldInverseDependencies = oldMetadata.inverseDependencies

        var importedInlineFunctionsModified = false

        override val inverseDependencies = oldMetadata.inverseDependencies.toMutable()
        override val directDependencies = oldMetadata.directDependencies
        override val importedInlineFunctions = oldMetadata.importedInlineFunctions

        fun oldExportedSignatures() = oldInverseDependencies.flatSignatures()
        fun newExportedSignatures() = inverseDependencies.flatSignatures()
    }

    private val transitiveHashCalculator = InlineFunctionTransitiveHashCalculator()

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
        jsIrLinker: JsIrLinker,
        loadedFragments: Map<KotlinLibraryFile, IrModuleFragment>,
        dirtySrcFiles: KotlinSourceFileMap<*>,
        inlineFunctionHashes: Map<IdSignature, ICHash>
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
                    inlineFunctionHashes[importedSignature]?.let { internalHeader.importedInlineFunctions[importedSignature] = it }

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

    private fun collectFilesWithModifiedExportsOrInlineImports(
        loadedDirtyFiles: KotlinSourceFileMap<DirtyFileMetadata>, inlineFunctionHashes: Map<IdSignature, ICHash>
    ): KotlinSourceFileMap<UpdatedDependenciesMetadata> {
        val filesWithModifiedExports = KotlinSourceFileMutableMap<UpdatedDependenciesMetadata>()

        fun KotlinSourceFileMetadata.makeNewMetadata(libFile: KotlinLibraryFile, srcFile: KotlinSourceFile) =
            filesWithModifiedExports[libFile, srcFile] ?: UpdatedDependenciesMetadata(this).also {
                filesWithModifiedExports[libFile, srcFile] = it
            }

        loadedDirtyFiles.forEachFile { libFile, srcFile, srcFileMetadata ->
            // go through updated dependencies and collect modified
            for ((dependencyLibFile, dependencySrcFiles) in srcFileMetadata.directDependencies) {
                val dependencyCache = incrementalCaches[dependencyLibFile] ?: error("TODO message1")
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

            // go through dependent files and check if their inline imports were modified
            for ((dependentLibFile, dependentSrcFiles) in srcFileMetadata.inverseDependencies) {
                val dependentCache = incrementalCaches[dependentLibFile] ?: error("TODO message1")
                for (dependentSrcFile in dependentSrcFiles.keys) {
                    val dependentSrcMetadata = dependentCache.fetchSourceFileFullMetadata(dependentSrcFile)
                    val importedInlineModified = dependentSrcMetadata.importedInlineFunctions.any {
                        inlineFunctionHashes[it.key]?.let { newHash -> newHash != it.value } ?: false
                    }
                    if (importedInlineModified) {
                        dependentSrcMetadata.makeNewMetadata(dependentLibFile, dependentSrcFile).importedInlineFunctionsModified = true
                    }
                }
            }
        }

        return filesWithModifiedExports
    }

    private fun collectFilesToRebuildSignatures(
        filesWithModifiedExports: KotlinSourceFileMap<UpdatedDependenciesMetadata>
    ): KotlinSourceFileMap<Set<IdSignature>> {
        val filesToRebuild = KotlinSourceFileMutableMap<Set<IdSignature>>()

        filesWithModifiedExports.forEachFile { libFile, srcFile, srcFileMetadata ->
            val newExportedSignatures = srcFileMetadata.newExportedSignatures()
            if (srcFileMetadata.importedInlineFunctionsModified || newExportedSignatures != srcFileMetadata.oldExportedSignatures()) {
                // if exported signatures or imported inline functions were modified - rebuild
                filesToRebuild[libFile, srcFile] = newExportedSignatures
            } else {
                // if exported signatures are the same - just update cache metadata
                val cache = incrementalCaches[libFile] ?: error("TODO message")
                cache.updateSourceFileMetadata(srcFile, srcFileMetadata)
            }
        }

        return filesToRebuild
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
        doMeasure("load IR 0")

        var cnt = 0


        var lastDirtyFiles: KotlinSourceFileMap<Collection<IdSignature>> = dirtyFileExports
        var hasUpdatedExports = false

        while (true) {
            val inlineFunctionHashes = transitiveHashCalculator.updateTransitiveHashes(loadedIr.loadedFragments.values)
            doMeasure("collectInlineFunctionTransitiveHashes")

            val dirtyHeaders = rebuildDirtySourceMetadata(loadedIr.linker, loadedIr.loadedFragments, lastDirtyFiles, inlineFunctionHashes)
            doMeasure("rebuildDirtySourceMetadata $cnt")

            val filesWithModifiedExportsOrImports = collectFilesWithModifiedExportsOrInlineImports(dirtyHeaders, inlineFunctionHashes)
            doMeasure("collectFilesWithModifiedExportsOrInlineImports $cnt")

            val filesToRebuild = collectFilesToRebuildSignatures(filesWithModifiedExportsOrImports)
            doMeasure("collectFilesToRebuildSignatures $cnt")

            if (filesToRebuild.isEmpty()) {
                break
            }

            hasUpdatedExports = true
            loadedIr = jsIrLinkerLoader.loadIr(filesToRebuild)
            cnt++

            doMeasure("load IR $cnt")

            lastDirtyFiles = filesToRebuild
            dirtyFileExports.copyFilesFrom(filesToRebuild)
            doMeasure("consumeFiles $cnt")
        }

        if (hasUpdatedExports) {
            loadedIr = jsIrLinkerLoader.loadIr(dirtyFileExports)
            doMeasure("load IR FINAL")
        }
        commitCacheMetadata(loadedIr.linker, loadedIr.loadedFragments)
        doMeasure("commitCacheMetadata")

        val mainModule = loadedIr.loadedFragments[mainLibraryFile] ?: error("TODO error")
        val allModules = loadedIr.loadedFragments.values

        val dirtyFiles = loadedIr.loadedFragments.flatMap {
            val libDirtyFiles = dirtyFileExports.libFiles(it.key)
            if (libDirtyFiles.isEmpty()) {
                emptyList()
            } else {
                it.value.files.filter { file -> KotlinSourceFile(file) in libDirtyFiles }
            }
        }

        executor.execute(mainModule, allModules, loadedIr.linker, compilerConfiguration, dirtyFiles, emptySet(), mainArguments)
        doMeasure("lowering things")
//
        return profile
    }
}


// Used for tests only
//fun rebuildCacheForDirtyFiles2(
//    library: KotlinLibrary,
//    configuration: CompilerConfiguration,
//    dependencyGraph: Map<KotlinLibrary, List<KotlinLibrary>>,
//    dirtyFiles: Collection<String>?,
//    artifactCache: ArtifactCache,
//    irFactory: IrFactory,
//    exportedDeclarations: Set<FqName>,
//    mainArguments: List<String>?,
//): String {
//    val jsIrLinkerProcessor = JsIrLinkerLoader(configuration, library, dependencyGraph, irFactory)
//    val (jsIrLinker, currentIrModule, irModules) = jsIrLinkerProcessor.processJsIrLinker(dirtyFiles)
//
//    buildCacheForModuleFiles(
//        currentIrModule, irModules, jsIrLinker, configuration, dirtyFiles, artifactCache, exportedDeclarations, mainArguments
//    )
//    return currentIrModule.name.asString()
//}

fun buildCacheForModuleFiles2(
    currentModule: IrModuleFragment,
    allModules: Collection<IrModuleFragment>,
    deserializer: JsIrLinker,
    configuration: CompilerConfiguration,
    dirtyFiles: Collection<IrFile>,
    exportedDeclarations: Set<FqName>,
    mainArguments: List<String>?
) {
    compileWithIC(
        currentModule,
        allModules = allModules,
        filesToLower = dirtyFiles,
        configuration = configuration,
        deserializer = deserializer,
        mainArguments = mainArguments,
        exportedDeclarations = exportedDeclarations,
    )
}
