package com.github.liyibo1110.jdk.java.net;

import jdk.internal.access.JavaNetURLAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.VM;
import sun.net.util.IPAddressUtil;
import sun.security.action.GetPropertyAction;
import sun.security.util.SecurityConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.net.UnknownHostException;
import java.net.spi.URLStreamHandlerProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * URL不仅表示地址长什么样，它是一个：可以告诉我去哪儿，并且可能真的帮我连过去的地址对象，还隐含着：
 * 1、这个协议应该怎么处理。
 * 2、怎么建立连接。
 * 3、怎么读取资源。
 * URL也有一些和URI类似的字段，但还有协议handler的概念：就是不同的协议，URL可能会找到对应的处理器，去做：
 * 1、打开连接。
 * 2、读取内容。
 * 3、构造URLConnection。
 * URL的设计重点不是：怎么解析得更优雅。
 * URL的设计重点是：如何让不同协议的资源都能通过统一入口访问。
 * @author liyibo
 * @date 2026-03-31 16:48
 */
public class URL implements Serializable {
    static final String BUILTIN_HANDLERS_PREFIX = "sun.net.www.protocol";

    @java.io.Serial
    static final long serialVersionUID = -7627629688361524110L;

    private static final String protocolPathProp = "java.protocol.handler.pkgs";

    private String protocol;

    private String host;

    private int port = -1;

    private String file;

    private transient String query;

    private String authority;

    private transient String path;

    private transient String userInfo;

    private String ref;

    private transient InetAddress hostAddress;

    transient URLStreamHandler handler;

    private int hashCode = -1;

    private transient UrlDeserializedState tempState;

    public URL(String protocol, String host, int port, String file) throws MalformedURLException {
        this(protocol, host, port, file, null);
    }

    public URL(String protocol, String host, String file) throws MalformedURLException {
        this(protocol, host, -1, file);
    }

    public URL(String protocol, String host, int port, String file, URLStreamHandler handler) throws MalformedURLException {
        if (handler != null) {
            @SuppressWarnings("removal")
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                // check for permission to specify a handler
                checkSpecifyHandler(sm);
            }
        }

        protocol = toLowerCase(protocol);
        this.protocol = protocol;
        if (host != null) {
            /**
             * if host is a literal IPv6 address,
             * we will make it conform to RFC 2732
             */
            if (host.indexOf(':') >= 0 && !host.startsWith("["))
                host = "["+host+"]";

            this.host = host;

            if (port < -1)
                throw new MalformedURLException("Invalid port number :" + port);

            this.port = port;
            authority = (port == -1) ? host : host + ":" + port;
        }

        int index = file.indexOf('#');
        this.ref = index < 0 ? null : file.substring(index + 1);
        file = index < 0 ? file : file.substring(0, index);
        int q = file.lastIndexOf('?');
        if (q != -1) {
            this.query = file.substring(q + 1);
            this.path = file.substring(0, q);
            this.file = path + "?" + query;
        } else {
            this.path = file;
            this.file = path;
        }

        // Note: we don't do full validation of the URL here. Too risky to change
        // right now, but worth considering for future reference. -br
        if (handler == null && (handler = getURLStreamHandler(protocol)) == null) {
            throw new MalformedURLException("unknown protocol: " + protocol);
        }
        this.handler = handler;
        if (host != null && isBuiltinStreamHandler(handler)) {
            String s = IPAddressUtil.checkExternalForm(this);
            if (s != null)
                throw new MalformedURLException(s);
        }
        if ("jar".equalsIgnoreCase(protocol)) {
            if (handler instanceof sun.net.www.protocol.jar.Handler) {
                // URL.openConnection() would throw a confusing exception
                // so generate a better exception here instead.
                String s = ((sun.net.www.protocol.jar.Handler) handler).checkNestedProtocol(file);
                if (s != null)
                    throw new MalformedURLException(s);
            }
        }
    }

    public URL(String spec) throws MalformedURLException {
        this(null, spec);
    }

    public URL(URL context, String spec) throws MalformedURLException {
        this(context, spec, null);
    }

    public URL(URL context, String spec, URLStreamHandler handler) throws MalformedURLException {
        String original = spec;
        int i, limit, c;
        int start = 0;
        String newProtocol = null;
        boolean aRef=false;
        boolean isRelative = false;

        // Check for permission to specify a handler
        if (handler != null) {
            @SuppressWarnings("removal")
            SecurityManager sm = System.getSecurityManager();
            if (sm != null)
                checkSpecifyHandler(sm);
        }

        try {
            limit = spec.length();
            while ((limit > 0) && (spec.charAt(limit - 1) <= ' '))
                limit--;        //eliminate trailing whitespace

            while ((start < limit) && (spec.charAt(start) <= ' '))
                start++;        // eliminate leading whitespace

            if (spec.regionMatches(true, start, "url:", 0, 4))
                start += 4;

            if (start < spec.length() && spec.charAt(start) == '#') {
                /* we're assuming this is a ref relative to the context URL.
                 * This means protocols cannot start w/ '#', but we must parse
                 * ref URL's like: "hello:there" w/ a ':' in them.
                 */
                aRef=true;
            }
            for (i = start ; !aRef && (i < limit) && ((c = spec.charAt(i)) != '/') ; i++) {
                if (c == ':') {
                    String s = toLowerCase(spec.substring(start, i));
                    if (isValidProtocol(s)) {
                        newProtocol = s;
                        start = i + 1;
                    }
                    break;
                }
            }

            // Only use our context if the protocols match.
            protocol = newProtocol;
            if ((context != null) && ((newProtocol == null) || newProtocol.equalsIgnoreCase(context.protocol))) {
                // inherit the protocol handler from the context
                // if not specified to the constructor
                if (handler == null)
                    handler = context.handler;

                // If the context is a hierarchical URL scheme and the spec
                // contains a matching scheme then maintain backwards
                // compatibility and treat it as if the spec didn't contain
                // the scheme; see 5.2.3 of RFC2396
                if (context.path != null && context.path.startsWith("/"))
                    newProtocol = null;

                if (newProtocol == null) {
                    protocol = context.protocol;
                    authority = context.authority;
                    userInfo = context.userInfo;
                    host = context.host;
                    port = context.port;
                    file = context.file;
                    path = context.path;
                    isRelative = true;
                }
            }

            if (protocol == null)
                throw new MalformedURLException("no protocol: "+original);

            // Get the protocol handler if not specified or the protocol
            // of the context could not be used
            if (handler == null && (handler = getURLStreamHandler(protocol)) == null)
                throw new MalformedURLException("unknown protocol: "+protocol);

            this.handler = handler;

            i = spec.indexOf('#', start);
            if (i >= 0) {
                ref = spec.substring(i + 1, limit);
                limit = i;
            }

            /*
             * Handle special case inheritance of query and fragment
             * implied by RFC2396 section 5.2.2.
             */
            if (isRelative && start == limit) {
                query = context.query;
                if (ref == null)
                    ref = context.ref;
            }

            handler.parseURL(this, spec, start, limit);

        } catch(MalformedURLException e) {
            throw e;
        } catch(Exception e) {
            MalformedURLException exception = new MalformedURLException(e.getMessage());
            exception.initCause(e);
            throw exception;
        }
    }

    static URL fromURI(URI uri) throws MalformedURLException {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("URI is not absolute");

        String protocol = uri.getScheme();

        // In general we need to go via Handler.parseURL, but for the jrt
        // protocol we enforce that the Handler is not overrideable and can
        // optimize URI to URL conversion.
        //
        // Case-sensitive comparison for performance; malformed protocols will
        // be handled correctly by the slow path.
        if (protocol.equals("jrt") && !uri.isOpaque() && uri.getRawFragment() == null) {

            String query = uri.getRawQuery();
            String path = uri.getRawPath();
            String file = (query == null) ? path : path + "?" + query;

            // URL represent undefined host as empty string while URI use null
            String host = uri.getHost();
            if (host == null)
                host = "";

            int port = uri.getPort();

            return new java.net.URL("jrt", host, port, file, null);
        } else {
            return new java.net.URL((java.net.URL)null, uri.toString(), null);
        }
    }

    private boolean isValidProtocol(String protocol) {
        int len = protocol.length();
        if (len < 1)
            return false;
        char c = protocol.charAt(0);
        if (!Character.isLetter(c))
            return false;
        for (int i = 1; i < len; i++) {
            c = protocol.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '+' && c != '-')
                return false;
        }
        return true;
    }

    private void checkSpecifyHandler(@SuppressWarnings("removal") SecurityManager sm) {
        sm.checkPermission(SecurityConstants.SPECIFY_HANDLER_PERMISSION);
    }

    void set(String protocol, String host, int port,
             String authority, String userInfo, String path,
             String query, String ref) {
        synchronized (this) {
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.file = query == null ? path : path + "?" + query;
            this.userInfo = userInfo;
            this.path = path;
            this.ref = ref;
            /* This is very important. We must recompute this after the
             * URL has been changed. */
            hashCode = -1;
            hostAddress = null;
            this.query = query;
            this.authority = authority;
        }
    }

    synchronized InetAddress getHostAddress() {
        if (hostAddress != null)
            return hostAddress;

        if (host == null || host.isEmpty())
            return null;

        try {
            hostAddress = InetAddress.getByName(host);
        } catch (UnknownHostException | SecurityException ex) {
            return null;
        }
        return hostAddress;
    }

    public String getQuery() {
        return query;
    }

    public String getPath() {
        return path;
    }

    public String getUserInfo() {
        return userInfo;
    }

    public String getAuthority() {
        return authority;
    }

    public int getPort() {
        return port;
    }

    public int getDefaultPort() {
        return handler.getDefaultPort();
    }

    public String getProtocol() {
        return protocol;
    }

    public String getHost() {
        return host;
    }

    public String getFile() {
        return file;
    }

    public String getRef() {
        return ref;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof URL u2))
            return false;
        return handler.equals(this, u2);
    }

    public synchronized int hashCode() {
        if (hashCode != -1)
            return hashCode;
        hashCode = handler.hashCode(this);
        return hashCode;
    }

    public boolean sameFile(URL other) {
        return handler.sameFile(this, other);
    }

    public String toString() {
        return toExternalForm();
    }

    public String toExternalForm() {
        return handler.toExternalForm(this);
    }

    public URI toURI() throws URISyntaxException {
        URI uri = new URI(toString());
        if (authority != null && isBuiltinStreamHandler(handler)) {
            String s = IPAddressUtil.checkAuthority(this);
            if (s != null)
                throw new URISyntaxException(authority, s);
        }
        return uri;
    }

    public URLConnection openConnection() throws IOException {
        return handler.openConnection(this);
    }

    public URLConnection openConnection(Proxy proxy) throws IOException {
        if (proxy == null)
            throw new IllegalArgumentException("proxy can not be null");

        // Create a copy of Proxy as a security measure
        Proxy p = proxy == Proxy.NO_PROXY ? Proxy.NO_PROXY : sun.net.ApplicationProxy.create(proxy);
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (p.type() != Proxy.Type.DIRECT && sm != null) {
            InetSocketAddress epoint = (InetSocketAddress) p.address();
            if (epoint.isUnresolved())
                sm.checkConnect(epoint.getHostName(), epoint.getPort());
            else
                sm.checkConnect(epoint.getAddress().getHostAddress(), epoint.getPort());
        }
        return handler.openConnection(this, p);
    }

    public final InputStream openStream() throws IOException {
        return openConnection().getInputStream();
    }

    public final Object getContent() throws IOException {
        return openConnection().getContent();
    }

    public final Object getContent(Class<?>[] classes) throws IOException {
        return openConnection().getContent(classes);
    }

    private static volatile URLStreamHandlerFactory factory;

    public static void setURLStreamHandlerFactory(URLStreamHandlerFactory fac) {
        synchronized (streamHandlerLock) {
            if (factory != null)
                throw new Error("factory already defined");

            @SuppressWarnings("removal")
            SecurityManager security = System.getSecurityManager();
            if (security != null)
                security.checkSetFactory();

            handlers.clear();

            // safe publication of URLStreamHandlerFactory with volatile write
            factory = fac;
        }
    }

    private static final URLStreamHandlerFactory defaultFactory = new DefaultFactory();

    private static class DefaultFactory implements URLStreamHandlerFactory {
        private static String PREFIX = "sun.net.www.protocol.";

        public URLStreamHandler createURLStreamHandler(String protocol) {
            // Avoid using reflection during bootstrap
            switch (protocol) {
                case "file":
                    return new sun.net.www.protocol.file.Handler();
                case "jar":
                    return new sun.net.www.protocol.jar.Handler();
                case "jrt":
                    return new sun.net.www.protocol.jrt.Handler();
            }
            String name = PREFIX + protocol + ".Handler";
            try {
                Object o = Class.forName(name).getDeclaredConstructor().newInstance();
                return (URLStreamHandler)o;
            } catch (Exception e) {
                // For compatibility, all Exceptions are ignored.
                // any number of exceptions can get thrown here
            }
            return null;
        }
    }

    private static URLStreamHandler lookupViaProperty(String protocol) {
        String packagePrefixList = GetPropertyAction.privilegedGetProperty(protocolPathProp);
        if (packagePrefixList == null) {
            // not set
            return null;
        }

        String[] packagePrefixes = packagePrefixList.split("\\|");
        URLStreamHandler handler = null;
        for (int i=0; handler == null && i<packagePrefixes.length; i++) {
            String packagePrefix = packagePrefixes[i].trim();
            try {
                String clsName = packagePrefix + "." + protocol + ".Handler";
                Class<?> cls = null;
                try {
                    cls = Class.forName(clsName);
                } catch (ClassNotFoundException e) {
                    ClassLoader cl = ClassLoader.getSystemClassLoader();
                    if (cl != null) {
                        cls = cl.loadClass(clsName);
                    }
                }
                if (cls != null) {
                    @SuppressWarnings("deprecation")
                    Object tmp = cls.newInstance();
                    handler = (URLStreamHandler)tmp;
                }
            } catch (Exception e) {
                // any number of exceptions can get thrown here
            }
        }
        return handler;
    }

    private static Iterator<URLStreamHandlerProvider> providers() {
        return new Iterator<>() {
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            ServiceLoader<URLStreamHandlerProvider> sl = ServiceLoader.load(URLStreamHandlerProvider.class, cl);
            Iterator<URLStreamHandlerProvider> i = sl.iterator();
            URLStreamHandlerProvider next = null;

            private boolean getNext() {
                while (next == null) {
                    try {
                        if (!i.hasNext())
                            return false;
                        next = i.next();
                    } catch (ServiceConfigurationError sce) {
                        if (sce.getCause() instanceof SecurityException) {
                            // Ignore security exceptions
                            continue;
                        }
                        throw sce;
                    }
                }
                return true;
            }

            public boolean hasNext() {
                return getNext();
            }

            public URLStreamHandlerProvider next() {
                if (!getNext())
                    throw new NoSuchElementException();
                URLStreamHandlerProvider n = next;
                next = null;
                return n;
            }
        };
    }

    // Thread-local gate to prevent recursive provider lookups
    private static ThreadLocal<Object> gate = new ThreadLocal<>();

    @SuppressWarnings("removal")
    private static URLStreamHandler lookupViaProviders(final String protocol) {
        if (gate.get() != null)
            throw new Error("Circular loading of URL stream handler providers detected");

        gate.set(gate);
        try {
            return AccessController.doPrivileged(
                    new PrivilegedAction<>() {
                        public URLStreamHandler run() {
                            Iterator<URLStreamHandlerProvider> itr = providers();
                            while (itr.hasNext()) {
                                URLStreamHandlerProvider f = itr.next();
                                URLStreamHandler h = f.createURLStreamHandler(protocol);
                                if (h != null)
                                    return h;
                            }
                            return null;
                        }
                    });
        } finally {
            gate.set(null);
        }
    }

    static String toLowerCase(String protocol) {
        if (protocol.equals("jrt") || protocol.equals("file") || protocol.equals("jar"))
            return protocol;
        else
            return protocol.toLowerCase(Locale.ROOT);
    }

    static boolean isOverrideable(String protocol) {
        if (protocol.length() == 3) {
            if ((Character.toLowerCase(protocol.charAt(0)) == 'j') &&
                    (Character.toLowerCase(protocol.charAt(1)) == 'r') &&
                    (Character.toLowerCase(protocol.charAt(2)) == 't')) {
                return false;
            }
        } else if (protocol.length() == 4) {
            if ((Character.toLowerCase(protocol.charAt(0)) == 'f') &&
                    (Character.toLowerCase(protocol.charAt(1)) == 'i') &&
                    (Character.toLowerCase(protocol.charAt(2)) == 'l') &&
                    (Character.toLowerCase(protocol.charAt(3)) == 'e')) {
                return false;
            }
        }
        return true;
    }

    /**
     * A table of protocol handlers.
     */
    static Hashtable<String,URLStreamHandler> handlers = new Hashtable<>();
    private static final Object streamHandlerLock = new Object();

    static URLStreamHandler getURLStreamHandler(String protocol) {

        URLStreamHandler handler = handlers.get(protocol);

        if (handler != null)
            return handler;

        URLStreamHandlerFactory fac;
        boolean checkedWithFactory = false;
        boolean overrideableProtocol = isOverrideable(protocol);

        if (overrideableProtocol && VM.isBooted()) {
            // Use the factory (if any). Volatile read makes
            // URLStreamHandlerFactory appear fully initialized to current thread.
            fac = factory;
            if (fac != null) {
                handler = fac.createURLStreamHandler(protocol);
                checkedWithFactory = true;
            }

            if (handler == null && !protocol.equalsIgnoreCase("jar"))
                handler = lookupViaProviders(protocol);

            if (handler == null)
                handler = lookupViaProperty(protocol);
        }

        if (handler == null) {
            // Try the built-in protocol handler
            handler = defaultFactory.createURLStreamHandler(protocol);
        }

        synchronized (streamHandlerLock) {
            URLStreamHandler handler2 = null;

            // Check again with hashtable just in case another
            // thread created a handler since we last checked
            handler2 = handlers.get(protocol);

            if (handler2 != null) {
                return handler2;
            }

            // Check with factory if another thread set a
            // factory since our last check
            if (overrideableProtocol && !checkedWithFactory &&
                    (fac = factory) != null) {
                handler2 = fac.createURLStreamHandler(protocol);
            }

            if (handler2 != null) {
                // The handler from the factory must be given more
                // importance. Discard the default handler that
                // this thread created.
                handler = handler2;
            }

            // Insert this handler into the hashtable
            if (handler != null) {
                handlers.put(protocol, handler);
            }
        }
        return handler;
    }

    @java.io.Serial
    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("protocol", String.class),
            new ObjectStreamField("host", String.class),
            new ObjectStreamField("port", int.class),
            new ObjectStreamField("authority", String.class),
            new ObjectStreamField("file", String.class),
            new ObjectStreamField("ref", String.class),
            new ObjectStreamField("hashCode", int.class), };

    @java.io.Serial
    private synchronized void writeObject(java.io.ObjectOutputStream s) throws IOException {
        s.defaultWriteObject(); // write the fields
    }

    @java.io.Serial
    private synchronized void readObject(java.io.ObjectInputStream s) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField gf = s.readFields();
        String protocol = (String)gf.get("protocol", null);
        if (getURLStreamHandler(protocol) == null)
            throw new IOException("unknown protocol: " + protocol);

        String host = (String)gf.get("host", null);
        int port = gf.get("port", -1);
        String authority = (String)gf.get("authority", null);
        String file = (String)gf.get("file", null);
        String ref = (String)gf.get("ref", null);
        int hashCode = gf.get("hashCode", -1);
        if (authority == null && ((host != null && !host.isEmpty()) || port != -1)) {
            if (host == null)
                host = "";
            authority = (port == -1) ? host : host + ":" + port;
        }
        tempState = new java.net.UrlDeserializedState(protocol, host, port, authority, file, ref, hashCode);
    }

    @java.io.Serial
    private Object readResolve() throws ObjectStreamException {
        URLStreamHandler handler = null;
        // already been checked in readObject
        handler = getURLStreamHandler(tempState.getProtocol());

        URL replacementURL = null;
        if (isBuiltinStreamHandler(handler.getClass().getName()))
            replacementURL = fabricateNewURL();
        else
            replacementURL = setDeserializedFields(handler);

        return replacementURL;
    }

    private URL setDeserializedFields(URLStreamHandler handler) {
        java.net.URL replacementURL;
        String userInfo = null;
        String protocol = tempState.getProtocol();
        String host = tempState.getHost();
        int port = tempState.getPort();
        String authority = tempState.getAuthority();
        String file = tempState.getFile();
        String ref = tempState.getRef();
        int hashCode = tempState.getHashCode();

        // Construct authority part
        if (authority == null && ((host != null && !host.isEmpty()) || port != -1)) {
            if (host == null)
                host = "";
            authority = (port == -1) ? host : host + ":" + port;

            // Handle hosts with userInfo in them
            int at = host.lastIndexOf('@');
            if (at != -1) {
                userInfo = host.substring(0, at);
                host = host.substring(at+1);
            }
        } else if (authority != null) {
            // Construct user info part
            int ind = authority.indexOf('@');
            if (ind != -1)
                userInfo = authority.substring(0, ind);
        }

        // Construct path and query part
        String path = null;
        String query = null;
        if (file != null) {
            // Fix: only do this if hierarchical?
            int q = file.lastIndexOf('?');
            if (q != -1) {
                query = file.substring(q+1);
                path = file.substring(0, q);
            } else
                path = file;
        }

        // Set the object fields.
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.file = file;
        this.authority = authority;
        this.ref = ref;
        this.hashCode = hashCode;
        this.handler = handler;
        this.query = query;
        this.path = path;
        this.userInfo = userInfo;
        replacementURL = this;
        return replacementURL;
    }

    private URL fabricateNewURL() throws InvalidObjectException {
        // create URL string from deserialized object
        URL replacementURL = null;
        String urlString = tempState.reconstituteUrlString();

        try {
            replacementURL = new URL(urlString);
        } catch (MalformedURLException mEx) {
            resetState();
            InvalidObjectException invoEx = new InvalidObjectException("Malformed URL:  " + urlString);
            invoEx.initCause(mEx);
            throw invoEx;
        }
        replacementURL.setSerializedHashCode(tempState.getHashCode());
        resetState();
        return replacementURL;
    }

    boolean isBuiltinStreamHandler(URLStreamHandler handler) {
        Class<?> handlerClass = handler.getClass();
        return isBuiltinStreamHandler(handlerClass.getName()) || VM.isSystemDomainLoader(handlerClass.getClassLoader());
    }

    private boolean isBuiltinStreamHandler(String handlerClassName) {
        return (handlerClassName.startsWith(BUILTIN_HANDLERS_PREFIX));
    }

    private void resetState() {
        this.protocol = null;
        this.host = null;
        this.port = -1;
        this.file = null;
        this.authority = null;
        this.ref = null;
        this.hashCode = -1;
        this.handler = null;
        this.query = null;
        this.path = null;
        this.userInfo = null;
        this.tempState = null;
    }

    private void setSerializedHashCode(int hc) {
        this.hashCode = hc;
    }

    static {
        SharedSecrets.setJavaNetURLAccess(
                new JavaNetURLAccess() {
                    @Override
                    public URLStreamHandler getHandler(java.net.URL u) {
                        return u.handler;
                    }
                }
        );
    }
}

final class UrlDeserializedState {
    private final String protocol;
    private final String host;
    private final int port;
    private final String authority;
    private final String file;
    private final String ref;
    private final int hashCode;

    public UrlDeserializedState(String protocol,
                                String host, int port,
                                String authority, String file,
                                String ref, int hashCode) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.authority = authority;
        this.file = file;
        this.ref = ref;
        this.hashCode = hashCode;
    }

    String getProtocol() {
        return protocol;
    }

    String getHost() {
        return host;
    }

    String getAuthority () {
        return authority;
    }

    int getPort() {
        return port;
    }

    String getFile () {
        return file;
    }

    String getRef () {
        return ref;
    }

    int getHashCode () {
        return hashCode;
    }

    String reconstituteUrlString() {

        // pre-compute length of StringBuffer
        int len = protocol.length() + 1;
        if (authority != null && !authority.isEmpty())
            len += 2 + authority.length();
        if (file != null) {
            len += file.length();
        }
        if (ref != null)
            len += 1 + ref.length();
        StringBuilder result = new StringBuilder(len);
        result.append(protocol);
        result.append(":");
        if (authority != null && !authority.isEmpty()) {
            result.append("//");
            result.append(authority);
        }
        if (file != null) {
            result.append(file);
        }
        if (ref != null) {
            result.append("#");
            result.append(ref);
        }
        return result.toString();
    }
}
