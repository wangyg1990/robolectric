package org.robolectric.internal.dependency;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SuppressWarnings("UnstableApiUsage")
public class MavenDependencyResolverTest {
  private static final String REPOSITORY_URL = "https://default-repo/";
  private static final String REPOSITORY_USERNAME = "username";
  private static final String REPOSITORY_PASSWORD = "password";
  private static final HashFunction SHA1 = Hashing.sha1();

  private static DependencyJar[] successCases =
      new DependencyJar[] {
        new DependencyJar("group", "artifact", "1"),
        new DependencyJar("org.group2", "artifact2-name", "2.4.5"),
        new DependencyJar("org.robolectric", "android-all", "10-robolectric-5803371"),
      };

  private static Map<URL, String> urlContents = new HashMap<>();

  static {
    for (DependencyJar dependencyJar : successCases) {
      addTestArtifact(dependencyJar);
    }
  }

  private File localRepositoryDir;
  private ExecutorService executorService;
  private MavenDependencyResolver mavenDependencyResolver;
  private TestMavenArtifactFetcher mavenArtifactFetcher;

  @Before
  public void setUp() throws Exception {
    executorService = MoreExecutors.newDirectExecutorService();
    localRepositoryDir = Files.createTempDir();
    localRepositoryDir.deleteOnExit();
    mavenArtifactFetcher =
        new TestMavenArtifactFetcher(
            REPOSITORY_URL,
            REPOSITORY_USERNAME,
            REPOSITORY_PASSWORD,
            localRepositoryDir,
            executorService);
    mavenDependencyResolver = new TestMavenDependencyResolver();
  }

  @Test
  public void getLocalArtifactUrl_placesFilesCorrectlyForSingleURL() throws Exception {
    DependencyJar dependencyJar = successCases[0];
    mavenDependencyResolver.getLocalArtifactUrl(dependencyJar);
    assertThat(mavenArtifactFetcher.getNumRequests()).isEqualTo(4);
    MavenJarArtifact artifact = new MavenJarArtifact(dependencyJar);
    checkJarArtifact(artifact);
  }

  @Test
  public void getLocalArtifactUrl_placesFilesCorrectlyForMultipleURL() throws Exception {
    mavenDependencyResolver.getLocalArtifactUrls(successCases);
    assertThat(mavenArtifactFetcher.getNumRequests()).isEqualTo(4 * successCases.length);
    for (DependencyJar dependencyJar : successCases) {
      MavenJarArtifact artifact = new MavenJarArtifact(dependencyJar);
      checkJarArtifact(artifact);
    }
  }

  private void checkJarArtifact(MavenJarArtifact artifact) throws Exception {
    File jar = new File(localRepositoryDir, artifact.jarPath());
    File pom = new File(localRepositoryDir, artifact.pomPath());
    File jarSha1 = new File(localRepositoryDir, artifact.jarSha1Path());
    File pomSha1 = new File(localRepositoryDir, artifact.pomSha1Path());
    assertThat(jar.exists()).isTrue();
    assertThat(readFile(jar)).isEqualTo(artifact.toString() + " jar contents");
    assertThat(pom.exists()).isTrue();
    assertThat(readFile(pom)).isEqualTo(artifact.toString() + " pom contents");
    assertThat(jarSha1.exists()).isTrue();
    assertThat(readFile(jarSha1)).isEqualTo(sha1(artifact.toString() + " jar contents"));
    assertThat(pom.exists()).isTrue();
    assertThat(readFile(pomSha1)).isEqualTo(sha1(artifact.toString() + " pom contents"));
  }

  @Test
  public void getLocalArtifactUrl_doesNotFetchWhenArtifactsExist() throws Exception {
    DependencyJar dependencyJar = new DependencyJar("group", "artifact", "1");
    MavenJarArtifact mavenJarArtifact = new MavenJarArtifact(dependencyJar);
    File artifactFile = new File(localRepositoryDir, mavenJarArtifact.jarPath());
    artifactFile.getParentFile().mkdirs();
    Files.write(new byte[0], artifactFile);
    assertThat(artifactFile.exists()).isTrue();
    mavenDependencyResolver.getLocalArtifactUrl(dependencyJar);
    assertThat(mavenArtifactFetcher.getNumRequests()).isEqualTo(0);
  }

  @Test(expected = AssertionError.class)
  public void getLocalArtifactUrl_handlesFileNotFound() throws Exception {
    DependencyJar dependencyJar = new DependencyJar("group", "missing-artifact", "1");
    mavenDependencyResolver.getLocalArtifactUrl(dependencyJar);
  }

  @Test(expected = AssertionError.class)
  public void getLocalArtifactUrl_handlesInvalidSha1() throws Exception {
    DependencyJar dependencyJar = new DependencyJar("group", "artifact-invalid-sha1", "1");
    addTestArtifactInvalidSha1(dependencyJar);
    mavenDependencyResolver.getLocalArtifactUrl(dependencyJar);
  }

  class TestMavenDependencyResolver extends MavenDependencyResolver {

    @Override
    protected MavenArtifactFetcher createMavenFetcher(
        String repositoryUrl,
        String repositoryUserName,
        String repositoryPassword,
        File localRepositoryDir,
        ExecutorService executorService) {
      return mavenArtifactFetcher;
    }

    @Override
    protected ExecutorService createExecutorService() {
      return executorService;
    }

    @Override
    protected File getLocalRepositoryDir() {
      return localRepositoryDir;
    }
  }

  static class TestMavenArtifactFetcher extends MavenArtifactFetcher {
    private ExecutorService executorService;
    private int numRequests;

    public TestMavenArtifactFetcher(
        String repositoryUrl,
        String repositoryUserName,
        String repositoryPassword,
        File localRepositoryDir,
        ExecutorService executorService) {
      super(
          repositoryUrl,
          repositoryUserName,
          repositoryPassword,
          localRepositoryDir,
          executorService);
      this.executorService = executorService;
    }

    @Override
    protected ListenableFuture<Void> createFetchToFileTask(URL remoteUrl, File tempFile) {
      return Futures.submitAsync(
          () -> {
            numRequests += 1;
            if (!urlContents.containsKey(remoteUrl)) {
              throw new IOException("Resource not found " + remoteUrl);
            }
            Files.write(urlContents.get(remoteUrl).getBytes(), tempFile);
            return Futures.immediateFuture(null);
          },
          executorService);
    }

    public int getNumRequests() {
      return numRequests;
    }
  }

  static void addTestArtifact(DependencyJar dependencyJar) {
    MavenJarArtifact mavenJarArtifact = new MavenJarArtifact(dependencyJar);
    try {
      String jarContents = mavenJarArtifact.toString() + " jar contents";
      urlContents.put(new URL(REPOSITORY_URL + mavenJarArtifact.jarPath()), jarContents);
      urlContents.put(new URL(REPOSITORY_URL + mavenJarArtifact.jarSha1Path()), sha1(jarContents));
      String pomContents = mavenJarArtifact.toString() + " pom contents";
      urlContents.put(new URL(REPOSITORY_URL + mavenJarArtifact.pomPath()), pomContents);
      urlContents.put(new URL(REPOSITORY_URL + mavenJarArtifact.pomSha1Path()), sha1(pomContents));
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
  }

  static void addTestArtifactInvalidSha1(DependencyJar dependencyJar) {
    MavenJarArtifact mavenJarArtifact = new MavenJarArtifact(dependencyJar);
    try {
      String jarContents = mavenJarArtifact.toString() + " jar contents";
      urlContents.put(new URL(REPOSITORY_URL + mavenJarArtifact.jarPath()), jarContents);
      urlContents.put(
          new URL(REPOSITORY_URL + mavenJarArtifact.jarSha1Path()), sha1("No the same content"));
      String pomContents = mavenJarArtifact.toString() + " pom contents";
      urlContents.put(new URL(REPOSITORY_URL + mavenJarArtifact.pomPath()), pomContents);
      urlContents.put(
          new URL(REPOSITORY_URL + mavenJarArtifact.pomSha1Path()),
          sha1("Really not the same content"));
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
  }

  static String sha1(String contents) {
    return SHA1.hashString(contents, StandardCharsets.UTF_8).toString();
  }

  static String readFile(File file) throws IOException {
    return new String(Files.asByteSource(file).read());
  }
}
