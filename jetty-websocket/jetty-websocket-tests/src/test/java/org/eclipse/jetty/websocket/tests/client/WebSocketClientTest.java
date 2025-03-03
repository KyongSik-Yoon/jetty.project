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

package org.eclipse.jetty.websocket.tests.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.tests.AnnoMaxMessageEndpoint;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.ConnectMessageEndpoint;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.ParamsEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WebSocketClientTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        NativeWebSocketServletContainerInitializer.configure(context,
            (servletContext, configuration) ->
            {
                configuration.getPolicy().setIdleTimeout(10000);
                configuration.addMapping("/echo", (req, resp) ->
                {
                    if (req.hasSubProtocol("echo"))
                        resp.setAcceptedSubProtocol("echo");
                    return new EchoSocket();
                });
                configuration.addMapping("/anno-max-message", (req, resp) -> new AnnoMaxMessageEndpoint());
                configuration.addMapping("/connect-msg", (req, resp) -> new ConnectMessageEndpoint());
                configuration.addMapping("/get-params", (req, resp) -> new ParamsEndpoint());
            });

        context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        server.setHandler(context);

        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAddExtension_NotInstalled() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        request.addExtensions("x-bad");

        assertThrows(IllegalArgumentException.class, () ->
        {
            // Should trigger failure on bad extension
            client.connect(cliSock, wsUri, request);
        });
    }

    @Test
    public void testBasicEcho_FromClient() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(30, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            Collection<WebSocketSession> sessions = client.getOpenSessions();
            assertThat("client.sessions.size", sessions.size(), is(1));

            RemoteEndpoint remote = cliSock.getSession().getRemote();
            remote.sendString("Hello World!");

            // wait for response from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Hello World"));
        }
    }

    @Test
    public void testBasicEcho_UsingCallback() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            Collection<WebSocketSession> sessions = client.getOpenSessions();
            assertThat("client.sessions.size", sessions.size(), is(1));

            FutureWriteCallback callback = new FutureWriteCallback();

            cliSock.getSession().getRemote().sendString("Hello World!", callback);
            callback.get(5, TimeUnit.SECONDS);

            // wait for response from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Hello World"));
        }
    }

    @Test
    public void testBasicEcho_FromServer() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/connect-msg"));
        Future<Session> future = client.connect(cliSock, wsUri);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            // Validate connect
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            // wait for message from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Greeting from onConnect"));
        }
    }

    @Test
    public void testLocalRemoteAddress() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            Assertions.assertTrue(cliSock.openLatch.await(1, TimeUnit.SECONDS));

            InetSocketAddress local = cliSock.getSession().getLocalAddress();
            InetSocketAddress remote = cliSock.getSession().getRemoteAddress();

            assertThat("Local Socket Address", local, notNullValue());
            assertThat("Remote Socket Address", remote, notNullValue());

            // Hard to validate (in a portable unit test) the local address that was used/bound in the low level Jetty Endpoint
            assertThat("Local Socket Address / Host", local.getAddress().getHostAddress(), notNullValue());
            assertThat("Local Socket Address / Port", local.getPort(), greaterThan(0));

            String uriHostAddress = InetAddress.getByName(wsUri.getHost()).getHostAddress();
            assertThat("Remote Socket Address / Host", remote.getAddress().getHostAddress(), is(uriHostAddress));
            assertThat("Remote Socket Address / Port", remote.getPort(), greaterThan(0));
        }
    }

    /**
     * Ensure that <code>@WebSocket(maxTextMessageSize = 100*1024)</code> behaves as expected.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testMaxMessageSize() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.getPolicy().setMaxTextMessageSize(100 * 1024);
        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/anno-max-message"));
        Future<Session> future = client.connect(cliSock, wsUri);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));

            // Create string that is larger than default size of 64k
            // but smaller than maxMessageSize of 100k
            int size = 80 * 1024;
            byte[] buf = new byte[size];
            Arrays.fill(buf, (byte)'x');
            String msg = StringUtil.toUTF8String(buf, 0, buf.length);

            sess.getRemote().sendString(msg);

            // wait for message from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received.length(), is(size));
        }
    }

    @Test
    public void testParameterMap() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.getPolicy().setMaxTextMessageSize(100 * 1024);
        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/get-params?snack=cashews&amount=handful&brand=off"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            UpgradeRequest req = sess.getUpgradeRequest();
            assertThat("Upgrade Request", req, notNullValue());

            Map<String, List<String>> parameterMap = req.getParameterMap();
            assertThat("Parameter Map", parameterMap, notNullValue());

            assertThat("Parameter[snack]", parameterMap.get("snack"), is(Arrays.asList(new String[]{"cashews"})));
            assertThat("Parameter[amount]", parameterMap.get("amount"), is(Arrays.asList(new String[]{"handful"})));
            assertThat("Parameter[brand]", parameterMap.get("brand"), is(Arrays.asList(new String[]{"off"})));

            assertThat("Parameter[cost]", parameterMap.get("cost"), nullValue());

            // wait for message from server indicating what it sees
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Parameter[snack]", received, containsString("Params[snack]=[cashews]"));
            assertThat("Parameter[amount]", received, containsString("Params[amount]=[handful]"));
            assertThat("Parameter[brand]", received, containsString("Params[brand]=[off]"));
            assertThat("Parameter[cost]", received, not(containsString("Params[cost]=")));
        }
    }
}
