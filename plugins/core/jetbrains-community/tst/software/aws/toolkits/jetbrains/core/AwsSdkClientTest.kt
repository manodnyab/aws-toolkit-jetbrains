// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.net.ssl.CertificateManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpFullRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.aws.toolkits.core.rules.EnvironmentVariableHelper
import software.aws.toolkits.core.rules.SystemPropertyHelper
import java.net.URI

class AwsSdkClientTest {
    @Rule
    @JvmField
    val application = ApplicationRule()

    @Rule
    @JvmField
    val wireMock = createSelfSignedServer()

    @Rule
    @JvmField
    val sysProps = SystemPropertyHelper()

    @Rule
    @JvmField
    val environmentVariableHelper = EnvironmentVariableHelper()

    @Before
    fun setUp() {
        stubFor(any(urlPathEqualTo("/")).willReturn(aResponse().withStatus(200)))
    }

    @After
    fun tearDown() {
        CertificateManager.getInstance().customTrustManager.removeCertificate("selfsign")
    }

    @Test
    fun testCertGetsTrusted() {
        val trustManager = CertificateManager.getInstance().customTrustManager
        val initialSize = trustManager.certificates.size

        val request = mockSdkRequest("https://localhost:" + wireMock.httpsPort())

        val awsSdkClient = AwsSdkClient()
        val response = try {
            awsSdkClient.sharedSdkClient().prepareRequest(
                HttpExecuteRequest.builder().request(request).build()
            ).call()
        } finally {
            Disposer.dispose(awsSdkClient)
        }

        assertThat(response.httpResponse().isSuccessful).isTrue()

        assertThat(trustManager.certificates).hasSize(initialSize + 1)
        assertThat(trustManager.containsCertificate("selfsign")).isTrue()
    }

    @Test
    fun systemPropertyProxyConfigurationIgnored() {
        System.setProperty("http.proxyHost", "foo.com")
        System.setProperty("http.proxyPort", Integer.toString(8888))

        val request = mockSdkRequest("https://localhost:" + wireMock.httpsPort())

        val awsSdkClient = AwsSdkClient()
        val response = try {
            awsSdkClient.sharedSdkClient().prepareRequest(
                HttpExecuteRequest.builder().request(request).build()
            ).call()
        } finally {
            Disposer.dispose(awsSdkClient)
        }

        assertThat(response.httpResponse().isSuccessful).isTrue()
    }

    @Test
    fun environmentVariableProxyConfigurationIgnored() {
        environmentVariableHelper.set("HTTP_PROXY", "https://foo.com:8888")

        val request = mockSdkRequest("https://localhost:" + wireMock.httpsPort())

        val awsSdkClient = AwsSdkClient()
        val response = try {
            awsSdkClient.sharedSdkClient().prepareRequest(
                HttpExecuteRequest.builder().request(request).build()
            ).call()
        } finally {
            Disposer.dispose(awsSdkClient)
        }

        assertThat(response.httpResponse().isSuccessful).isTrue()
    }

    private fun mockSdkRequest(uriString: String): SdkHttpFullRequest? {
        val uri = URI.create(uriString)
        return SdkHttpFullRequest.builder()
            .uri(uri)
            .method(SdkHttpMethod.GET)
            .build()
    }

    private fun createSelfSignedServer(): WireMockRule {
        val selfSignedJks = AwsSdkClientTest::class.java.getResource("/selfSigned.jks")
        return WireMockRule(
            wireMockConfig()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(selfSignedJks.toString())
                .keystorePassword("changeit")
                .keyManagerPassword("changeit")
                .keystoreType("jks")
        )
    }
}
