/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import jdk.httpclient.test.lib.http2.Http2Handler;
import jdk.httpclient.test.lib.http2.Http2TestExchange;
import jdk.httpclient.test.lib.http2.Http2TestServer;
import jdk.httpclient.test.lib.http2.Http2TestServerConnection;
import jdk.internal.net.http.frame.ErrorFrame;
import jdk.test.lib.net.SimpleSSLContext;
import jdk.test.lib.net.URIBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static java.net.http.HttpClient.Version.HTTP_2;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/*
 * @test
 * @bug 8335181
 * @summary verify that the HttpClient correctly handles incoming GOAWAY frames and
 *          retries any unprocessed requests on a new connection
 * @library /test/lib /test/jdk/java/net/httpclient/lib
 * @build jdk.httpclient.test.lib.http2.Http2TestServer
 *        jdk.test.lib.net.SimpleSSLContext
 * @run junit H2GoAwayTest
 */
public class H2GoAwayTest {
    private static final String REQ_PATH = "/test";
    private static Http2TestServer server;
    private static String REQ_URI_BASE;
    private static SSLContext sslCtx;

    @BeforeAll
    static void beforeAll() throws Exception {
        sslCtx = new SimpleSSLContext().get();
        assertNotNull(sslCtx, "SSLContext couldn't be created");
        server = new Http2TestServer("localhost", true, sslCtx);
        server.addHandler(new Handler(), REQ_PATH);
        server.start();
        System.out.println("Server started at " + server.getAddress());
        REQ_URI_BASE = URIBuilder.newBuilder().scheme("https")
                .loopback()
                .port(server.getAddress().getPort())
                .path(REQ_PATH)
                .build().toString();
    }

    @AfterAll
    static void afterAll() {
        if (server != null) {
            System.out.println("Stopping server at " + server.getAddress());
            server.stop();
        }
    }

    /**
     * Verifies that when several requests are sent using send() and the server
     * connection is configured to send a GOAWAY after processing only a few requests, then
     * the remaining requests are retried on a different connection
     */
    @Test
    public void testSequential() throws Exception {
        final RequestApprover reqApprover = new RequestApprover();
        server.setRequestApprover(reqApprover::allowNewRequest);
        try (final HttpClient client = HttpClient.newBuilder().version(HTTP_2)
                .sslContext(sslCtx).build()) {
            final String[] reqMethods = {"HEAD", "GET", "POST"};
            for (final String reqMethod : reqMethods) {
                final int numReqs = RequestApprover.MAX_REQS_PER_CONN + 3;
                final Set<String> connectionKeys = new LinkedHashSet<>();
                for (int i = 1; i <= numReqs; i++) {
                    final URI reqURI = new URI(REQ_URI_BASE + "?seq&" + reqMethod + "=" + i);
                    final HttpRequest req = HttpRequest.newBuilder()
                            .uri(reqURI)
                            .method(reqMethod, HttpRequest.BodyPublishers.noBody())
                            .build();
                    System.out.println("initiating request " + req);
                    final HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
                    final String respBody = resp.body();
                    System.out.println("received response: " + respBody);
                    assertEquals(200, resp.statusCode(),
                            "unexpected status code for request " + resp.request());
                    // response body is the logical key of the connection on which the
                    // request was handled
                    connectionKeys.add(respBody);
                }
                System.out.println("connections involved in handling the requests: "
                        + connectionKeys);
                // all requests have finished, we now just do a basic check that
                // more than one connection was involved in processing these requests
                assertEquals(2, connectionKeys.size(),
                        "unexpected number of connections " + connectionKeys);
            }
        } finally {
            server.setRequestApprover(null); // reset
        }
    }

    /**
     * Verifies that when a server responds with a GOAWAY and then never processes the new retried
     * requests on a new connection too, then the application code receives the request failure.
     * This tests the send() API of the HttpClient.
     */
    @Test
    public void testUnprocessedRaisesException() throws Exception {
        try (final HttpClient client = HttpClient.newBuilder().version(HTTP_2)
                .sslContext(sslCtx).build()) {
            final Random random = new Random();
            final String[] reqMethods = {"HEAD", "GET", "POST"};
            for (final String reqMethod : reqMethods) {
                final int maxAllowedReqs = 2;
                final int numReqs = maxAllowedReqs + 3; // 3 more requests than max allowed
                // create a random set of request paths that will be allowed to be processed
                // on the server
                final Set<String> allowedReqPaths = new HashSet<>();
                while (allowedReqPaths.size() < maxAllowedReqs) {
                    final int rnd = random.nextInt(1, numReqs + 1);
                    final String reqPath = REQ_PATH + "?sync&" + reqMethod + "=" + rnd;
                    allowedReqPaths.add(reqPath);
                }
                // configure the approver
                final OnlyAllowSpecificPaths reqApprover = new OnlyAllowSpecificPaths(allowedReqPaths);
                server.setRequestApprover(reqApprover::allowNewRequest);
                try {
                    int numSuccess = 0;
                    int numFailed = 0;
                    for (int i = 1; i <= numReqs; i++) {
                        final String reqQueryPart = "?sync&" + reqMethod + "=" + i;
                        final URI reqURI = new URI(REQ_URI_BASE + reqQueryPart);
                        final HttpRequest req = HttpRequest.newBuilder()
                                .uri(reqURI)
                                .method(reqMethod, HttpRequest.BodyPublishers.noBody())
                                .build();
                        System.out.println("initiating request " + req);
                        if (allowedReqPaths.contains(REQ_PATH + reqQueryPart)) {
                            // expected to successfully complete
                            numSuccess++;
                            final HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
                            final String respBody = resp.body();
                            System.out.println("received response: " + respBody);
                            assertEquals(200, resp.statusCode(),
                                    "unexpected status code for request " + resp.request());
                        } else {
                            // expected to fail as unprocessed
                            try {
                                final HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
                                fail("Request was expected to fail as unprocessed,"
                                        + " but got response: " + resp.body() + ", status code: "
                                        + resp.statusCode());
                            } catch (IOException ioe) {
                                // verify it failed for the right reason
                                if (ioe.getMessage() == null
                                        || !ioe.getMessage().contains("request not processed by peer")) {
                                    // propagate the original failure
                                    throw ioe;
                                }
                                numFailed++; // failed due to right reason
                                System.out.println("received expected failure: " + ioe
                                        + ", for request " + reqURI);
                            }
                        }
                    }
                    // verify the correct number of requests succeeded/failed
                    assertEquals(maxAllowedReqs, numSuccess, "unexpected number of requests succeeded");
                    assertEquals((numReqs - maxAllowedReqs), numFailed, "unexpected number of requests failed");
                } finally {
                    server.setRequestApprover(null); // reset
                }
            }
        }
    }

    /**
     * Verifies that when a server responds with a GOAWAY and then never processes the new retried
     * requests on a new connection too, then the application code receives the request failure.
     * This tests the sendAsync() API of the HttpClient.
     */
    @Test
    public void testUnprocessedRaisesExceptionAsync() throws Throwable {
        try (final HttpClient client = HttpClient.newBuilder().version(HTTP_2)
                .sslContext(sslCtx).build()) {
            final Random random = new Random();
            final String[] reqMethods = {"HEAD", "GET", "POST"};
            for (final String reqMethod : reqMethods) {
                final int maxAllowedReqs = 2;
                final int numReqs = maxAllowedReqs + 3; // 3 more requests than max allowed
                // create a random set of request paths that will be allowed to be processed
                // on the server
                final Set<String> allowedReqPaths = new HashSet<>();
                while (allowedReqPaths.size() < maxAllowedReqs) {
                    final int rnd = random.nextInt(1, numReqs + 1);
                    final String reqPath = REQ_PATH + "?async&" + reqMethod + "=" + rnd;
                    allowedReqPaths.add(reqPath);
                }
                // configure the approver
                final OnlyAllowSpecificPaths reqApprover = new OnlyAllowSpecificPaths(allowedReqPaths);
                server.setRequestApprover(reqApprover::allowNewRequest);
                try {
                    final List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                    for (int i = 1; i <= numReqs; i++) {
                        final URI reqURI = new URI(REQ_URI_BASE + "?async&" + reqMethod + "=" + i);
                        final HttpRequest req = HttpRequest.newBuilder()
                                .uri(reqURI)
                                .method(reqMethod, HttpRequest.BodyPublishers.noBody())
                                .build();
                        System.out.println("initiating request " + req);
                        final Future<HttpResponse<String>> f = client.sendAsync(req, BodyHandlers.ofString());
                        futures.add(f);
                    }
                    // wait for responses
                    int numFailed = 0;
                    int numSuccess = 0;
                    for (int i = 1; i <= numReqs; i++) {
                        try {
                            final HttpResponse<String> resp = futures.get(i - 1).get();
                            numSuccess++;
                            final String respBody = resp.body();
                            System.out.println("request: " + resp.request()
                                    + ", received response: " + respBody);
                            assertEquals(200, resp.statusCode(),
                                    "unexpected status code for request " + resp.request());
                        } catch (ExecutionException ee) {
                            final Throwable cause = ee.getCause();
                            if (!(cause instanceof IOException ioe)) {
                                throw cause;
                            }
                            // verify it failed for the right reason
                            if (ioe.getMessage() == null
                                    || !ioe.getMessage().contains("request not processed by peer")) {
                                // propagate the original failure
                                throw ioe;
                            }
                            numFailed++; // failed due to the right reason
                            System.out.println("received expected failure: " + ioe
                                    + ", for request "
                                    + REQ_URI_BASE + "?async&" + reqMethod + "=" + i);
                        }
                    }
                    // verify the correct number of requests succeeded/failed
                    assertEquals(maxAllowedReqs, numSuccess, "unexpected number of requests succeeded");
                    assertEquals((numReqs - maxAllowedReqs), numFailed, "unexpected number of requests failed");
                } finally {
                    server.setRequestApprover(null); // reset
                }
            }
        }
    }

    // only allows requests with certain paths to be processed, irrespective of which
    // server connection is serving them. requests which don't match the allowed
    // paths will not be processed and a GOAWAY frame will be sent.
    private static final class OnlyAllowSpecificPaths {
        private final Set<String> allowedReqPaths;

        private OnlyAllowSpecificPaths(final Set<String> allowedReqPaths) {
            this.allowedReqPaths = Set.copyOf(allowedReqPaths);
        }

        public boolean allowNewRequest(final Http2TestServerConnection serverConn,
                                       final String reqPath) {
            if (allowedReqPaths.contains(reqPath)) {
                // allowed
                return true;
            }
            System.out.println("sending GOAWAY on server connection " + serverConn
                    + " for request: " + reqPath);
            try {
                serverConn.sendGoAway(ErrorFrame.NO_ERROR);
            } catch (IOException e) {
                System.err.println("Failed to send GOAWAY on server connection: "
                        + serverConn + ", request: " + reqPath + ", due to: " + e);
                e.printStackTrace();
            }
            return false;
        }
    }

    // allows a certain number of requests per server connection, before sending a GOAWAY
    // for any subsequent requests on that connection
    private static final class RequestApprover {
        private static final int MAX_REQS_PER_CONN = 6;
        private final Map<Http2TestServerConnection, AtomicInteger> numApproved =
                new ConcurrentHashMap<>();
        private final Map<Http2TestServerConnection, AtomicInteger> numDisapproved =
                new ConcurrentHashMap<>();

        public boolean allowNewRequest(final Http2TestServerConnection serverConn,
                                       final String reqPath) {
            final AtomicInteger approved = numApproved.computeIfAbsent(serverConn,
                    (k) -> new AtomicInteger());
            int curr = approved.get();
            while (curr < MAX_REQS_PER_CONN) {
                if (approved.compareAndSet(curr, curr + 1)) {
                    return true; // new request allowed
                }
                curr = approved.get();
            }
            final AtomicInteger disapproved = numDisapproved.computeIfAbsent(serverConn,
                    (k) -> new AtomicInteger());
            final int numUnprocessed = disapproved.incrementAndGet();
            System.out.println(approved.get() + " processed, "
                    + numUnprocessed + " unprocessed requests so far," +
                    " sending GOAWAY on connection " + serverConn);
            try {
                serverConn.sendGoAway(ErrorFrame.NO_ERROR);
            } catch (IOException e) {
                System.err.println("Failed to send GOAWAY on server connection: "
                        + serverConn + ", due to: " + e);
                e.printStackTrace();
            }
            return false;
        }
    }

    private static final class Handler implements Http2Handler {

        @Override
        public void handle(final Http2TestExchange exchange) throws IOException {
            final String connectionKey = exchange.getConnectionKey();
            System.out.println(connectionKey + " responding to request: "
                    + exchange.getRequestURI());
            final byte[] response = connectionKey.getBytes(UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (final OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
}
