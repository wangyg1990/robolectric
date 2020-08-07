package org.robolectric.internal.dependency;

import com.google.common.base.Strings;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.robolectric.MavenRoboSettings;
import org.robolectric.util.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class MavenDependencyResolver implements DependencyResolver {

  private final ExecutorService executorService;
  private final MavenArtifactFetcher mavenArtifactFetcher;
  private final File localRepositoryDir;

  public MavenDependencyResolver() {
    this(MavenRoboSettings.getMavenRepositoryUrl(), MavenRoboSettings.getMavenRepositoryId(), MavenRoboSettings
        .getMavenRepositoryUserName(), MavenRoboSettings.getMavenRepositoryPassword());
  }

  public MavenDependencyResolver(String repositoryUrl, String repositoryId, String repositoryUserName, String repositoryPassword) {
    this.executorService = createExecutorService();
    this.localRepositoryDir = getLocalRepositoryDir();
    this.mavenArtifactFetcher =
        createMavenFetcher(
            repositoryUrl,
            repositoryUserName,
            repositoryPassword,
            localRepositoryDir,
            this.executorService);
  }

  @Override
  public URL[] getLocalArtifactUrls(DependencyJar dependency) {
    return getLocalArtifactUrls(new DependencyJar[] {dependency});
  }

  /**
   * Get an array of local artifact URLs for the given dependencies. The order of the URLs is guaranteed to be the
   * same as the input order of dependencies, i.e., urls[i] is the local artifact URL for dependencies[i].
   */
  @SuppressWarnings("NewApi")
  public URL[] getLocalArtifactUrls(DependencyJar... dependencies) {
    List<MavenJarArtifact> artifacts = new ArrayList<>(dependencies.length);
    for (DependencyJar dependencyJar : dependencies) {
      MavenJarArtifact artifact = new MavenJarArtifact(dependencyJar);
      artifacts.add(artifact);
      mavenArtifactFetcher.fetchArtifact(artifact);
    }
    URL[] urls = new URL[dependencies.length];
    try {
      for (int i = 0; i < artifacts.size(); i++) {
        MavenJarArtifact artifact = artifacts.get(i);
        urls[i] = new File(localRepositoryDir, artifact.jarPath()).toURI().toURL();
      }
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
    return urls;
  }

  @Override
  public URL getLocalArtifactUrl(DependencyJar dependency) {
    URL[] urls = getLocalArtifactUrls(dependency);
    if (urls.length > 0) {
      return urls[0];
    }
    return null;
  }

  /** Locates the local maven repo. */
  protected File getLocalRepositoryDir() {
    String localRepoDir = System.getProperty("maven.repo.local");
    if (!Strings.isNullOrEmpty(localRepoDir)) {
      return new File(localRepoDir);
    }
    File mavenHome = new File(System.getProperty("user.home"), ".m2");
    String settingsRepoDir = getLocalRepositoryFromSettings(mavenHome);
    if (!Strings.isNullOrEmpty(settingsRepoDir)) {
      return new File(settingsRepoDir);
    }
    return new File(mavenHome, "repository");
  }

  private String getLocalRepositoryFromSettings(File mavenHome) {
    File mavenSettings = new File(mavenHome, "settings.xml");
    if (!mavenSettings.exists() || !mavenSettings.isFile()) {
      return null;
    }
    try {
      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document document = builder.parse(mavenSettings);
      NodeList nodeList = document.getElementsByTagName("localRepository");

      if (nodeList.getLength() != 0) {
        Node node = nodeList.item(0);
        return node.getTextContent();
      }
    } catch (ParserConfigurationException | IOException | SAXException e) {
      Logger.error("Error reading settings.xml", e);
    }
    return null;
  }

  protected MavenArtifactFetcher createMavenFetcher(
      String repositoryUrl,
      String repositoryUserName,
      String repositoryPassword,
      File localRepositoryDir,
      ExecutorService executorService) {
    return new MavenArtifactFetcher(
        repositoryUrl, repositoryUserName, repositoryPassword, localRepositoryDir, executorService);
  }

  protected ExecutorService createExecutorService() {
    return Executors.newFixedThreadPool(2);
  }
}
