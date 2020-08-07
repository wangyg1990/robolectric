package org.robolectric.internal.dependency;

import com.google.common.base.Strings;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * Class responsible for fetching artifacts from Maven. This uses a thread pool of size two in order
 * to parallelize downloads.
 */
@SuppressWarnings("UnstableApiUsage")
public class MavenArtifactFetcher {

  private final String repositoryUrl;
  private final String repositoryUserName;
  private final String repositoryPassword;
  private final File localRepositoryDir;
  private final ExecutorService executorService;
  private File stagingRepositoryDir;

  public MavenArtifactFetcher(
      String repositoryUrl,
      String repositoryUserName,
      String repositoryPassword,
      File localRepositoryDir,
      ExecutorService executorService) {
    this.repositoryUrl = repositoryUrl;
    this.repositoryUserName = repositoryUserName;
    this.repositoryPassword = repositoryPassword;
    this.localRepositoryDir = localRepositoryDir;
    this.executorService = executorService;
  }

  public void fetchArtifact(MavenJarArtifact artifact) {
    // Assume that if the file exists in the local repository, it has been fetched successfully.
    if (new File(localRepositoryDir, artifact.jarPath()).exists()) {
      return;
    }
    this.stagingRepositoryDir = Files.createTempDir();
    this.stagingRepositoryDir.deleteOnExit();
    createArtifactSubdirectory(artifact, stagingRepositoryDir);
    try {
      Futures.whenAllSucceed(
              fetchToStagingRepository(artifact.pomSha1Path()),
              fetchToStagingRepository(artifact.pomPath()),
              fetchToStagingRepository(artifact.jarSha1Path()),
              fetchToStagingRepository(artifact.jarPath()))
          .callAsync(
              () -> {
                createArtifactSubdirectory(artifact, localRepositoryDir);
                boolean pomValid = validateStagedFiles(artifact.pomPath(), artifact.pomSha1Path());
                if (!pomValid) {
                  throw new AssertionError("Unable to validate POM file");
                }
                boolean jarValid = validateStagedFiles(artifact.jarPath(), artifact.jarSha1Path());
                if (!jarValid) {
                  throw new AssertionError("Unable to validate JAR file");
                }
                commitFromStaging(artifact.pomPath());
                commitFromStaging(artifact.jarPath());
                commitFromStaging(artifact.jarSha1Path());
                commitFromStaging(artifact.pomSha1Path());
                removeArtifactFiles(stagingRepositoryDir, artifact);
                return Futures.immediateFuture(null);
              },
              executorService)
          .get();
    } catch (InterruptedException | ExecutionException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt(); // Restore the interrupted status
      }
      removeArtifactFiles(stagingRepositoryDir, artifact);
      removeArtifactFiles(localRepositoryDir, artifact);
      throw new AssertionError("Failed to fetch maven artifacts", e);
    }
  }

  private void removeArtifactFiles(File repositoryDir, MavenJarArtifact artifact) {
    new File(repositoryDir, artifact.jarPath()).delete();
    new File(repositoryDir, artifact.jarSha1Path()).delete();
    new File(repositoryDir, artifact.pomPath()).delete();
    new File(repositoryDir, artifact.pomSha1Path()).delete();
  }

  private boolean validateStagedFiles(String filePath, String sha1Path) throws IOException {
    File tempFile = new File(this.stagingRepositoryDir, filePath);
    File sha1File = new File(this.stagingRepositoryDir, sha1Path);

    HashCode expected = HashCode.fromString(new String(Files.asByteSource(sha1File).read()));
    HashCode actual = Files.asByteSource(tempFile).hash(Hashing.sha1());
    return expected.equals(actual);
  }

  private void createArtifactSubdirectory(MavenJarArtifact artifact, File repositoryDir) {
    File artifactDirectory = new File(repositoryDir, artifact.jarPath()).getParentFile();
    artifactDirectory.mkdirs();
  }

  private URL getRemoteUrl(String path) {
    String url = this.repositoryUrl;
    if (!url.endsWith("/")) {
      url = url + "/";
    }
    try {
      return new URI(url + path).toURL();
    } catch (URISyntaxException | MalformedURLException e) {
      throw new AssertionError(e);
    }
  }

  private ListenableFuture<Void> fetchToStagingRepository(String path) {
    URL remoteUrl = getRemoteUrl(path);
    File destination = new File(this.stagingRepositoryDir, path);
    return createFetchToFileTask(remoteUrl, destination);
  }

  protected ListenableFuture<Void> createFetchToFileTask(URL remoteUrl, File tempFile) {
    return Futures.submitAsync(
        new FetchToFileTask(remoteUrl, tempFile, repositoryUserName, repositoryPassword),
        this.executorService);
  }

  private void commitFromStaging(String path) throws IOException {
    File source = new File(this.stagingRepositoryDir, path);
    File destination = new File(this.localRepositoryDir, path);
    if (!source.renameTo(destination)) {
      throw new IOException("Unable to rename to " + destination);
    }
  }

  private static class FetchToFileTask implements AsyncCallable<Void> {
    private final URL remoteURL;
    private final File localFile;
    private String repositoryUserName;
    private String repositoryPassword;

    public FetchToFileTask(
        URL remoteURL, File localFile, String repositoryUserName, String repositoryPassword) {
      this.remoteURL = remoteURL;
      this.localFile = localFile;
      this.repositoryUserName = repositoryUserName;
      this.repositoryPassword = repositoryPassword;
    }

    @Override
    public ListenableFuture<Void> call() throws Exception {
      HttpURLConnection connection = (HttpURLConnection) remoteURL.openConnection();

      // Add authoriztion header if applicable.
      if (!Strings.isNullOrEmpty(this.repositoryUserName)) {
        String encoded =
            Base64.getEncoder()
                .encodeToString(
                    (this.repositoryUserName + ":" + this.repositoryPassword)
                        .getBytes(StandardCharsets.UTF_8));
        connection.setRequestProperty("Authorization", "Basic " + encoded);
      }

      try (InputStream inputStream = remoteURL.openConnection().getInputStream();
          FileOutputStream outputStream = new FileOutputStream(localFile)) {
        ByteStreams.copy(inputStream, outputStream);
      }
      return Futures.immediateFuture(null);
    }
  }
}
