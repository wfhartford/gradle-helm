package ca.cutterslade.gradle.helm

import com.google.common.io.BaseEncoding
import org.glassfish.grizzly.PortRange
import org.glassfish.grizzly.http.Method
import org.glassfish.grizzly.http.server.HttpHandler
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.http.server.Request
import org.glassfish.grizzly.http.server.Response
import org.glassfish.grizzly.http.util.Header
import org.glassfish.grizzly.http.util.HttpStatus

class GrizzlyServer : AutoCloseable {
  private val server = HttpServer.createSimpleServer(null, "localhost", PortRange(49152, 65535))
  val handler = TracingHandler()
  private var _port: Int? = null
  val port: Int
    get() = _port ?: throw IllegalStateException("port is null")

  fun start() = server.run {
    serverConfiguration.addHttpHandler(handler)
    start()
    _port = listeners.let {
      if (it.size != 1) throw IllegalStateException("Expected a single listener, found ${it.size}: $it")
      it.first().port
    }
  }

  override fun close() {
    server.shutdownNow()
    _port = null
  }
}

data class RequestDetails(
        val method: Method,
        val host: String,
        val path: String,
        val contentLength: Long,
        val headers: List<Pair<String, String>>
) {
  constructor(request: Request) :
          this(
                  request.method,
                  request.getHeader(Header.Host),
                  request.httpHandlerPath,
                  request.contentLengthLong,
                  request.headerNames.flatMap { name ->
                    request.getHeaders(name).map { name to it }
                  }
          )

  fun hasHeader(name: String) = headers.any { it.first.toLowerCase() == name.toLowerCase() }
}

class TracingHandler : HttpHandler() {
  var requireAuth = false
  val requests = mutableListOf<RequestDetails>()

  companion object {
    private val authorizedHeader = "Basic ${BaseEncoding.base64().encode("user:pass".toByteArray())}"
  }

  override fun service(request: Request, response: Response) = response.run {
    val details = RequestDetails(request)
    requests += details
    if (!requireAuth || request.getHeader(Header.Authorization) == authorizedHeader) {
      contentType = "text/plain"
      contentLength = 2
      writer.write("ok")
    }
    else {
      setHeader(Header.WWWAuthenticate, "Basic realm=\"test\"")
      setStatus(HttpStatus.UNAUTHORIZED_401)
    }
  }

  fun reset() {
    requests.clear()
    requireAuth = false
  }
}