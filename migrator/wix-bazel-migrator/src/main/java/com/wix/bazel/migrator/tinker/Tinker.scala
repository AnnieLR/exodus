package com.wix.bazel.migrator.tinker

import com.wix.bazel.migrator._
import com.wix.bazel.migrator.external.registry.{CachingEagerExternalSourceModuleRegistry, CodotaExternalSourceModuleRegistry, CompositeExternalSourceModuleRegistry, ConstantExternalSourceModuleRegistry}
import com.wix.bazel.migrator.overrides.{AdditionalDepsByMavenDepsOverrides, AdditionalDepsByMavenDepsOverridesReader, InternalTargetOverridesReader, MavenArchiveTargetsOverridesReader}
import com.wix.bazel.migrator.transform._
import com.wix.bazel.migrator.workspace.WorkspaceWriter
import com.wix.bazel.migrator.workspace.resolution.GitIgnoreAppender
import com.wix.build.maven.analysis.ThirdPartyConflicts
import com.wixpress.build.bazel.NeverLinkResolver._

class Tinker(configuration: RunConfiguration) extends AppTinker(configuration) {
  def migrate(): Unit = {
    failOnConflictsIfNeeded()

    writeBazelRc()
    writeBazelRcManagedDevEnv()
    writePrelude()
    writeBazelRemoteRc()
    writeBazelRemoteSettingsRc()
    writeWorkspace()
    writeInternal()
    writeExternal()
    writeDockerImages()
    appendAutoGeneratedFilesToGitIgnore()
    writeDefaultJavaToolchain()
    writeRemoteJdkCustomFlags()

    syncLocalThirdPartyDeps()

    cleanGitIgnore()
  }

  lazy val externalSourceModuleRegistry = CachingEagerExternalSourceModuleRegistry.build(
    externalSourceDependencies = externalSourceDependencies.map(_.coordinates),
    registry = new CompositeExternalSourceModuleRegistry(
      new ConstantExternalSourceModuleRegistry(),
      new CodotaExternalSourceModuleRegistry(configuration.codotaToken)))

  lazy val mavenArchiveTargetsOverrides = MavenArchiveTargetsOverridesReader.from(repoRoot)

  private def failOnConflictsIfNeeded(): Unit = if (configuration.failOnSevereConflicts)
    failIfFoundSevereConflictsIn(checkConflictsInThirdPartyDependencies(aetherResolver))

  private def writeBazelRc(): Unit =
    new BazelRcWriter(repoRoot).write()

  private def writeBazelRcManagedDevEnv(): Unit =
    new BazelRcManagedDevEnvWriter(repoRoot).resetFileWithDefaultOptions()

  private def writePrelude(): Unit =
    new PreludeWriter(repoRoot).write()

  private def writeBazelRemoteRc(): Unit =
    new BazelRcRemoteWriter(repoRoot).write()

  private def writeBazelRemoteSettingsRc(): Unit =
    new BazelRcRemoteSettingsWriter(repoRoot).write()

  private def writeWorkspace(): Unit =
    new WorkspaceWriter(repoRoot, localWorkspaceName, configuration.interRepoSourceDependency).write()

  private def writeInternal(): Unit =
    new Writer(repoRoot, codeModules, bazelPackages).write()

  private def writeExternal(): Unit =
    new TemplateOfThirdPartyDepsSkylarkFileWriter(repoRoot).write()

  private def writeDockerImages(): Unit =
    new DockerImagesWriter(repoRoot, InternalTargetOverridesReader.from(repoRoot)).write()

  private def appendAutoGeneratedFilesToGitIgnore(): Unit = {
    val gitIgnoreAppender = new GitIgnoreAppender(repoRoot)
    gitIgnoreAppender.append("tools/2nd_party_resolved_dependencies_current_branch.bzl")
    gitIgnoreAppender.append("tools/master_2nd_party_resolved_dependencies.bzl")
    gitIgnoreAppender.append("tools/third_party_deps_of_external_wix_repositories.bzl")
    gitIgnoreAppender.append("tools/*_2nd_party_resolved_dependencies.json")
    gitIgnoreAppender.append("tools/2nd_party_resolved_dependencies/*")
  }

  private def writeDefaultJavaToolchain(): Unit =
    new DefaultJavaToolchainWriter(repoRoot).write()

  private def writeRemoteJdkCustomFlags(): Unit = {
    val jdk8CustomFlags = List(
      "build --host_javabase=@core_server_build_tools//toolchains/jdk:wix_remote_jdk",
      "build --javabase=@core_server_build_tools//toolchains/jdk:wix_remote_jdk"
    )
    new BazelRcManagedDevEnvWriter(repoRoot).appendLines(jdk8CustomFlags)
  }

  private def cleanGitIgnore(): Unit =
    new GitIgnoreCleaner(repoRoot).clean()

  private def bazelPackages =
    if (configuration.performTransformation) transform() else Persister.readTransformationResults()

  private def transform() = {
    val transformer = new CodeAnalysisTransformer(dependencyAnalyzer)
    val packagesTransformers = Seq(
      externalProtoTransformer(),
      moduleDepsTransformer(),
      providedModuleTestDependenciesTransformer(),
      additionalDepsByMavenDepsTransformer()
    )

    val packagesFromCodeAnalysis = transformer.transform(codeModules)
    val transformedPackages = packagesTransformers.foldLeft(packagesFromCodeAnalysis){
      (packages, packageTransformer) => packageTransformer.transform(packages)
    }
    Persister.persistTransformationResults(transformedPackages)
    transformedPackages
  }

  private def externalProtoTransformer() = new ExternalProtoTransformer(codeModules)

  private def moduleDepsTransformer() = {
    new ModuleDependenciesTransformer(codeModules, externalSourceModuleRegistry, mavenArchiveTargetsOverrides, globalNeverLinkDependencies)
  }

  private def providedModuleTestDependenciesTransformer() =
    new ProvidedModuleTestDependenciesTransformer(codeModules, externalSourceModuleRegistry, mavenArchiveTargetsOverrides)

  private def additionalDepsByMavenDepsTransformer() = {
    val overrides = configuration.additionalDepsByMavenDeps match {
      case Some(path) => AdditionalDepsByMavenDepsOverridesReader.from(path)
      case None => AdditionalDepsByMavenDepsOverrides.empty
    }
    new AdditionalDepsByMavenOverridesTransformer(overrides,configuration.interRepoSourceDependency, configuration.includeServerInfraInSocialModeSet)
  }

  private def dependencyAnalyzer = {
    val exceptionFormattingDependencyAnalyzer = new ExceptionFormattingDependencyAnalyzer(codotaDependencyAnalyzer)
    val cachingCodotaDependencyAnalyzer = new CachingEagerEvaluatingCodotaDependencyAnalyzer(codeModules, exceptionFormattingDependencyAnalyzer)
    if (wixFrameworkMigration)
      new CompositeDependencyAnalyzer(
        cachingCodotaDependencyAnalyzer,
        new ManualInfoDependencyAnalyzer(sourceModules),
        new InternalFileDepsOverridesDependencyAnalyzer(sourceModules, repoRoot))
    else
      new CompositeDependencyAnalyzer(
        cachingCodotaDependencyAnalyzer,
        new InternalFileDepsOverridesDependencyAnalyzer(sourceModules, repoRoot))
  }

  private def wixFrameworkMigration = configuration.repoUrl.contains("/wix-framework.git")

  private def failIfFoundSevereConflictsIn(conflicts: ThirdPartyConflicts): Unit = {
    if (conflicts.fail.nonEmpty) {
      throw new RuntimeException("Found failing third party conflicts (look for \"Found conflicts\" in log)")
    }
  }
}
