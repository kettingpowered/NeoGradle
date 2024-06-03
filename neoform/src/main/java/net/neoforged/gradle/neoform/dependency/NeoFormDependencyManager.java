package net.neoforged.gradle.neoform.dependency;

import com.google.common.collect.Sets;
import net.neoforged.gradle.dsl.common.extensions.dependency.replacement.Context;
import net.neoforged.gradle.dsl.common.runtime.definition.Definition;
import net.neoforged.gradle.dsl.common.runtime.extensions.RuntimesContainer;
import net.neoforged.gradle.dsl.common.runtime.spec.Specification;
import net.neoforged.gradle.dsl.common.runtime.spec.TaskTreeBuilder;
import net.neoforged.gradle.dsl.common.util.ConfigurationUtils;
import net.neoforged.gradle.dsl.common.util.DistributionType;
import net.neoforged.gradle.dsl.neoform.configuration.NeoFormConfigConfigurationSpecV2;
import net.neoforged.gradle.neoform.runtime.NeoFormRuntime;
import net.neoforged.gradle.neoform.runtime.definition.NeoFormRuntimeDefinition;
import net.neoforged.gradle.neoform.util.NeoFormRuntimeUtils;
import net.neoforged.gradle.dsl.common.util.CommonRuntimeUtils;
import net.neoforged.gradle.dsl.common.extensions.dependency.replacement.DependencyReplacement;
import net.neoforged.gradle.dsl.common.extensions.dependency.replacement.ReplacementResult;
import net.neoforged.gradle.neoform.runtime.extensions.NeoFormRuntimeExtension;
import net.neoforged.gradle.util.ModuleDependencyUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * This class installs a dependency replacement handler that replaces the following dependencies with the output
 * of a NeoForm runtime.
 * <p>
 * <ul>
 *     <li>net.minecraft:neoform_client</li>
 *     <li>net.minecraft:neoform_server</li>
 *     <li>net.minecraft:neoform_joined</li>
 * </ul>
 * <p>
 * The NeoForm version that should be used is determined from the version of the dependency.
 */
public final class NeoFormDependencyManager {
    private NeoFormDependencyManager() {
    }

    public static void apply(final Project project) {
        final DependencyReplacement dependencyReplacer = project.getExtensions().getByType(DependencyReplacement.class);

        dependencyReplacer.getReplacementHandlers().create("neoForm", handler -> {
            handler.getReplacer().set(NeoFormDependencyManager::replaceDependency);
        });
    }

    private static Optional<ReplacementResult> replaceDependency(Context context) {
        ModuleDependency dependency = context.getDependency();

        NeoFormTarget target = getNeoFormTargetFromDependency(dependency);
        if (target == null) {
            return Optional.empty();
        }

        if (target.version == null) {
            throw new IllegalStateException("Version is missing on NeoForm dependency " + dependency);
        }

        // Build the runtime used to produce the artifact
        Project project = context.getProject();
        RuntimesContainer container = project.getExtensions().getByType(RuntimesContainer.class);

        final Provider<File> neoFormArchiveFile = NeoFormRuntime.getNeoFormArchive(project, target.version);
        final Provider<NeoFormConfigConfigurationSpecV2> config = NeoFormRuntime.parseConfiguration(neoFormArchiveFile);

        final Definition runtimeDefinition = container.register(
                new Specification(
                        project,
                        "neoForm",
                        project.provider(() -> target.version),
                        NeoFormRuntime.getMinecraftVersion(config),
                        project.provider(() -> target.distribution)
                ),
                new TaskTreeBuilder() {
                    @Override
                    public BuildResult build(Specification specification) {
                        return null;
                    }
                }
        );

        NeoFormRuntimeExtension runtimeExtension = project.getExtensions().getByType(NeoFormRuntimeExtension.class);
        NeoFormRuntimeDefinition runtime = runtimeExtension.maybeCreateFor(dependency, builder -> {
            builder.withDistributionType(target.distribution).withNeoFormVersion(target.version);
            NeoFormRuntimeUtils.configureDefaultRuntimeSpecBuilder(project, builder);
        });

        return Optional.of(
                new ReplacementResult(
                        project,
                        runtime.getSourceJarTask(),
                        runtime.getRawJarTask(),
                        runtime.getMinecraftDependenciesConfiguration(),
                        Collections.emptySet()
                ));
    }

    @Nullable
    private static NeoFormTarget getNeoFormTargetFromDependency(ModuleDependency dependency) {

        if (!"net.minecraft".equals(dependency.getGroup())) {
            return null;
        }

        DistributionType distributionType;
        switch (dependency.getName()) {
            case "neoform_client":
                distributionType = DistributionType.CLIENT;
                break;
            case "neoform_server":
                distributionType = DistributionType.SERVER;
                break;
            case "neoform_joined":
                distributionType = DistributionType.JOINED;
                break;
            default:
                return null; // This dependency is not handled by this replacer
        }

        if (!hasMatchingArtifact(dependency)) {
            return null;
        }

        return new NeoFormTarget(dependency.getVersion(), distributionType);
    }

    private static boolean hasMatchingArtifact(ModuleDependency externalModuleDependency) {
        if (externalModuleDependency.getArtifacts().isEmpty()){
            return true;
        }

        return hasSourcesArtifact(externalModuleDependency);
    }

    private static boolean hasSourcesArtifact(ModuleDependency externalModuleDependency) {
        if (externalModuleDependency.getArtifacts().size() != 1) {
            return false;
        }

        final DependencyArtifact artifact = externalModuleDependency.getArtifacts().iterator().next();
        return Objects.equals(artifact.getClassifier(), "sources") && Objects.equals(artifact.getExtension(), "jar");
    }

    private static final class NeoFormTarget {
        private final String version;
        private final DistributionType distribution;

        public NeoFormTarget(String version, DistributionType distribution) {
            this.version = version;
            this.distribution = distribution;
        }
    }
    
}
