//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemoBaseTests extends AbstractDistributionTest
{
    @Test
    public void testJspDump() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] args = {
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };

        try (DistributionTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitConsoleLogsFor("Started @", 20, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/jsp/dump.jsp");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("PathInfo"));
            assertThat(response.getContentAsString(), not(containsString("<%")));
        }
    }

    @Test
    public void testAsyncRest() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] args = {
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };

        try (DistributionTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitConsoleLogsFor("Started @", 20, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response;

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testSerial?items=kayak");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Blocking: kayak"));

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testSerial?items=mouse,beer,gnome");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Blocking: mouse,beer,gnome"));

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testAsync?items=kayak");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Asynchronous: kayak"));

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testAsync?items=mouse,beer,gnome");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Asynchronous: mouse,beer,gnome"));
        }
    }

    @Test
    public void testSpec() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] args = {
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };

        try (DistributionTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitConsoleLogsFor("Started @", 20, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.POST("http://localhost:" + httpPort + "/test-spec/asy/xx").send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
            assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));
        }
    }
}
