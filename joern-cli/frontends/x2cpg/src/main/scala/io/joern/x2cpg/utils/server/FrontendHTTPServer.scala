package io.joern.x2cpg.utils.server

import io.joern.x2cpg.X2Cpg
import io.joern.x2cpg.X2CpgConfig
import io.joern.x2cpg.X2CpgFrontend
import io.joern.x2cpg.X2CpgMain
import net.freeutils.httpserver.HTTPServer
import net.freeutils.httpserver.HTTPServer.Context
import org.slf4j.LoggerFactory

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.jdk.CollectionConverters.ListHasAsScala

/** Companion object for `FrontendHTTPServer` providing default executor configurations. */
object FrontendHTTPServer {

  /** ExecutorService for single-threaded execution. */
  private lazy val SingleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

  /** ExecutorService for cached thread pool execution. */
  private lazy val CachedThreadPoolExecutor: ExecutorService = Executors.newCachedThreadPool()

  /** Default ExecutorService used by `FrontendHTTPServer`. */
  private val DefaultExecutor: ExecutorService = CachedThreadPoolExecutor

}

/** A trait representing a frontend HTTP server for handling operations any subclass of `X2CpgMain` may offer via its
  * main function. This trait provides methods and configurations for setting up an HTTP server that processes requests
  * related to `X2CpgMain`. It includes handling request execution either in a single-threaded or multi-threaded manner,
  * depending on the executor configuration.
  *
  * @tparam T
  *   The type parameter representing the X2Cpg configuration.
  * @tparam X
  *   The type parameter representing the X2Cpg frontend.
  */
trait FrontendHTTPServer[T <: X2CpgConfig[T], X <: X2CpgFrontend[T]] { this: X2CpgMain[T, X] =>

  /** Logger instance for logging server-related information. */
  private val logger = LoggerFactory.getLogger(this.getClass)

  /** Optionally holds the underlying HTTP server instance. */
  private var underlyingServer: Option[HTTPServer] = None

  /** Creates a new default configuration for the inheriting `X2CpgFrontend`.
    *
    * This method should be overridden by implementations to provide the default configuration object needed for the
    * `X2CpgFrontend` operation.
    *
    * @return
    *   A new instance of the configuration `T`.
    */
  protected def newDefaultConfig(): T

  /** ExecutorService used to execute HTTP requests.
    *
    * This can be overridden to switch between single-threaded and multi-threaded execution. By default, it uses the
    * cached thread pool executor from `FrontendHTTPServer`.
    */
  protected val executor: ExecutorService = FrontendHTTPServer.DefaultExecutor

  /** Handler for HTTP requests, providing functionality to handle specific routes.
    *
    * @param server
    *   The underlying HTTP server instance.
    */
  protected class FrontendHTTPHandler(val server: HTTPServer) {

    /** Handles POST requests to the "/run" endpoint.
      *
      * This method is annotated to handle POST requests directed to the `/run` path. The request `req` is expected to
      * include `input`, `output`, and (optionally) frontend arguments (unbounded). The request is expected to be sent
      * `application/x-www-form-urlencoded`. The provided `X2CpgFrontend` is run with these input/output/arguments and
      * the resulting CPG output path is returned in the response `resp` and status code 200. In case of a failure,
      * status code 400 is sent together with a response containing the reason.
      *
      * @param req
      *   The HTTP request received by the server.
      * @param resp
      *   The HTTP response to be sent by the server.
      * @return
      *   The HTTP status code for the response.
      */
    @Context(value = "/run", methods = Array("POST"))
    def run(req: server.Request, resp: server.Response): Int = {
      resp.getHeaders.add("Content-Type", "text/plain")

      val params = req.getParamsList.asScala
      val outputDir = params
        .collectFirst { case Array(arg, value) if arg == "output" => value }
        .getOrElse(X2CpgConfig.defaultOutputPath)
      val arguments = params.collect {
        case Array(arg, value) if arg == "input"        => Array(value)
        case Array(arg, value) if value.strip().isEmpty => Array(s"--$arg")
        case Array(arg, value)                          => Array(s"--$arg", value)
      }.flatten
      logger.debug("Got POST with arguments: " + arguments.mkString(" "))

      val config = X2Cpg
        .parseCommandLine(arguments.toArray, cmdLineParser, newDefaultConfig())
        .getOrElse(newDefaultConfig())
      Try(frontend.run(config)) match {
        case Failure(exception) =>
          resp.send(400, exception.getMessage)
        case Success(_) =>
          resp.send(200, outputDir)
      }
      0
    }
  }

  /** Stops the underlying HTTP server if it is running.
    *
    * This method checks if the `underlyingServer` is defined and, if so, stops the server. It also logs a debug message
    * indicating that the server has been stopped. If the server is not running, this method does nothing.
    */
  def stop(): Unit = {
    underlyingServer.foreach { server =>
      server.stop()
      logger.debug("Server stopped.")
    }
  }

  /** Starts the HTTP server with the provided configuration.
    *
    * This method initializes the `underlyingServer` with the specified configuration, sets the executor, and adds the
    * appropriate contexts using the `FrontendHTTPHandler`. It then starts the server and logs a debug message
    * indicating the server's host and port. Additionally, a shutdown hook is added to ensure that the server is
    * properly stopped when the application is terminated.
    *
    * @param config
    *   The frontend configuration object of type `T` that contains the server settings, such as the server port.
    */
  def startup(config: T): Unit = {
    underlyingServer = Some(new HTTPServer(config.serverPort))
    val host = underlyingServer.get.getVirtualHost(null)
    underlyingServer.get.setExecutor(executor)
    host.addContexts(new FrontendHTTPHandler(underlyingServer.get))
    try {
      underlyingServer.get.start()
      logger.debug(s"Server started on ${Option(host.getName).getOrElse("localhost")}:${config.serverPort}.")
    } finally {
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        stop()
      }))
    }
  }

}
