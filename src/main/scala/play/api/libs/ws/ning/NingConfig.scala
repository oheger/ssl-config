/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package play.api.libs.ws.ning

import java.security.KeyStore
import java.security.cert.CertPathValidatorException

import javax.net.ssl._
import com.typesafe.config.Config
import com.typesafe.sslconfig.ssl._
import play.api.libs.ws.WSClientConfig

import scala.concurrent.duration._

/**
 * Ning client config.
 *
 * @param wsClientConfig The general WS client config.
 * @param allowPoolingConnection Whether connection pooling should be allowed.
 * @param allowSslConnectionPool Whether connection pooling should be allowed for SSL connections.
 * @param ioThreadMultiplier The multiplier to use for the number of IO threads.
 * @param maxConnectionsPerHost The maximum number of connections to make per host. -1 means no maximum.
 * @param maxConnectionsTotal The maximum total number of connections. -1 means no maximum.
 * @param maxConnectionLifetime The maximum time that a connection should live for in the pool.
 * @param idleConnectionInPoolTimeout The time after which a connection that has been idle in the pool should be closed.
 * @param webSocketIdleTimeout The time after which a websocket connection should be closed.
 * @param maxNumberOfRedirects The maximum number of redirects.
 * @param maxRequestRetry The maximum number of times to retry a request if it fails.
 * @param disableUrlEncoding Whether the raw URL should be used.
 */
case class NingWSClientConfig(wsClientConfig: WSClientConfig = WSClientConfig(),
                              allowPoolingConnection: Boolean = true,
                              allowSslConnectionPool: Boolean = true,
                              ioThreadMultiplier: Int = 2,
                              maxConnectionsPerHost: Int = -1,
                              maxConnectionsTotal: Int = -1,
                              maxConnectionLifetime: Duration = Duration.Inf,
                              idleConnectionInPoolTimeout: Duration = 1.minute,
                              webSocketIdleTimeout: Duration = 15.minutes,
                              maxNumberOfRedirects: Int = 5,
                              maxRequestRetry: Int = 5,
                              disableUrlEncoding: Boolean = false)

/**
 * Factory for creating NingWSClientConfig, for use from Java.
 */
object NingWSClientConfigFactory {

  def forClientConfig(config: WSClientConfig) = {
    NingWSClientConfig(wsClientConfig = config)
  }
}

/**
 * This class creates a DefaultWSClientConfig object from the play.api.Configuration.
 */
@Singleton
class NingWSClientConfigParser(wsClientConfig: WSClientConfig, configuration: Config) {

  def get = parse()

  def parse(): NingWSClientConfig = {
    import com.typesafe.sslconfig.util._
    val config = configuration.getConfig("play.ws.ning")
    val allowPoolingConnection = config.getBoolean("allowPoolingConnection")
    val allowSslConnectionPool = config.getBoolean("allowSslConnectionPool")
    val ioThreadMultiplier = config.getInt("ioThreadMultiplier")
    val maximumConnectionsPerHost = config.getInt("maxConnectionsPerHost")
    val maximumConnectionsTotal = config.getInt("maxConnectionsTotal")
    val maxConnectionLifetime = config.getFiniteDuration("maxConnectionLifetime")
    val idleConnectionInPoolTimeout = config.getFiniteDuration("idleConnectionInPoolTimeout")
    val webSocketIdleTimeout = config.getFiniteDuration("webSocketIdleTimeout")
    val maximumNumberOfRedirects = config.get[Int]("maxNumberOfRedirects")
    val maxRequestRetry = config.get[Int]("maxRequestRetry")
    val disableUrlEncoding = config.get[Boolean]("disableUrlEncoding")

    NingWSClientConfig(
      wsClientConfig = wsClientConfig,
      allowPoolingConnection = allowPoolingConnection,
      allowSslConnectionPool = allowSslConnectionPool,
      ioThreadMultiplier = ioThreadMultiplier,
      maxConnectionsPerHost = maximumConnectionsPerHost,
      maxConnectionsTotal = maximumConnectionsTotal,
      maxConnectionLifetime = maxConnectionLifetime,
      idleConnectionInPoolTimeout = idleConnectionInPoolTimeout,
      webSocketIdleTimeout = webSocketIdleTimeout,
      maxNumberOfRedirects = maximumNumberOfRedirects,
      maxRequestRetry = maxRequestRetry,
      disableUrlEncoding = disableUrlEncoding
    )
  }
}

/**
 * Builds a valid AsyncHttpClientConfig object from config.
 *
 * @param ningConfig the ning client configuration.
 */
class NingAsyncHttpClientConfigBuilder(ningConfig: NingWSClientConfig = NingWSClientConfig()) {

  /**
   * Configure the underlying builder with values specified by the `config`, and add any custom settings.
   *
   * @return the resulting builder
   */
  def configure(): AsyncHttpClientConfig.Builder = {
    configureSSL(config.ssl)
  }

  private def configureProtocols(existingProtocols: Array[String], sslConfig: SSLConfig): Array[String] = {
    val definedProtocols = sslConfig.enabledProtocols match {
      case Some(configuredProtocols) =>
        // If we are given a specific list of protocols, then return it in exactly that order,
        // assuming that it's actually possible in the SSL context.
        configuredProtocols.filter(existingProtocols.contains).toArray

      case None =>
        // Otherwise, we return the default protocols in the given list.
        Protocols.recommendedProtocols.filter(existingProtocols.contains).toArray
    }

    if (!sslConfig.loose.allowWeakProtocols) {
      val deprecatedProtocols = Protocols.deprecatedProtocols
      for (deprecatedProtocol <- deprecatedProtocols) {
        if (definedProtocols.contains(deprecatedProtocol)) {
          throw new IllegalStateException(s"Weak protocol $deprecatedProtocol found in ws.ssl.protocols!")
        }
      }
    }
    definedProtocols
  }

  private def configureCipherSuites(existingCiphers: Array[String], sslConfig: SSLConfig): Array[String] = {
    val definedCiphers = sslConfig.enabledCipherSuites match {
      case Some(configuredCiphers) =>
        // If we are given a specific list of ciphers, return it in that order.
        configuredCiphers.filter(existingCiphers.contains(_)).toArray

      case None =>
        Ciphers.recommendedCiphers.filter(existingCiphers.contains(_)).toArray
    }

    if (!sslConfig.loose.allowWeakCiphers) {
      val deprecatedCiphers = Ciphers.deprecatedCiphers
      for (deprecatedCipher <- deprecatedCiphers) {
        if (definedCiphers.contains(deprecatedCipher)) {
          throw new IllegalStateException(s"Weak cipher $deprecatedCipher found in ws.ssl.ciphers!")
        }
      }
    }
    definedCiphers
  }

  /**
   * Configures the SSL.  Can use the system SSLContext.getDefault() if "ws.ssl.default" is set.
   */
  def configureSSL(sslConfig: SSLConfig) {

    // context!
    val sslContext = if (sslConfig.default) {
      // logger.info("buildSSLContext: ws.ssl.default is true, using default SSLContext")
      validateDefaultTrustManager(sslConfig)
      SSLContext.getDefault
    } else {
      // break out the static methods as much as we can...
      val keyManagerFactory = buildKeyManagerFactory(sslConfig)
      val trustManagerFactory = buildTrustManagerFactory(sslConfig)
      new ConfigSSLContextBuilder(sslConfig, keyManagerFactory, trustManagerFactory).build()
    }

    // protocols!
    val defaultParams = sslContext.getDefaultSSLParameters
    val defaultProtocols = defaultParams.getProtocols
    val protocols = configureProtocols(defaultProtocols, sslConfig)
    defaultParams.setProtocols(protocols)
    builder.setEnabledProtocols(protocols)

    // ciphers!
    val defaultCiphers = defaultParams.getCipherSuites
    val cipherSuites = configureCipherSuites(defaultCiphers, sslConfig)
    defaultParams.setCipherSuites(cipherSuites)
    builder.setEnabledCipherSuites(cipherSuites)

    builder.setAcceptAnyCertificate(sslConfig.loose.acceptAnyCertificate)

    // Hostname Processing
    if (!sslConfig.loose.disableHostnameVerification) {
      val hostnameVerifier = buildHostnameVerifier(sslConfig)
      builder.setHostnameVerifier(hostnameVerifier)
    } else {
      logger.warn("buildHostnameVerifier: disabling hostname verification")
      val disabledHostnameVerifier = new DisabledComplainingHostnameVerifier
      builder.setHostnameVerifier(disabledHostnameVerifier)
    }

    builder.setSSLContext(sslContext)
  }

  def buildKeyManagerFactory(ssl: SSLConfig): KeyManagerFactoryWrapper = {
    new DefaultKeyManagerFactoryWrapper(ssl.keyManagerConfig.algorithm)
  }

  def buildTrustManagerFactory(ssl: SSLConfig): TrustManagerFactoryWrapper = {
    new DefaultTrustManagerFactoryWrapper(ssl.trustManagerConfig.algorithm)
  }

  def buildHostnameVerifier(sslConfig: SSLConfig): HostnameVerifier = {
    logger.debug("buildHostnameVerifier: enabling hostname verification using {}", sslConfig.hostnameVerifierClass)
    try {
      sslConfig.hostnameVerifierClass.newInstance()
    } catch {
      case e: Exception =>
        throw new IllegalStateException("Cannot configure hostname verifier", e)
    }
  }

  def validateDefaultTrustManager(sslConfig: SSLConfig) {
    // If we are using a default SSL context, we can't filter out certificates with weak algorithms
    // We ALSO don't have access to the trust manager from the SSLContext without doing horrible things
    // with reflection.
    //
    // However, given that the default SSLContextImpl will call out to the TrustManagerFactory and any
    // configuration with system properties will also apply with the factory, we can use the factory
    // method to recreate the trust manager and validate the trust certificates that way.
    //
    // This is really a last ditch attempt to satisfy https://wiki.mozilla.org/CA:MD5and1024 on root certificates.
    //
    // http://grepcode.com/file/repository.grepcode.com/java/root/jdk/openjdk/7-b147/sun/security/ssl/SSLContextImpl.java#79

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(null.asInstanceOf[KeyStore])
    val trustManager: X509TrustManager = tmf.getTrustManagers()(0).asInstanceOf[X509TrustManager]

    val constraints = sslConfig.disabledKeyAlgorithms.map(a => AlgorithmConstraintsParser.parseAll(AlgorithmConstraintsParser.expression, a).get).toSet
    val algorithmChecker = new AlgorithmChecker(keyConstraints = constraints, signatureConstraints = Set())
    for (cert <- trustManager.getAcceptedIssuers) {
      try {
        algorithmChecker.checkKeyAlgorithms(cert)
      } catch {
        case e: CertPathValidatorException =>
          logger.warn("You are using ws.ssl.default=true and have a weak certificate in your default trust store!  (You can modify ws.ssl.disabledKeyAlgorithms to remove this message.)", e)
      }
    }
  }
}