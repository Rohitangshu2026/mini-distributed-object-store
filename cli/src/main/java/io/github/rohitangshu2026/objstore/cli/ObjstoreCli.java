package io.github.rohitangshu2026.objstore.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The {@code objstore} command-line client. It is plain Java with no Spring, so it starts quickly
 * (decision 0002).
 */
@Command(
    name = "objstore",
    mixinStandardHelpOptions = true,
    versionProvider = ObjstoreCli.BuildVersion.class,
    description = "Client for the mini distributed object store.")
public class ObjstoreCli implements Runnable {

  /** This command's model, injected by picocli and used to report usage errors. */
  @Spec CommandSpec spec;

  /**
   * Runs the client and exits with its status: 0 on success, 2 on a usage error.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    System.exit(new CommandLine(new ObjstoreCli()).execute(args));
  }

  /** Runs when no command is given: picocli reports the usage error and exits with code 2. */
  @Override
  public void run() {
    throw new ParameterException(spec.commandLine(), "Missing command");
  }

  /** Reads the version that the Maven build records in {@code META-INF/build-info.properties}. */
  static final class BuildVersion implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
      Properties build = new Properties();
      try (InputStream in =
          ObjstoreCli.class.getResourceAsStream("/META-INF/build-info.properties")) {
        if (in == null) {
          return new String[] {"objstore (version unknown)"};
        }
        build.load(in);
      }
      return new String[] {"objstore " + build.getProperty("build.version")};
    }
  }
}
