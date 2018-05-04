package ca.cutterslade.gradle.helm

import mu.KLogging
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.util.Arrays
import java.util.Optional
import java.util.Optional.of
import java.util.TreeMap


/**
 * Reads both environment and system to pull all properties related to http(s) proxy and parses it into protocol, host, port and a no-proxy string.
 */
class HttpProxySettingsHandler @JvmOverloads constructor(
    sys: CaseInsensitiveSystemDelegate = CaseInsensitiveSystemDelegate()
) {

  private companion object : KLogging()

  val proxy: Optional<URL> = firstAvailable(sys["http_proxy"], sys["https_proxy"]).flatMap { this.tryParseUrl(it) }

  val proxyProtocol: Optional<String> = proxy.flatMap { proxy -> of(proxy.protocol) }

  val proxyHost: Optional<String> = proxy.flatMap { proxy -> of(proxy.host) }

  val proxyPort: Optional<Int> = proxy.flatMap { proxy -> of(proxy.port) }

  val noProxySettings: Optional<String> = sys["no_proxy"]

  val isProxyIsConfigured: Boolean = proxy.isPresent

  val socketAddress: Optional<InetSocketAddress> = proxy.flatMap { proxy -> of(InetSocketAddress(proxy.host, proxy.port)) }

  private fun tryParseUrl(url: String): Optional<URL> =
      try {
        Optional.of(URI.create(url).toURL())
      } catch (e: Exception) {
        logger.warn(e) { "Failed to parse proxy url of '$url'" }
        Optional.empty()
      }


  private fun firstAvailable(vararg options: Optional<String>) =
      Arrays
        .stream(options)
        .filter { it.isPresent }
        .findFirst()
        .flatMap { it }


}

open class CaseInsensitiveSystemDelegate {
  private val env = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)

  init {
    env.putAll(System.getenv())
    System.getProperties().forEach { key, value -> env[key.toString()] = value.toString() }
  }

  operator fun get(name: String) = Optional.ofNullable(env[name])

  protected open operator fun set(name: String, value: String) = env.put(name, value)

  protected open fun clear() = env.clear()
}
