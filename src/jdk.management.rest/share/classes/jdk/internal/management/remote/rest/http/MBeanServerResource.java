/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package jdk.internal.management.remote.rest.http;

import jdk.internal.management.remote.rest.json.JSONElement;
import jdk.internal.management.remote.rest.mapper.JSONMapper;
import jdk.internal.management.remote.rest.mapper.JSONMappingException;
import jdk.internal.management.remote.rest.mapper.JSONMappingFactory;
import com.sun.jmx.remote.security.JMXPluggableAuthenticator;
import com.sun.jmx.remote.security.JMXSubjectDomainCombiner;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerDelegateMBean;
import javax.management.remote.JMXAuthenticator;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorServer;
import jdk.internal.management.remote.rest.JMXRestAdapter;
import jdk.internal.management.remote.rest.PlatformRestAdapter;

import javax.security.auth.Subject;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * An MBeanServerResoure implements the Rest Adapter.
 * Created by PlatformRestAdapter.
 */
public final class MBeanServerResource implements RestResource, JMXRestAdapter {

    // Initialization parameters
    private final String connectionId;
    private final HttpServer httpServer;
    private final String contextStr;
    private final Map<String, ?> env;
    private final MBeanServer mbeanServer;

    // Save the context to start/stop the adapter
    private HttpContext httpContext;
    private JMXAuthenticator authenticator = null;
    private final MBeanServerDelegateMBean mBeanServerDelegateMBean;

    // Save MBeanServer Proxy for a user
    private final MBeanCollectionResource defaultMBeansResource;
    // Use an expiring map that removes entries after the configured period lapses.
    private final TimedMap<String, MBeanCollectionResource> proxyMBeanServers = new TimedMap<>(5*60);

    private static AtomicInteger resourceNumber = new AtomicInteger(1);
    private boolean started = false;

    private static AtomicInteger id = new AtomicInteger(0);

    public MBeanServerResource(JMXConnectorServer connServer, HttpServer hServer, MBeanServer mbeanServer,
                               String context, Map<String, ?> env) {

        this.httpServer = hServer;
        this.env = env;
        this.mbeanServer = mbeanServer;
        this.connectionId = makeConnectionId(hServer, context);

        mBeanServerDelegateMBean = JMX.newMBeanProxy(mbeanServer,
                MBeanServerDelegate.DELEGATE_NAME, MBeanServerDelegateMBean.class);

        if (context == null || context.isEmpty()) {
            contextStr = "server-" + resourceNumber.getAndIncrement();
        } else {
            contextStr = context;
        }

        // setup authentication
        authenticator = (JMXAuthenticator) env.get("jmx.remote.authenticator");
        System.err.println("ZZZZZZZZZZZZZ MBeanServerResource authenticator = " + authenticator);

        if (env.get("jmx.remote.x.authentication") != null) {
            authenticator = (JMXAuthenticator) env.get("jmx.remote.authenticator");
            if (authenticator == null) {
                if (env.get("jmx.remote.x.password.file") != null
                        || env.get("jmx.remote.x.login.config") != null) {
                    authenticator = new JMXPluggableAuthenticator(env);
                } else {
                    throw new IllegalArgumentException
                            ("Config error : Authentication is enabled with no authenticator");
                }
            }
        }

        if (env.get("jmx.remote.x.authentication") == null) {
            defaultMBeansResource = new MBeanCollectionResource(mbeanServer);
        } else {
            defaultMBeansResource = null;
        }
    }

    private String makeConnectionId(HttpServer hServer, String context) {
        // Format is specified in java.management/javax/management/remote package summary.                                                             
        // RMIConnectionIdTest expects protocol://ip port username arbitrary
        StringBuilder s = new StringBuilder(); 
        s.append("http").append(":");
        String a = hServer.getAddress().toString();
        a = a.substring(0, a.indexOf(":"));
        s.append(a);
        s.append(" ;").append(id.incrementAndGet());  
        return s.toString();

    }

    public String getConnectionId() throws IOException {
        return connectionId;
    }

    private MBeanServer getMBeanServerProxy(MBeanServer mbeaServer, Subject subject) {
        return (MBeanServer) Proxy.newProxyInstance(MBeanServer.class.getClassLoader(),
                new Class<?>[]{MBeanServer.class},
                new AuthInvocationHandler(mbeaServer, subject));
    }

    private JMXConnector connector;

    public void setConnector(JMXConnector c) {
        this.connector = c;
    }

    @Override
    public HttpResponse doGet(HttpExchange exchange) {
        String selfUrl = getUrl();
        Map<String, String> links = new LinkedHashMap<>();
        links.put("mbeans", selfUrl + "/mbeans");

        Map<String, Object> mBeanServerInfo = getMBeanServerInfo();
        mBeanServerInfo.put("_links", links);

        final JSONMapper typeMapper = JSONMappingFactory.INSTANCE.getTypeMapper(mBeanServerInfo);
        if (typeMapper != null) {
            try {
                JSONElement jsonElement = typeMapper.toJsonValue(mBeanServerInfo);
                return new HttpResponse(jsonElement.toJsonString());
            } catch (JSONMappingException e) {
                return HttpResponse.SERVER_ERROR;
            }
        } else {
            return HttpResponse.SERVER_ERROR;
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        MBeanCollectionResource mBeansResource = defaultMBeansResource;
        if (env.get("jmx.remote.x.authentication") != null) {
            String authCredentials = HttpUtil.getCredentials(exchange);
            // MBeanServer proxy should be populated in the authenticator
            mBeansResource = proxyMBeanServers.get(authCredentials);
            if (mBeansResource == null) {
                throw new IllegalArgumentException("Invalid HTTP request Headers");
            }
        }

        String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8.displayName());
        String pathPrefix = httpContext.getPath();

        // Route request to appropriate resource
        if (path.matches(pathPrefix + "/?$")) {
            RestResource.super.handle(exchange);
        } else if (path.matches(pathPrefix + "/mbeans.*")) { // startsWith XXXX
            mBeansResource.handle(exchange);
        } else {
            HttpUtil.sendResponse(exchange, HttpResponse.REQUEST_NOT_FOUND);
        }
    }

    @Override
    public synchronized void start() {
        if (!started) {
            try {
            httpContext = httpServer.createContext("/jmx/servers/" + contextStr, this);

            if (env.get("jmx.remote.x.authentication") != null) {
                httpContext.setAuthenticator(new RestAuthenticator("jmx-rest"));
            }
            } catch (IllegalArgumentException iae) {
                System.err.println("XXXX MBeanServerResource.started context = /jmx/servers/" + contextStr + " gets: " + iae);
            }
            System.err.println("XXXX MBeanServerResource.started context = /jmx/servers/" + contextStr);
            started = true;
        }
    }

    @Override
    public synchronized void stop() {
        if (!started) {
            throw new IllegalStateException("Rest Adapter not started yet");
        }
        httpServer.removeContext(httpContext);
        started = false;
    }

    @Override
    public String getUrl() {
        return PlatformRestAdapter.getBaseURL() + "/" + contextStr;
    }

    @Override
    public MBeanServer getMBeanServer() {
        return mbeanServer;
    }

    public String getContext() {
        return contextStr;
    }

    private class RestAuthenticator extends BasicAuthenticator {

        RestAuthenticator(String realm) {
            super(realm);
        }

        @Override
        public boolean checkCredentials(String username, String password) {
            if (proxyMBeanServers.containsKey(username)) {
                return true;
            } else {
                Subject subject = null;
                if (authenticator != null) {
                    String[] credential = new String[]{username, password};
                    try {
                        subject = authenticator.authenticate(credential);
                    } catch (SecurityException e) {
                        return false;
                    }
                }
                MBeanServer proxy = getMBeanServerProxy(mbeanServer, subject);
                proxyMBeanServers.put(username, new MBeanCollectionResource(proxy));
                return true;
            }
        }
    }

    Map<String, Object> getMBeanServerInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("id", mBeanServerDelegateMBean.getMBeanServerId());
        result.put("connectionId", connectionId);
        result.put("context", contextStr);

        result.put("defaultDomain", mbeanServer.getDefaultDomain());
        result.put("mBeanCount", mbeanServer.getMBeanCount());

        result.put("domains", mbeanServer.getDomains());
        // Do not use Arrays.toString(domains) as that flattens array to one String,
        // without quoting the elements. Let caller's Mapper handle it.

        result.put("specName", mBeanServerDelegateMBean.getSpecificationName());
        result.put("specVersion", mBeanServerDelegateMBean.getSpecificationVersion());
        result.put("specVendor", mBeanServerDelegateMBean.getSpecificationVendor());

        result.put("implName", mBeanServerDelegateMBean.getImplementationName());
        result.put("implVersion", mBeanServerDelegateMBean.getImplementationVersion());
        result.put("implVendor", mBeanServerDelegateMBean.getImplementationVendor());

        return result;
    }

    private class AuthInvocationHandler implements InvocationHandler {

        private final MBeanServer mbeanServer;
        @SuppressWarnings("removal")
        private final AccessControlContext acc;

        AuthInvocationHandler(MBeanServer server, Subject subject) {
            this.mbeanServer = server;
            if (subject == null) {
                this.acc = null;
            } else {
                acc = JMXSubjectDomainCombiner.getContext(subject);
            }
        }

        @Override
        @SuppressWarnings("removal")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (acc == null) {
                return method.invoke(mbeanServer, args);
            } else {
                PrivilegedAction<Object> op = () -> {
                    try {
                        return method.invoke(mbeanServer, args);
                    } catch (Exception ex) {
                    }
                    return null;
                };
                return AccessController.doPrivileged(op, acc);
            }
        }
    }

    /*
    This is an expiring map that removes entries after the configured time period lapses.
    This is required to re-authenticate the user after the timeout.
     */
    private class TimedMap<K,V> {

        private ConcurrentHashMap<K,TimeStampedValue<V>>  permanentMap;
        private long timeout = Long.MAX_VALUE;   // Timeout in seconds

        private class TimeStampedValue<T> {
            private final T value;
            private final long insertTimeStamp;

            TimeStampedValue(T value) {
                this.value = value;
                insertTimeStamp = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
            }
        }

        public TimedMap(int seconds) {
            this.timeout = seconds;
            permanentMap = new ConcurrentHashMap<>();
        }

        public boolean containsKey(K key) {
            return permanentMap.containsKey(key);
        }

        public V get(K key) {
            TimeStampedValue<V> vTimeStampedValue = permanentMap.get(key);
            long current = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
            if(current - vTimeStampedValue.insertTimeStamp > timeout) {
                permanentMap.remove(key);
                return null;
            } else {
                return vTimeStampedValue.value;
            }
        }

        public void put(K key, V value) {
            permanentMap.put(key, new TimeStampedValue<>(value));
        }
    }
}
