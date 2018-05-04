package ca.cutterslade.gradle.helm

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.FeatureSpec
import java.net.URI
import java.util.Optional

// this class is using KotlinTest - it was already written this way, easier to just import kotlintest rather than translate to Spek.
class HttpProxySettingsHandlerTest : FeatureSpec() {
  init {
    val testProxy = "http://myproxytest:8080"

    feature("unset env variables shouldn't cause a problem") {
      // given the environment variable
      val mySys = MockSystemDelegate()
      mySys.clear()

      // when
      val handler = HttpProxySettingsHandler(mySys)

      // then
      handler.proxy.isPresent shouldBe false
      handler.proxyProtocol.isPresent shouldBe false
      handler.proxyHost.isPresent shouldBe false
      handler.proxyPort.isPresent shouldBe false
      handler.noProxySettings.isPresent shouldBe false
    }

    feature("badly set HTTP_PROXY should cause 'not-present' on getHost() and getProxy()") {
      // given
      val mySys = MockSystemDelegate()
      mySys.clear()
      mySys["http_proxy"] = "some garbage"

      // when
      val handler = HttpProxySettingsHandler(mySys)

      // then
      handler.proxy.isPresent shouldBe false
      handler.proxyHost.isPresent shouldBe false
      handler.proxyPort.isPresent shouldBe false
    }

    feature("the simple proxy settings handler can read from environment properties and is not case sensitive") {

      scenario("environment variable http_proxy is set") {
        // given the environment variable
        val mySys = MockSystemDelegate()
        mySys["http_proxy"] = testProxy

        // when
        val handler = HttpProxySettingsHandler(mySys)

        // then the proxy should be
        handler.proxy shouldBe Optional.of(URI.create(testProxy).toURL())
      }

      scenario("system property http_proxy is set") {
        // given
        System.setProperty("http_proxy", testProxy)

        // when (using the default system abstraction
        val handler = HttpProxySettingsHandler()

        // then
        handler.proxy shouldBe Optional.of(URI.create(testProxy).toURL())
      }
    }

    feature("the no_proxy settings are read from environment and system properties") {
      val noProxy = "this,is,a,no,proxy"
      scenario("environment variable no_proxy is set") {
        // given the environment variable
        val mySys = MockSystemDelegate()
        mySys["no_proxy"] = noProxy

        // when
        val handler = HttpProxySettingsHandler(mySys)

        // then
        handler.noProxySettings shouldBe Optional.of(noProxy)
      }

      scenario("system property http_proxy is set") {
        // given
        System.setProperty("no_proxy", noProxy)

        // when (using the default system abstraction
        val handler = HttpProxySettingsHandler()

        // then
        handler.noProxySettings shouldBe Optional.of(noProxy)
      }
    }

    feature("the proxy uri components are parsed from the proxy") {
      scenario("parse scheme, host and port from full URI") {
        // given the environment variable
        val mySys = MockSystemDelegate()
        mySys["http_proxy"] = testProxy

        // when
        val handler = HttpProxySettingsHandler(mySys)

        // then the proxy should be
        handler.proxyProtocol shouldBe Optional.of("http")
        handler.proxyHost shouldBe Optional.of("myproxytest")
        handler.proxyPort shouldBe Optional.of(8080)
      }
    }

    feature("environment and system properties are case-insensitive") {
      scenario("environment variable http_proxy is set with random case") {
        // given the environment variable
        val mySys = MockSystemDelegate()
        mySys["HTTp_pROXy"] = testProxy

        // when
        val handler = HttpProxySettingsHandler(mySys)

        // then the proxy should be
        handler.proxy shouldBe Optional.of(URI.create(testProxy).toURL())
      }

      scenario("system property http_proxy is set with random case") {
        // given
        System.setProperty("HttP_PROXY", testProxy)

        // when (using the default system abstraction
        val handler = HttpProxySettingsHandler()

        // then
        handler.proxy shouldBe Optional.of(URI.create(testProxy).toURL())
      }
    }

  }
}

/**
 * Overrides the CaseInsensitiveSystemDelegate to make the setter and the clear functions public for test usage.
 */
class MockSystemDelegate : CaseInsensitiveSystemDelegate() {
  public override fun set(name: String, value: String): String? {
    return super.set(name, value)
  }

  public override fun clear() {
    super.clear()
  }
}