package net.neoforged.gradle.common.runs.run;

import net.neoforged.gradle.dsl.common.runs.run.RunTestScope;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;

import javax.inject.Inject;
import java.io.File;


public abstract class RunTestScopeImpl implements RunTestScope {

    static final String DEFAULT_PATTERN = ".*";

    private final Project project;

    @Inject
    public RunTestScopeImpl(Project project) {
        this.project = project;

        getPattern().convention(
                project.provider(() -> {
                    if (getPackageName().orElse(getDirectory().map(Directory::getAsFile).map(File::getName))
                            .orElse(getClassName())
                            .orElse(getMethod())
                            .orElse(getCategory())
                            .getOrNull() == null) {
                        return DEFAULT_PATTERN;
                    }

                    return null;
                })
        );
    }

    @Override
    public Project getProject() {
        return project;
    }
}
