package de.heisluft;

import org.apache.logging.log4j.LogManager;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.PathWatcher;
import org.eclipse.jetty.util.UrlEncoded;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Bootstrap extends AbstractHandler {

  private static Path configPath = Path.of("/usr/local/etc/relay-monitor.conf");
  private static Set<String> GPIOS = new HashSet<>();
  private PathWatcher watcher;

  public static void main(String[] args) throws IOException {

    System.setProperty("log4j.configurationFile", "log4j2.xml");
    if(!Files.exists(configPath))
      Files.write(configPath, Bootstrap.class.getResourceAsStream("/defaultconfig").readAllBytes());
    Files.readAllLines(configPath).stream().filter(s -> !s.startsWith("#"))
        .map(s -> s.substring(0, s.indexOf(','))).filter(Predicate.not(String::isBlank))
        .forEach(GPIOS::add);

    Server server = new Server();

    HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.setSendXPoweredBy(false);
    httpConfig.setSendServerVersion(false);

    ServerConnector connector = new ServerConnector(server);
    connector.addConnectionFactory(new HttpConnectionFactory(httpConfig));
    connector.setPort(8000);

    server.setConnectors(new ServerConnector[]{connector});
    server.setHandler(new Bootstrap());
    try {
      server.start();
      server.join();
    } catch(Exception e) {
      LogManager.getLogger("Runtime").catching(e);
    }
  }

  @Override
  protected void doStart() throws Exception {
    super.doStart();
    watcher = new PathWatcher();
    watcher.watch(configPath);
    watcher.addListener((PathWatcher.Listener) event -> {
      if(event.getType().equals(PathWatcher.PathWatchEventType.MODIFIED)) {
        try {
          Files.readAllLines(configPath).stream().filter(s -> !s.startsWith("#"))
              .map(s -> s.substring(0, s.indexOf(','))).filter(Predicate.not(String::isBlank))
              .forEach(GPIOS::add);
        } catch(IOException e) {
          e.printStackTrace();
        }
      }
    });
    watcher.setNotifyExistingOnStart(false);
    watcher.start();
  }

  @Override
  protected void doStop() throws Exception {
    super.doStop();
    watcher.stop();
  }

  @Override
  public void handle(String target, Request baseRequest, HttpServletRequest request,
      HttpServletResponse response) throws IOException {

    if(target.equals("/")) {
      response.setContentType("text/html; charset=utf-8");
      response.setStatus(200);
      response.getWriter()
          .print(new String(getClass().getResourceAsStream("/index.html").readAllBytes()));
      baseRequest.setHandled(true);
      return;
    }
    if(target.equals("/config")) {
      response.setContentType("text/plain; charset=utf-8");
      response.setStatus(200);
      response.getWriter().print(new String(Files.readAllBytes(configPath)));
      baseRequest.setHandled(true);
      return;
    }

    if(!target.startsWith("/gpio") || !GPIOS.contains(target.substring(5))) return;

    if(request.getMethod().equals("GET")) {
      response.setStatus(200);
      response.setContentType("text/plain");
      response.getWriter()
          .println(new String(Files.readAllBytes(Path.of("/sys/class/gpio" + target + "/value"))));
      baseRequest.setHandled(true);
      return;
    }
    if(!request.getMethod().equals("POST")) return;
    MultiMap<String> postParams = new MultiMap<>();
    UrlEncoded.decodeUtf8To(request.getReader().lines().collect(Collectors.joining()), postParams);
    if(postParams.get("value").size() != 1 ||
        Predicate.isEqual("1").or(Predicate.isEqual("0")).negate()
            .test(postParams.getValue("value", 0))) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    Files.write(Path.of("/sys/class/gpio" + target + "/value"),
        postParams.getValue("value", 0).getBytes(StandardCharsets.US_ASCII));
    response.setStatus(204);
    baseRequest.setHandled(true);
  }
}
