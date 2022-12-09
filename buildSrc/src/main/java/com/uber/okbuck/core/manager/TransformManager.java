package com.uber.okbuck.core.manager;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Var;
import com.uber.okbuck.OkBuckGradlePlugin;
import com.uber.okbuck.composer.base.BuckRuleComposer;
import com.uber.okbuck.core.dependency.DependencyCache;
import com.uber.okbuck.core.model.android.AndroidAppTarget;
import com.uber.okbuck.core.model.base.RuleType;
import com.uber.okbuck.core.model.base.Scope;
import com.uber.okbuck.core.util.FileUtil;
import com.uber.okbuck.core.util.ProjectUtil;
import com.uber.okbuck.template.core.Rule;
import com.uber.okbuck.template.java.NativePrebuilt;
import com.uber.okbuck.template.jvm.JvmBinaryRule;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.gradle.api.Project;

public final class TransformManager {

  private static final String TRANSFORM_CACHE = OkBuckGradlePlugin.WORKSPACE_PATH + "/transform";
  public static final String TRANSFORM_RULE = "//" + TRANSFORM_CACHE + ":okbuck_transform";

  public static final String CONFIGURATION_TRANSFORM = "transform";
  private static final String TRANSFORM_FOLDER = "transform/";
  private static final String TRANSFORM_JAR = "transform-cli-1.1.0.jar";
  private static final String TRANSFORM_JAR_RULE = "//" + TRANSFORM_CACHE + ":" + TRANSFORM_JAR;

  private static final String OPT_TRANSFORM_CLASS = "transform";
  private static final String OPT_CONFIG_FILE = "configFile";
  private static final String PREFIX =
      "java -Dokbuck.inJarsDir=$IN_JARS_DIR -Dokbuck.outJarsDir=$OUT_JARS_DIR "
          + "-Dokbuck.androidBootClasspath=$ANDROID_BOOTCLASSPATH ";
  private static final String SUFFIX =
      "-cp $(location "
          + TransformManager.TRANSFORM_RULE
          + ") "
          + "com.uber.okbuck.transform.CliTransform; ";
  private static final String OKBUCK_TRANSFORM_TARGET_NAME = "okbuck_transform";
  private static final String BINARY_EXCLUDES = "META-INF";

  private final Project rootProject;
  private final BuckFileManager buckFileManager;

  @Nullable private ImmutableSet<String> dependencies;

  public TransformManager(Project rootProject, BuckFileManager buckFileManager) {
    this.rootProject = rootProject;
    this.buckFileManager = buckFileManager;
  }

  public void fetchTransformDeps() {
    DependencyCache dependencyCache =
        new DependencyCache(rootProject, ProjectUtil.getDependencyManager(rootProject));

    Scope transformScope =
        Scope.builder(rootProject)
            .configuration(CONFIGURATION_TRANSFORM)
            .depCache(dependencyCache)
            .build();

    dependencies =
        new ImmutableSet.Builder<String>()
            .addAll(BuckRuleComposer.targets(transformScope.getTargetDeps()))
            .addAll(BuckRuleComposer.external(transformScope.getExternalDeps()))
            .add(TRANSFORM_JAR_RULE)
            .build();
  }

  public void finalizeDependencies() {
    if (dependencies != null && dependencies.size() > 0) {
      Path cacheDir = rootProject.file(TRANSFORM_CACHE).toPath();
      FileUtil.deleteQuietly(cacheDir);

      cacheDir.toFile().mkdirs();

      copyFiles(cacheDir);
      composeBuckFile(cacheDir);
    }
  }

  private static void copyFiles(Path cacheDir) {
    FileUtil.copyResourceToProject(
        TRANSFORM_FOLDER + TRANSFORM_JAR, new File(cacheDir.toFile(), TRANSFORM_JAR));
  }

  private void composeBuckFile(Path cacheDir) {
    ImmutableList.Builder<Rule> rulesBuilder = new ImmutableList.Builder<>();

    if (dependencies != null) {
      rulesBuilder
          .add(
              new NativePrebuilt()
                  .prebuiltType(RuleType.PREBUILT_JAR.getProperties().get(0))
                  .prebuilt(TRANSFORM_JAR)
                  .ruleType(RuleType.PREBUILT_JAR.getBuckName())
                  .name(TRANSFORM_JAR))
          .add(
              new JvmBinaryRule()
                  .excludes(Collections.singleton(BINARY_EXCLUDES))
                  .deps(dependencies)
                  .ruleType(RuleType.JAVA_BINARY.getBuckName())
                  .name(OKBUCK_TRANSFORM_TARGET_NAME)
                  .defaultVisibility());
    }

    buckFileManager.writeToBuckFile(
        rulesBuilder.build(), cacheDir.resolve(OkBuckGradlePlugin.BUCK).toFile());
  }

  public Pair<String, List<String>> getBashCommandAndTransformDeps(AndroidAppTarget target) {
    List<Pair<String, String>> results =
        target
            .getTransforms()
            .stream()
            .map(it -> getBashCommandAndTransformDeps(target, it))
            .collect(Collectors.toList());
    return Pair.of(
        String.join(" ", results.stream().map(Pair::getLeft).collect(Collectors.toList())),
        results.stream().map(Pair::getRight).filter(Objects::nonNull).collect(Collectors.toList()));
  }

  private static Pair<String, String> getBashCommandAndTransformDeps(
      AndroidAppTarget target, Map<String, String> options) {
    String transformClass = options.get(OPT_TRANSFORM_CLASS);
    String configFile = options.get(OPT_CONFIG_FILE);
    StringBuilder bashCmd = new StringBuilder(PREFIX);

    @Var String configFileRule = null;
    if (transformClass != null) {
      bashCmd.append("-Dokbuck.transformClass=").append(transformClass).append(" ");
    }
    if (configFile != null) {
      configFileRule =
          getTransformConfigRuleForFile(
              target.getProject(), target.getRootProject().file(configFile));
      bashCmd.append("-Dokbuck.configFile=$(location ").append(configFileRule).append(") ");
    }
    bashCmd.append(SUFFIX);
    return Pair.of(bashCmd.toString(), configFileRule);
  }

  private static String getTransformConfigRuleForFile(Project project, File config) {
    String relativeConfigPath =
        FileUtil.getRelativePath(project.getRootProject().getProjectDir(), config);
    ProjectUtil.getPlugin(project.getRootProject()).exportedPaths.add(relativeConfigPath);

    if (project.getProjectDir().equals(config.getParentFile())) {
      return ":" + config.getName();
    } else {
      String configTarget = BuckRuleComposer.fileRule(relativeConfigPath);
      Preconditions.checkNotNull(configTarget);
      return configTarget;
    }
  }
}
