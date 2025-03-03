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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class RequestTest
{
    private static final Logger LOG = Log.getLogger(RequestTest.class);
    private Server _server;
    private LocalConnector _connector;
    private RequestHandler _handler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        http.getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());
        _connector = new LocalConnector(_server, http);
        _server.addConnector(_connector);
        _connector.setIdleTimeout(500);
        _handler = new RequestHandler();
        _server.setHandler(_handler);

        ErrorHandler errors = new ErrorHandler();
        errors.setServer(_server);
        errors.setShowStacks(true);
        _server.addBean(errors);
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testParamExtraction() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    // do the parse
                    request.getParameterMap();
                    return false;
                }
                catch (BadMessageException e)
                {
                    // Should be able to retrieve the raw query
                    String rawQuery = request.getQueryString();
                    return rawQuery.equals("param=aaa%ZZbbb&other=value");
                }
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "GET /?param=aaa%ZZbbb&other=value HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testParamExtraction_BadSequence() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                request.getParameterMap();
                // should have thrown a BadMessageException
                return false;
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "GET /?test_%e0%x8%81=missing HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertThat("Responses", responses, startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testParamExtraction_Timeout() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                request.getParameterMap();
                // should have thrown a BadMessageException
                return false;
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\n" +
            "Connection: close\n" +
            "Content-Length: 100\n" +
            "\n" +
            "name=value";

        LocalEndPoint endp = _connector.connect();
        endp.addInput(request);

        String response = BufferUtil.toString(endp.waitForResponse(false, 1, TimeUnit.SECONDS));
        assertThat("Responses", response, startsWith("HTTP/1.1 500"));
    }

    @Test
    public void testEmptyHeaders() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                assertNotNull(request.getLocale());
                assertTrue(request.getLocales().hasMoreElements()); // Default locale
                assertEquals("", request.getContentType());
                assertNull(request.getCharacterEncoding());
                assertEquals(0, request.getQueryString().length());
                assertEquals(-1, request.getContentLength());
                assertNull(request.getCookies());
                assertEquals("", request.getHeader("Name"));
                assertTrue(request.getHeaders("Name").hasMoreElements()); // empty
                try
                {
                    request.getDateHeader("Name");
                    assertTrue(false);
                }
                catch (IllegalArgumentException e)
                {

                }
                assertEquals(-1, request.getDateHeader("Other"));
                return true;
            }
        };

        String request = "GET /? HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Connection: close\n" +
            "Content-Type: \n" +
            "Accept-Language: \n" +
            "Cookie: \n" +
            "Name: \n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testMultiPartNoConfig() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    request.getPart("stuff");
                    return false;
                }
                catch (IllegalStateException e)
                {
                    //expected exception because no multipart config is set up
                    assertTrue(e.getMessage().startsWith("No multipart config"));
                    return true;
                }
                catch (Exception e)
                {
                    return false;
                }
            }
        };

        String multipart = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"\r\n" +
            "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
            "\r\n" +
            "000000000000000000000000000000000000000000000000000\r\n" +
            "--AaB03x--\r\n";

        String request = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        String responses = _connector.getResponse(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testLocale() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertThat(request.getLocale().getLanguage(), is("da"));
                Enumeration<Locale> locales = request.getLocales();
                Locale locale = locales.nextElement();
                assertThat(locale.getLanguage(), is("da"));
                assertThat(locale.getCountry(), is(""));
                locale = locales.nextElement();
                assertThat(locale.getLanguage(), is("en"));
                assertThat(locale.getCountry(), is("AU"));
                locale = locales.nextElement();
                assertThat(locale.getLanguage(), is("en"));
                assertThat(locale.getCountry(), is("GB"));
                locale = locales.nextElement();
                assertThat(locale.getLanguage(), is("en"));
                assertThat(locale.getCountry(), is(""));
                assertFalse(locales.hasMoreElements());
                return true;
            }
        };

        String request = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Connection: close\r\n" +
            "Accept-Language: da, en-gb;q=0.8, en;q=0.7\r\n" +
            "Accept-Language: XX;q=0, en-au;q=0.9\r\n" +
            "\r\n";
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testMultiPart() throws Exception
    {
        final File testTmpDir = File.createTempFile("reqtest", null);
        if (testTmpDir.exists())
            testTmpDir.delete();
        testTmpDir.mkdir();
        testTmpDir.deleteOnExit();
        assertTrue(testTmpDir.list().length == 0);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new MultiPartRequestHandler(testTmpDir));
        contextHandler.addEventListener(new MultiPartCleanerListener()
        {

            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                MultiParts m = (MultiParts)sre.getServletRequest().getAttribute(Request.MULTIPARTS);
                assertNotNull(m);
                ContextHandler.Context c = m.getContext();
                assertNotNull(c);
                assertTrue(c == sre.getServletContext());
                assertTrue(!m.isEmpty());
                assertTrue(testTmpDir.list().length == 2);
                super.requestDestroyed(sre);
                String[] files = testTmpDir.list();
                assertTrue(files.length == 0);
            }
        });
        _server.stop();
        _server.setHandler(contextHandler);
        _server.start();

        String multipart = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"foo.upload\"\r\n" +
            "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
            "\r\n" +
            "000000000000000000000000000000000000000000000000000\r\n" +
            "--AaB03x--\r\n";

        String request = "GET /foo/x.html HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        String responses = _connector.getResponse(request);
        //System.err.println(responses);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testUtilMultiPart() throws Exception
    {
        final File testTmpDir = File.createTempFile("reqtest", null);
        if (testTmpDir.exists())
            testTmpDir.delete();
        testTmpDir.mkdir();
        testTmpDir.deleteOnExit();
        assertTrue(testTmpDir.list().length == 0);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new MultiPartRequestHandler(testTmpDir));
        contextHandler.addEventListener(new MultiPartCleanerListener()
        {

            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                MultiParts m = (MultiParts)sre.getServletRequest().getAttribute(Request.MULTIPARTS);
                assertNotNull(m);
                ContextHandler.Context c = m.getContext();
                assertNotNull(c);
                assertTrue(c == sre.getServletContext());
                assertTrue(!m.isEmpty());
                assertTrue(testTmpDir.list().length == 2);
                super.requestDestroyed(sre);
                String[] files = testTmpDir.list();
                assertTrue(files.length == 0);
            }
        });
        _server.stop();
        _server.setHandler(contextHandler);
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setMultiPartFormDataCompliance(MultiPartFormDataCompliance.LEGACY);
        _server.start();

        String multipart = "      --AaB03x\r" +
            "content-disposition: form-data; name=\"field1\"\r" +
            "\r" +
            "Joe Blow\r" +
            "--AaB03x\r" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"foo.upload\"\r" +
            "Content-Type: text/plain;charset=ISO-8859-1\r" +
            "\r" +
            "000000000000000000000000000000000000000000000000000\r" +
            "--AaB03x--\r";

        String request = "GET /foo/x.html HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        String responses = _connector.getResponse(request);
        //System.err.println(responses);
        assertThat(responses, Matchers.startsWith("HTTP/1.1 200"));
        assertThat(responses, Matchers.containsString("Violation: CR_LINE_TERMINATION"));
        assertThat(responses, Matchers.containsString("Violation: NO_CRLF_AFTER_PREAMBLE"));
    }

    @Test
    public void testHttpMultiPart() throws Exception
    {
        final File testTmpDir = File.createTempFile("reqtest", null);
        if (testTmpDir.exists())
            testTmpDir.delete();
        testTmpDir.mkdir();
        testTmpDir.deleteOnExit();
        assertTrue(testTmpDir.list().length == 0);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new MultiPartRequestHandler(testTmpDir));

        _server.stop();
        _server.setHandler(contextHandler);
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setMultiPartFormDataCompliance(MultiPartFormDataCompliance.RFC7578);
        _server.start();

        String multipart = "      --AaB03x\r" +
            "content-disposition: form-data; name=\"field1\"\r" +
            "\r" +
            "Joe Blow\r" +
            "--AaB03x\r" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"foo.upload\"\r" +
            "Content-Type: text/plain;charset=ISO-8859-1\r" +
            "\r" +
            "000000000000000000000000000000000000000000000000000\r" +
            "--AaB03x--\r";

        String request = "GET /foo/x.html HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        String responses = _connector.getResponse(request);
        //System.err.println(responses);
        assertThat(responses, Matchers.startsWith("HTTP/1.1 500"));
    }

    @Test
    public void testBadMultiPart() throws Exception
    {
        //a bad multipart where one of the fields has no name
        final File testTmpDir = File.createTempFile("badmptest", null);
        if (testTmpDir.exists())
            testTmpDir.delete();
        testTmpDir.mkdir();
        testTmpDir.deleteOnExit();
        assertTrue(testTmpDir.list().length == 0);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new BadMultiPartRequestHandler(testTmpDir));
        contextHandler.addEventListener(new MultiPartCleanerListener()
        {

            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                MultiParts m = (MultiParts)sre.getServletRequest().getAttribute(Request.MULTIPARTS);
                assertNotNull(m);
                ContextHandler.Context c = m.getContext();
                assertNotNull(c);
                assertTrue(c == sre.getServletContext());
                super.requestDestroyed(sre);
                String[] files = testTmpDir.list();
                assertTrue(files.length == 0);
            }
        });
        _server.stop();
        _server.setHandler(contextHandler);
        _server.start();

        String multipart = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"xxx\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data;  filename=\"foo.upload\"\r\n" +
            "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
            "\r\n" +
            "000000000000000000000000000000000000000000000000000\r\n" +
            "--AaB03x--\r\n";

        String request = "GET /foo/x.html HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            String responses = _connector.getResponse(request);
            //System.err.println(responses);
            assertTrue(responses.startsWith("HTTP/1.1 500"));
        }
    }

    @Test
    public void testBadUtf8ParamExtraction() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    // This throws an exception if attempted
                    request.getParameter("param");
                    return false;
                }
                catch (BadMessageException e)
                {
                    // Should still be able to get the raw query.
                    String rawQuery = request.getQueryString();
                    return rawQuery.equals("param=aaa%E7bbb");
                }
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "GET /?param=aaa%E7bbb HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        LOG.info("Expecting NotUtf8Exception in state 36...");
        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testEncodedParamExtraction() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    // This throws an exception if attempted
                    request.getParameter("param");
                    return false;
                }
                catch (BadMessageException e)
                {
                    return e.getCode() == 501;
                }
            }
        };

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded; charset=utf-8\n" +
            "Content-Length: 10\n" +
            "Content-Encoding: gzip\n" +
            "Connection: close\n" +
            "\n" +
            "0123456789\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testIdentityParamExtraction() throws Exception
    {
        _handler._checker = (request, response) -> "bar".equals(request.getParameter("foo"));

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded; charset=utf-8\n" +
            "Content-Length: 7\n" +
            "Content-Encoding: identity\n" +
            "Connection: close\n" +
            "\n" +
            "foo=bar\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testEncodedNotParams() throws Exception
    {
        _handler._checker = (request, response) -> request.getParameter("param") == null;

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/octet-stream\n" +
            "Content-Length: 10\n" +
            "Content-Encoding: gzip\n" +
            "Connection: close\n" +
            "\n" +
            "0123456789\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testInvalidHostHeader() throws Exception
    {
        // Use a contextHandler with vhosts to force call to Request.getServerName()
        ContextHandler context = new ContextHandler();
        context.addVirtualHosts(new String[]{"something"});
        _server.stop();
        _server.setHandler(context);
        _server.start();

        // Request with illegal Host header
        String request = "GET / HTTP/1.1\n" +
            "Host: whatever.com:xxxx\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, Matchers.startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testContentTypeEncoding() throws Exception
    {
        final ArrayList<String> results = new ArrayList<>();
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                results.add(request.getContentType());
                results.add(request.getCharacterEncoding());
                return true;
            }
        };

        LocalEndPoint endp = _connector.executeRequest(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/test\n" +
                "\n" +

                "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/html;charset=utf8\n" +
                "\n" +

                "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/html; charset=\"utf8\"\n" +
                "\n" +

                "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/html; other=foo ; blah=\"charset=wrong;\" ; charset =   \" x=z; \"   ; more=values \n" +
                "Connection: close\n" +
                "\n"
        );

        endp.getResponse();
        endp.getResponse();
        endp.getResponse();
        endp.getResponse();

        int i = 0;
        assertEquals("text/test", results.get(i++));
        assertEquals(null, results.get(i++));

        assertEquals("text/html;charset=utf8", results.get(i++));
        assertEquals("utf-8", results.get(i++));

        assertEquals("text/html; charset=\"utf8\"", results.get(i++));
        assertEquals("utf-8", results.get(i++));

        assertTrue(results.get(i++).startsWith("text/html"));
        assertEquals(" x=z; ", results.get(i++));
    }

    @Test
    public void testHostPort() throws Exception
    {
        final ArrayList<String> results = new ArrayList<>();
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                results.add(request.getRequestURL().toString());
                results.add(request.getRemoteAddr());
                results.add(request.getServerName());
                results.add(String.valueOf(request.getServerPort()));
                return true;
            }
        };

        results.clear();
        String response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: myhost\n" +
                "Connection: close\n" +
                "\n");
        int i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("80", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: myhost:8888\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("8888", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET http://myhost:8888/ HTTP/1.0\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("8888", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET http://myhost:8888/ HTTP/1.1\n" +
                "Host: wrong:666\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("8888", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: 1.2.3.4\n" +
                "Connection: close\n" +
                "\n");
        i = 0;

        assertThat(response, containsString("200 OK"));
        assertEquals("http://1.2.3.4/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("1.2.3.4", results.get(i++));
        assertEquals("80", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: 1.2.3.4:8888\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://1.2.3.4:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("1.2.3.4", results.get(i++));
        assertEquals("8888", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://[::1]/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("80", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]:8888\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://[::1]:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("8888", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]\n" +
                "x-forwarded-for: remote\n" +
                "x-forwarded-proto: https\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("https://[::1]/", results.get(i++));
        assertEquals("remote", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("443", results.get(i++));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]:8888\n" +
                "Connection: close\n" +
                "x-forwarded-for: remote\n" +
                "x-forwarded-proto: https\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("https://[::1]:8888/", results.get(i++));
        assertEquals("remote", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("8888", results.get(i++));
    }

    @Test
    public void testContent() throws Exception
    {
        final AtomicInteger length = new AtomicInteger();

        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                int len = request.getContentLength();
                ServletInputStream in = request.getInputStream();
                for (int i = 0; i < len; i++)
                {
                    int b = in.read();
                    if (b < 0)
                        return false;
                }
                if (in.read() > 0)
                    return false;

                length.set(len);
                return true;
            }
        };

        String content = "";

        for (int l = 0; l < 1024; l++)
        {
            String request = "POST / HTTP/1.1\r\n" +
                "Host: whatever\r\n" +
                "Content-Type: multipart/form-data-test\r\n" +
                "Content-Length: " + l + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                content;
            Log.getRootLogger().debug("test l={}", l);
            String response = _connector.getResponse(request);
            Log.getRootLogger().debug(response);
            assertThat(response, containsString(" 200 OK"));
            assertEquals(l, length.get());
            content += "x";
        }
    }

    @Test
    public void testEncodedForm() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String actual = request.getParameter("name2");
                return "test2".equals(actual);
            }
        };

        String content = "name1=test&name2=test2&name3=&name4=test";
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testEncodedFormUnknownMethod() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                return request.getParameter("name1") == null && request.getParameter("name2") == null && request.getParameter("name3") == null;
            }
        };

        String content = "name1=test&name2=test2&name3=&name4=test";
        String request = "UNKNOWN / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testEncodedFormExtraMethod() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String actual = request.getParameter("name2");
                return "test2".equals(actual);
            }
        };

        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().addFormEncodedMethod("Extra");
        String content = "name1=test&name2=test2&name3=&name4=test";
        String request = "EXTRA / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void test8859EncodedForm() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Should be "testä"
                // "test" followed by a LATIN SMALL LETTER A WITH DIAERESIS
                request.setCharacterEncoding(StandardCharsets.ISO_8859_1.name());
                String actual = request.getParameter("name2");
                return "test\u00e4".equals(actual);
            }
        };

        String content = "name1=test&name2=test%E4&name3=&name4=test";
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testUTF8EncodedForm() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // http://www.ltg.ed.ac.uk/~richard/utf-8.cgi?input=00e4&mode=hex
                // Should be "testä"
                // "test" followed by a LATIN SMALL LETTER A WITH DIAERESIS
                String actual = request.getParameter("name2");
                return "test\u00e4".equals(actual);
            }
        };

        String content = "name1=test&name2=test%C3%A4&name3=&name4=test";
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    @Disabled("See issue #1175")
    public void testMultiPartFormDataReadInputThenParams() throws Exception
    {
        final File tmpdir = MavenTestingUtils.getTargetTestingDir("multipart");
        FS.ensureEmpty(tmpdir);

        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
            {
                if (baseRequest.getDispatcherType() != DispatcherType.REQUEST)
                    return;

                // Fake a @MultiPartConfig'd servlet endpoint
                MultipartConfigElement multipartConfig = new MultipartConfigElement(tmpdir.getAbsolutePath());
                request.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, multipartConfig);

                // Normal processing
                baseRequest.setHandled(true);

                // Fake the commons-fileupload behavior
                int length = request.getContentLength();
                InputStream in = request.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                IO.copy(in, out, length); // KEY STEP (Don't Change!) commons-fileupload does not read to EOF

                // Record what happened as servlet response headers
                response.setIntHeader("x-request-content-length", request.getContentLength());
                response.setIntHeader("x-request-content-read", out.size());
                String foo = request.getParameter("foo"); // uri query parameter
                String bar = request.getParameter("bar"); // form-data content parameter
                response.setHeader("x-foo", foo == null ? "null" : foo);
                response.setHeader("x-bar", bar == null ? "null" : bar);
            }
        };

        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String multipart = "--AaBbCc\r\n" +
            "content-disposition: form-data; name=\"bar\"\r\n" +
            "\r\n" +
            "BarContent\r\n" +
            "--AaBbCc\r\n" +
            "content-disposition: form-data; name=\"stuff\"\r\n" +
            "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
            "\r\n" +
            "000000000000000000000000000000000000000000000000000\r\n" +
            "--AaBbCc--\r\n";

        String request = "POST /?foo=FooUri HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaBbCc\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        // It should always be possible to read query string
        assertThat("response.x-foo", response.get("x-foo"), is("FooUri"));
        // Not possible to read request content parameters?
        assertThat("response.x-bar", response.get("x-bar"), is("null")); // TODO: should this work?
    }

    @Test
    public void testPartialRead() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
            {
                baseRequest.setHandled(true);
                Reader reader = request.getReader();
                byte[] b = ("read=" + reader.read() + "\n").getBytes(StandardCharsets.UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String requests = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "\r\n" +
            "0123456789\r\n" +
            "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "ABCDEFGHIJ\r\n";

        LocalEndPoint endp = _connector.executeRequest(requests);
        String responses = endp.getResponse() + endp.getResponse();

        int index = responses.indexOf("read=" + (int)'0');
        assertTrue(index > 0);

        index = responses.indexOf("read=" + (int)'A', index + 7);
        assertTrue(index > 0);
    }

    @Test
    public void testQueryAfterRead()
        throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
            {
                baseRequest.setHandled(true);
                Reader reader = request.getReader();
                String in = IO.toString(reader);
                String param = request.getParameter("param");

                byte[] b = ("read='" + in + "' param=" + param + "\n").getBytes(StandardCharsets.UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String request = "POST /?param=right HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: " + 11 + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "param=wrong\r\n";

        String responses = _connector.getResponse(request);

        assertTrue(responses.indexOf("read='param=wrong' param=right") > 0);
    }

    @Test
    public void testSessionAfterRedirect() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
            {
                baseRequest.setHandled(true);
                response.sendRedirect("/foo");
                try
                {
                    request.getSession(true);
                    fail("Session should not be created after response committed");
                }
                catch (IllegalStateException e)
                {
                    //expected
                }
                catch (Exception e)
                {
                    fail("Session creation after response commit should throw IllegalStateException");
                }
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();
        String response = _connector.getResponse("GET / HTTP/1.1\n" +
            "Host: myhost\n" +
            "Connection: close\n" +
            "\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("Location: http://myhost/foo"));
    }

    @Test
    public void testPartialInput() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
            {
                baseRequest.setHandled(true);
                InputStream in = request.getInputStream();
                byte[] b = ("read=" + in.read() + "\n").getBytes(StandardCharsets.UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String requests = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "\r\n" +
            "0123456789\r\n" +
            "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "ABCDEFGHIJ\r\n";

        LocalEndPoint endp = _connector.executeRequest(requests);
        String responses = endp.getResponse() + endp.getResponse();

        int index = responses.indexOf("read=" + (int)'0');
        assertTrue(index > 0);

        index = responses.indexOf("read=" + (int)'A', index + 7);
        assertTrue(index > 0);
    }

    @Test
    public void testConnectionClose() throws Exception
    {
        String response;

        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "\n",
            200, TimeUnit.MILLISECONDS
        );
        assertThat(response, containsString("200"));
        assertThat(response, not(containsString("Connection: close")));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: close\n" +
                "\n"
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: Other, close\n" +
                "\n"
        );

        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.0\n" +
                "Host: whatever\n" +
                "\n"
        );
        assertThat(response, containsString("200"));
        assertThat(response, not(containsString("Connection: close")));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.0\n" +
                "Host: whatever\n" +
                "Connection: Other, close\n" +
                "\n"
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.0\n" +
                "Host: whatever\n" +
                "Connection: Other,,keep-alive\n" +
                "\n",
            200, TimeUnit.MILLISECONDS
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: keep-alive"));
        assertThat(response, containsString("Hello World"));

        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setHeader("Connection", "TE");
                response.addHeader("Connection", "Other");
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "\n",
            200, TimeUnit.MILLISECONDS
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: TE"));
        assertThat(response, containsString("Connection: Other"));

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: close\n" +
                "\n"
        );
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("Hello World"));
    }

    @Test
    public void testCookies() throws Exception
    {
        final ArrayList<Cookie> cookies = new ArrayList<>();

        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                javax.servlet.http.Cookie[] ca = request.getCookies();
                if (ca != null)
                    cookies.addAll(Arrays.asList(ca));
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        String response;

        cookies.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(0, cookies.size());

        cookies.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Cookie: name=quoted=\"\\\"badly\\\"\"\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("quoted=\"\\\"badly\\\"\"", cookies.get(0).getValue());

        cookies.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(2, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        cookies.clear();
        LocalEndPoint endp = _connector.executeRequest(
            "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n" +
                "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "Connection: close\n" +
                "\n"
        );
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));

        assertEquals(4, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        assertSame(cookies.get(0), cookies.get(2));
        assertSame(cookies.get(1), cookies.get(3));

        cookies.clear();
        endp = _connector.executeRequest(
            "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n" +
                "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"othervalue\"\n" +
                "Connection: close\n" +
                "\n"
        );
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        assertEquals(4, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        assertNotSame(cookies.get(0), cookies.get(2));
        assertNotSame(cookies.get(1), cookies.get(3));

        cookies.clear();
        response = _connector.getResponse(
            "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: __utmz=14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1, cookies.size());
        assertEquals("__utmz", cookies.get(0).getName());
        assertEquals("14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html", cookies.get(0).getValue());
    }

    @Test
    public void testBadCookies() throws Exception
    {
        final ArrayList<Cookie> cookies = new ArrayList<>();

        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                javax.servlet.http.Cookie[] ca = request.getCookies();
                if (ca != null)
                    cookies.addAll(Arrays.asList(ca));
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        String response;

        cookies.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Cookie: Path=value\n" +
                "Cookie: name=value\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
    }

    @Disabled("No longer relevant")
    @Test
    public void testCookieLeak() throws Exception
    {
        final String[] cookie = new String[10];

        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                for (int i = 0; i < cookie.length; i++)
                {
                    cookie[i] = null;
                }

                Cookie[] cookies = request.getCookies();
                for (int i = 0; cookies != null && i < cookies.length; i++)
                {
                    cookie[i] = cookies[i].getValue();
                }
                return true;
            }
        };

        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: other=cookie\r\n" +
            "\r\n"
            +
            "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        _connector.getResponse(request);

        assertEquals("value", cookie[0]);
        assertEquals(null, cookie[1]);

        request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "\r\n"
            +
            "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: \r\n" +
            "Connection: close\r\n" +
            "\r\n";

        _connector.getResponse(request);
        assertEquals(null, cookie[0]);
        assertEquals(null, cookie[1]);

        request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "Cookie: other=cookie\r\n" +
            "\r\n"
            +
            "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "Cookie:\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        _connector.getResponse(request);

        assertEquals("value", cookie[0]);
        assertEquals(null, cookie[1]);
    }

    @Test
    public void testHashDOSKeys() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            // Expecting maxFormKeys limit and Closing HttpParser exceptions...
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", -1);
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormKeys", 1000);

            StringBuilder buf = new StringBuilder(4000000);
            buf.append("a=b");

            // The evil keys file is not distributed - as it is dangerous
            File evil_keys = new File("/tmp/keys_mapping_to_zero_2m");
            if (evil_keys.exists())
            {
                // Using real evil keys!
                try (BufferedReader in = new BufferedReader(new FileReader(evil_keys)))
                {
                    String key = null;
                    while ((key = in.readLine()) != null)
                    {
                        buf.append("&").append(key).append("=").append("x");
                    }
                }
            }
            else
            {
                // we will just create a lot of keys and make sure the limit is applied
                for (int i = 0; i < 2000; i++)
                {
                    buf.append("&").append("K").append(i).append("=").append("x");
                }
            }
            buf.append("&c=d");

            _handler._checker = new RequestTester()
            {
                @Override
                public boolean check(HttpServletRequest request, HttpServletResponse response)
                {
                    return "b".equals(request.getParameter("a")) && request.getParameter("c") == null;
                }
            };

            String request = "POST / HTTP/1.1\r\n" +
                "Host: whatever\r\n" +
                "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
                "Content-Length: " + buf.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                buf;

            long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            String rawResponse = _connector.getResponse(request);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response.status", response.getStatus(), is(400));
            assertThat("Response body content", response.getContent(), containsString(BadMessageException.class.getName()));
            assertThat("Response body content", response.getContent(), containsString(IllegalStateException.class.getName()));
            long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertTrue((now - start) < 5000);
        }
    }

    @Test
    public void testHashDOSSize() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("Expecting maxFormSize limit and too much data exceptions...");
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", 3396);
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormKeys", 1000);

            StringBuilder buf = new StringBuilder(4000000);
            buf.append("a=b");
            // we will just create a lot of keys and make sure the limit is applied
            for (int i = 0; i < 500; i++)
            {
                buf.append("&").append("K").append(i).append("=").append("x");
            }
            buf.append("&c=d");

            _handler._checker = new RequestTester()
            {
                @Override
                public boolean check(HttpServletRequest request, HttpServletResponse response)
                {
                    return "b".equals(request.getParameter("a")) && request.getParameter("c") == null;
                }
            };

            String request = "POST / HTTP/1.1\r\n" +
                "Host: whatever\r\n" +
                "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
                "Content-Length: " + buf.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                buf;

            long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            String rawResponse = _connector.getResponse(request);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response.status", response.getStatus(), is(400));
            assertThat("Response body content", response.getContent(), containsString(BadMessageException.class.getName()));
            assertThat("Response body content", response.getContent(), containsString(IllegalStateException.class.getName()));
            long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertTrue((now - start) < 5000);
        }
    }

    @Test
    public void testNotSupportedCharacterEncoding() throws UnsupportedEncodingException
    {
        Request request = new Request(null, null);
        assertThrows(UnsupportedEncodingException.class, () -> request.setCharacterEncoding("doesNotExist"));
    }

    @Test
    public void testGetterSafeFromNullPointerException()
    {
        Request request = new Request(null, null);

        assertNull(request.getAuthType());
        assertNull(request.getAuthentication());

        assertNull(request.getContentType());

        assertNull(request.getCookies());
        assertNull(request.getContext());
        assertNull(request.getContextPath());

        assertNull(request.getHttpFields());
        assertNull(request.getHttpURI());

        assertNotNull(request.getScheme());
        assertNotNull(request.getServerName());
        assertNotNull(request.getServerPort());

        assertNotNull(request.getAttributeNames());
        assertFalse(request.getAttributeNames().hasMoreElements());

        request.getParameterMap();
        assertNull(request.getQueryString());
        assertNotNull(request.getQueryParameters());
        assertEquals(0, request.getQueryParameters().size());
        assertNotNull(request.getParameterMap());
        assertEquals(0, request.getParameterMap().size());
    }

    interface RequestTester
    {
        boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    private class RequestHandler extends AbstractHandler.ErrorDispatchHandler
    {
        private RequestTester _checker;
        @SuppressWarnings("unused")
        private String _content;

        @Override
        protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);

            if (request.getContentLength() > 0
                && !request.getContentType().startsWith(MimeTypes.Type.FORM_ENCODED.asString())
                && !request.getContentType().startsWith("multipart/form-data"))
                _content = IO.toString(request.getInputStream());

            if (_checker != null && _checker.check(request, response))
                response.setStatus(200);
            else
                response.sendError(500);
        }
    }

    private class MultiPartRequestHandler extends AbstractHandler
    {
        File tmpDir;

        public MultiPartRequestHandler(File tmpDir)
        {
            this.tmpDir = tmpDir;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);
            try
            {

                MultipartConfigElement mpce = new MultipartConfigElement(tmpDir.getAbsolutePath(), -1, -1, 2);
                request.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, mpce);

                String field1 = request.getParameter("field1");
                assertNotNull(field1);

                Part foo = request.getPart("stuff");
                assertNotNull(foo);
                assertTrue(foo.getSize() > 0);
                response.setStatus(200);
                List<String> violations = (List<String>)request.getAttribute(HttpCompliance.VIOLATIONS_ATTR);
                if (violations != null)
                {
                    for (String v : violations)
                    {
                        response.addHeader("Violation", v);
                    }
                }
            }
            catch (IllegalStateException e)
            {
                //expected exception because no multipart config is set up
                assertTrue(e.getMessage().startsWith("No multipart config"));
                response.setStatus(200);
            }
            catch (Exception e)
            {
                response.sendError(500);
            }
        }
    }

    private class BadMultiPartRequestHandler extends AbstractHandler
    {
        File tmpDir;

        public BadMultiPartRequestHandler(File tmpDir)
        {
            this.tmpDir = tmpDir;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);
            try
            {

                MultipartConfigElement mpce = new MultipartConfigElement(tmpDir.getAbsolutePath(), -1, -1, 2);
                request.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, mpce);

                //We should get an error when we getParams if there was a problem parsing the multipart
                request.getPart("xxx");
                //A 200 response is actually wrong here
            }
            catch (RuntimeException e)
            {
                response.sendError(500);
            }
        }
    }
}
