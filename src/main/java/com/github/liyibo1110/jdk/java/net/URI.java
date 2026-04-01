package com.github.liyibo1110.jdk.java.net;

import jdk.internal.access.JavaNetUriAccess;
import jdk.internal.access.SharedSecrets;
import sun.nio.cs.UTF_8;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;

/**
 * 本质就是一个结构化的字符串，主要做三件事：
 * 1、把一个URI字符串解析成几个部分。
 * 2、按照规则校验这些部分是否合法。
 * 3、提供标准化、拼接、相对路径解析等字符串级操作。
 *
 * URI是什么：统一资源标识符，例如https://example.com:8080/a/b?x=1#top
 * - scheme：https
 * - host：example.com
 * - port：8080
 * - path：/a/b
 * - query：x=1
 * - fragment：top
 * 即scheme://userInfo@host:port/path?query#fragment
 * 偏抽象、偏语法、偏字符串
 *
 * URL是什么：更偏向可访问资源的位置
 * 偏访问、偏协议、偏资源定位
 * @author liyibo
 * @date 2026-03-31 15:47
 */
public final class URI implements Comparable<URI>, Serializable {
    @java.io.Serial
    static final long serialVersionUID = -6052424284110960213L;

    // Components of all URIs: [<scheme>:]<scheme-specific-part>[#<fragment>]
    private transient String scheme;

    private transient String fragment;

    // Hierarchical URI components: [//<authority>]<path>[?<query>]
    private transient String authority;

    // Server-based authority: [<userInfo>@]<host>[:<port>]
    private transient String userInfo;

    private transient String host;

    private transient int port = -1;

    private transient String path;

    private transient String query;

    private transient String schemeSpecificPart;

    private transient int hash;

    private transient String decodedUserInfo;
    private transient String decodedAuthority;
    private transient String decodedPath;
    private transient String decodedQuery;
    private transient String decodedFragment;
    private transient String decodedSchemeSpecificPart;

    /** 完成的URI */
    private volatile String string;

    private URI() {}

    public URI(String str) throws URISyntaxException {
        new Parser(str).parse(false);
    }

    public URI(String scheme,
               String userInfo, String host, int port,
               String path, String query, String fragment) throws URISyntaxException {
        String s = toString(scheme, null, null, userInfo, host, port, path, query, fragment);
        checkPath(s, scheme, path);
        new Parser(s).parse(true);
    }

    public URI(String scheme, String authority,
               String path, String query, String fragment) throws URISyntaxException {
        String s = toString(scheme, null, authority, null, null, -1, path, query, fragment);
        checkPath(s, scheme, path);
        new Parser(s).parse(false);
    }

    public URI(String scheme, String host, String path, String fragment) throws URISyntaxException {
        this(scheme, null, host, -1, path, null, fragment);
    }

    public URI(String scheme, String ssp, String fragment) throws URISyntaxException {
        new Parser(toString(scheme, ssp, null, null, null, -1,
                null, null, fragment)).parse(false);
    }

    URI(String scheme, String path) {
        assert validSchemeAndPath(scheme, path);
        this.scheme = scheme;
        this.path = path;
    }

    private static boolean validSchemeAndPath(String scheme, String path) {
        try {
            URI u = new URI(scheme + ":" + path);
            return scheme.equals(u.scheme) && path.equals(u.path);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static URI create(String str) {
        try {
            return new URI(str);
        } catch (URISyntaxException x) {
            throw new IllegalArgumentException(x.getMessage(), x);
        }
    }

    // -- Operations --

    public URI parseServerAuthority() throws URISyntaxException {
        // We could be clever and cache the error message and index from the
        // exception thrown during the original parse, but that would require
        // either more fields or a more-obscure representation.
        if ((host != null) || (authority == null))
            return this;
        new Parser(toString()).parse(true);
        return this;
    }

    public URI normalize() {
        return normalize(this);
    }

    public URI resolve(URI uri) {
        return resolve(this, uri);
    }

    public URI resolve(String str) {
        return resolve(URI.create(str));
    }

    public URI relativize(URI uri) {
        return relativize(this, uri);
    }

    public URL toURL() throws MalformedURLException {
        return URL.fromURI(this);
    }

    // -- Component access methods --

    public String getScheme() {
        return scheme;
    }

    public boolean isAbsolute() {
        return scheme != null;
    }

    public boolean isOpaque() {
        return path == null;
    }

    public String getRawSchemeSpecificPart() {
        String part = schemeSpecificPart;
        if (part != null) {
            return part;
        }

        String s = string;
        if (s != null) {
            // if string is defined, components will have been parsed
            int start = 0;
            int end = s.length();
            if (scheme != null)
                start = scheme.length() + 1;

            if (fragment != null)
                end -= fragment.length() + 1;

            if (path != null && path.length() == end - start)
                part = path;
            else
                part = s.substring(start, end);
        } else {
            StringBuilder sb = new StringBuilder();
            appendSchemeSpecificPart(sb, null, getAuthority(), getUserInfo(), host, port, getPath(), getQuery());
            part = sb.toString();
        }
        return schemeSpecificPart = part;
    }

    public String getSchemeSpecificPart() {
        String part = decodedSchemeSpecificPart;
        if (part == null)
            decodedSchemeSpecificPart = part = decode(getRawSchemeSpecificPart());
        return part;
    }

    public String getRawAuthority() {
        return authority;
    }

    public String getAuthority() {
        String auth = decodedAuthority;
        if ((auth == null) && (authority != null))
            decodedAuthority = auth = decode(authority);
        return auth;
    }

    public String getRawUserInfo() {
        return userInfo;
    }

    public String getUserInfo() {
        String user = decodedUserInfo;
        if ((user == null) && (userInfo != null))
            decodedUserInfo = user = decode(userInfo);
        return user;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getRawPath() {
        return path;
    }

    public String getPath() {
        String decoded = decodedPath;
        if ((decoded == null) && (path != null))
            decodedPath = decoded = decode(path);
        return decoded;
    }

    public String getRawQuery() {
        return query;
    }

    public String getQuery() {
        String decoded = decodedQuery;
        if ((decoded == null) && (query != null))
            decodedQuery = decoded = decode(query, false);
        return decoded;
    }

    public String getRawFragment() {
        return fragment;
    }

    public String getFragment() {
        String decoded = decodedFragment;
        if ((decoded == null) && (fragment != null))
            decodedFragment = decoded = decode(fragment, false);
        return decoded;
    }

    // -- Equality, comparison, hash code, toString, and serialization --

    public boolean equals(Object ob) {
        if (ob == this)
            return true;
        if (!(ob instanceof URI that))
            return false;
        if (this.isOpaque() != that.isOpaque()) return false;
        if (!equalIgnoringCase(this.scheme, that.scheme)) return false;
        if (!equal(this.fragment, that.fragment)) return false;

        // Opaque
        if (this.isOpaque())
            return equal(this.schemeSpecificPart, that.schemeSpecificPart);

        // Hierarchical
        if (!equal(this.path, that.path)) return false;
        if (!equal(this.query, that.query)) return false;

        // Authorities
        if (this.authority == that.authority) return true;
        if (this.host != null) {
            // Server-based
            if (!equal(this.userInfo, that.userInfo)) return false;
            if (!equalIgnoringCase(this.host, that.host)) return false;
            if (this.port != that.port) return false;
        } else if (this.authority != null) {
            // Registry-based
            if (!equal(this.authority, that.authority)) return false;
        } else if (this.authority != that.authority) {
            return false;
        }

        return true;
    }

    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = hashIgnoringCase(0, scheme);
            h = hash(h, fragment);
            if (isOpaque()) {
                h = hash(h, schemeSpecificPart);
            } else {
                h = hash(h, path);
                h = hash(h, query);
                if (host != null) {
                    h = hash(h, userInfo);
                    h = hashIgnoringCase(h, host);
                    h += 1949 * port;
                } else {
                    h = hash(h, authority);
                }
            }
            if (h != 0)
                hash = h;
        }
        return h;
    }

    public int compareTo(URI that) {
        int c;

        if ((c = compareIgnoringCase(this.scheme, that.scheme)) != 0)
            return c;

        if (this.isOpaque()) {
            if (that.isOpaque()) {
                // Both opaque
                if ((c = compare(this.schemeSpecificPart, that.schemeSpecificPart)) != 0)
                    return c;
                return compare(this.fragment, that.fragment);
            }
            return +1;                  // Opaque > hierarchical
        } else if (that.isOpaque()) {
            return -1;                  // Hierarchical < opaque
        }

        // Hierarchical
        if ((this.host != null) && (that.host != null)) {
            // Both server-based
            if ((c = compare(this.userInfo, that.userInfo)) != 0)
                return c;
            if ((c = compareIgnoringCase(this.host, that.host)) != 0)
                return c;
            if ((c = this.port - that.port) != 0)
                return c;
        } else {
            // If one or both authorities are registry-based then we simply
            // compare them in the usual, case-sensitive way.  If one is
            // registry-based and one is server-based then the strings are
            // guaranteed to be unequal, hence the comparison will never return
            // zero and the compareTo and equals methods will remain
            // consistent.
            if ((c = compare(this.authority, that.authority)) != 0) return c;
        }

        if ((c = compare(this.path, that.path)) != 0) return c;
        if ((c = compare(this.query, that.query)) != 0) return c;
        return compare(this.fragment, that.fragment);
    }

    public String toString() {
        String s = string;
        if (s == null)
            s = defineString();
        return s;
    }

    private String defineString() {
        String s = string;
        if (s != null)
            return s;

        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme);
            sb.append(':');
        }
        if (isOpaque()) {
            sb.append(schemeSpecificPart);
        } else {
            if (host != null) {
                sb.append("//");
                if (userInfo != null) {
                    sb.append(userInfo);
                    sb.append('@');
                }
                boolean needBrackets = ((host.indexOf(':') >= 0)
                        && !host.startsWith("[")
                        && !host.endsWith("]"));
                if (needBrackets) sb.append('[');
                sb.append(host);
                if (needBrackets) sb.append(']');
                if (port != -1) {
                    sb.append(':');
                    sb.append(port);
                }
            } else if (authority != null) {
                sb.append("//");
                sb.append(authority);
            }
            if (path != null)
                sb.append(path);
            if (query != null) {
                sb.append('?');
                sb.append(query);
            }
        }
        if (fragment != null) {
            sb.append('#');
            sb.append(fragment);
        }
        return string = sb.toString();
    }

    public String toASCIIString() {
        return encode(toString());
    }

    // -- Serialization support --

    @java.io.Serial
    private void writeObject(ObjectOutputStream os) throws IOException {
        defineString();
        os.defaultWriteObject();        // Writes the string field only
    }

    @java.io.Serial
    private void readObject(ObjectInputStream is) throws ClassNotFoundException, IOException {
        port = -1;                      // Argh
        is.defaultReadObject();
        try {
            new Parser(string).parse(false);
        } catch (URISyntaxException x) {
            IOException y = new InvalidObjectException("Invalid URI");
            y.initCause(x);
            throw y;
        }
    }

    // -- End of public methods --

    // -- Utility methods for string-field comparison and hashing --

    private static int toLower(char c) {
        if ((c >= 'A') && (c <= 'Z'))
            return c + ('a' - 'A');
        return c;
    }

    private static int toUpper(char c) {
        if ((c >= 'a') && (c <= 'z'))
            return c - ('a' - 'A');
        return c;
    }

    private static boolean equal(String s, String t) {
        boolean testForEquality = true;
        int result = percentNormalizedComparison(s, t, testForEquality);
        return result == 0;
    }

    private static boolean equalIgnoringCase(String s, String t) {
        if (s == t) return true;
        if ((s != null) && (t != null)) {
            int n = s.length();
            if (t.length() != n)
                return false;
            for (int i = 0; i < n; i++) {
                if (toLower(s.charAt(i)) != toLower(t.charAt(i)))
                    return false;
            }
            return true;
        }
        return false;
    }

    private static int hash(int hash, String s) {
        if (s == null)
            return hash;
        return s.indexOf('%') < 0 ? hash * 127 + s.hashCode() : normalizedHash(hash, s);
    }

    private static int normalizedHash(int hash, String s) {
        int h = 0;
        for (int index = 0; index < s.length(); index++) {
            char ch = s.charAt(index);
            h = 31 * h + ch;
            if (ch == '%') {
                /*
                 * Process the next two encoded characters
                 */
                for (int i = index + 1; i < index + 3; i++)
                    h = 31 * h + toUpper(s.charAt(i));
                index += 2;
            }
        }
        return hash * 127 + h;
    }

    private static int hashIgnoringCase(int hash, String s) {
        if (s == null) return hash;
        int h = hash;
        int n = s.length();
        for (int i = 0; i < n; i++)
            h = 31 * h + toLower(s.charAt(i));
        return h;
    }

    private static int compare(String s, String t) {
        boolean testForEquality = false;
        int result = percentNormalizedComparison(s, t, testForEquality);
        return result;
    }

    private static int percentNormalizedComparison(String s, String t, boolean testForEquality) {
        if (s == t) return 0;
        if (s != null) {
            if (t != null) {
                if (s.indexOf('%') < 0)
                    return s.compareTo(t);

                int sn = s.length();
                int tn = t.length();
                if ((sn != tn) && testForEquality)
                    return sn - tn;
                int val = 0;
                int n = sn < tn ? sn : tn;
                for (int i = 0; i < n; ) {
                    char c = s.charAt(i);
                    char d = t.charAt(i);
                    val = c - d;
                    if (c != '%') {
                        if (val != 0)
                            return val;
                        i++;
                        continue;
                    }
                    if (d != '%') {
                        if (val != 0)
                            return val;
                    }
                    i++;
                    val = toLower(s.charAt(i)) - toLower(t.charAt(i));
                    if (val != 0)
                        return val;
                    i++;
                    val = toLower(s.charAt(i)) - toLower(t.charAt(i));
                    if (val != 0)
                        return val;
                    i++;
                }
                return sn - tn;
            } else
                return +1;
        } else {
            return -1;
        }
    }

    private static int compareIgnoringCase(String s, String t) {
        if (s == t)
            return 0;
        if (s != null) {
            if (t != null) {
                int sn = s.length();
                int tn = t.length();
                int n = sn < tn ? sn : tn;
                for (int i = 0; i < n; i++) {
                    int c = toLower(s.charAt(i)) - toLower(t.charAt(i));
                    if (c != 0)
                        return c;
                }
                return sn - tn;
            }
            return +1;
        } else {
            return -1;
        }
    }

    // -- String construction --

    private static void checkPath(String s, String scheme, String path) throws URISyntaxException {
        if (scheme != null) {
            if (path != null && !path.isEmpty() && path.charAt(0) != '/')
                throw new URISyntaxException(s, "Relative path in absolute URI");
        }
    }

    private void appendAuthority(StringBuilder sb,
                                 String authority,
                                 String userInfo,
                                 String host,
                                 int port) {
        if (host != null) {
            sb.append("//");
            if (userInfo != null) {
                sb.append(quote(userInfo, L_USERINFO, H_USERINFO));
                sb.append('@');
            }
            boolean needBrackets = ((host.indexOf(':') >= 0)
                    && !host.startsWith("[")
                    && !host.endsWith("]"));
            if (needBrackets) sb.append('[');
            sb.append(host);
            if (needBrackets) sb.append(']');
            if (port != -1) {
                sb.append(':');
                sb.append(port);
            }
        } else if (authority != null) {
            sb.append("//");
            if (authority.startsWith("[")) {
                // authority should (but may not) contain an embedded IPv6 address
                int end = authority.indexOf(']');
                String doquote = authority, dontquote = "";
                if (end != -1 && authority.indexOf(':') != -1) {
                    // the authority contains an IPv6 address
                    if (end == authority.length()) {
                        dontquote = authority;
                        doquote = "";
                    } else {
                        dontquote = authority.substring(0 , end + 1);
                        doquote = authority.substring(end + 1);
                    }
                }
                sb.append(dontquote);
                sb.append(quote(doquote,
                        L_REG_NAME | L_SERVER,
                        H_REG_NAME | H_SERVER));
            } else {
                sb.append(quote(authority,
                        L_REG_NAME | L_SERVER,
                        H_REG_NAME | H_SERVER));
            }
        }
    }

    private void appendSchemeSpecificPart(StringBuilder sb,
                                          String opaquePart,
                                          String authority,
                                          String userInfo,
                                          String host,
                                          int port,
                                          String path,
                                          String query) {
        if (opaquePart != null) {
            /* check if SSP begins with an IPv6 address
             * because we must not quote a literal IPv6 address
             */
            if (opaquePart.startsWith("//[")) {
                int end =  opaquePart.indexOf(']');
                if (end != -1 && opaquePart.indexOf(':')!=-1) {
                    String doquote, dontquote;
                    if (end == opaquePart.length()) {
                        dontquote = opaquePart;
                        doquote = "";
                    } else {
                        dontquote = opaquePart.substring(0,end+1);
                        doquote = opaquePart.substring(end+1);
                    }
                    sb.append (dontquote);
                    sb.append(quote(doquote, L_URIC, H_URIC));
                }
            } else {
                sb.append(quote(opaquePart, L_URIC, H_URIC));
            }
        } else {
            appendAuthority(sb, authority, userInfo, host, port);
            if (path != null)
                sb.append(quote(path, L_PATH, H_PATH));
            if (query != null) {
                sb.append('?');
                sb.append(quote(query, L_URIC, H_URIC));
            }
        }
    }

    private void appendFragment(StringBuilder sb, String fragment) {
        if (fragment != null) {
            sb.append('#');
            sb.append(quote(fragment, L_URIC, H_URIC));
        }
    }

    private String toString(String scheme,
                            String opaquePart,
                            String authority,
                            String userInfo,
                            String host,
                            int port,
                            String path,
                            String query,
                            String fragment) {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme);
            sb.append(':');
        }
        appendSchemeSpecificPart(sb, opaquePart,
                authority, userInfo, host, port,
                path, query);
        appendFragment(sb, fragment);
        return sb.toString();
    }

    // -- Normalization, resolution, and relativization --

    private static String resolvePath(String base, String child, boolean absolute) {
        int i = base.lastIndexOf('/');
        int cn = child.length();
        String path = "";

        if (cn == 0) {
            // 5.2 (6a)
            if (i >= 0)
                path = base.substring(0, i + 1);
        } else {
            StringBuilder sb = new StringBuilder(base.length() + cn);
            // 5.2 (6a-b)
            if (i >= 0 || !absolute) {
                sb.append(base, 0, i + 1);
            } else {
                sb.append('/');
            }
            sb.append(child);
            path = sb.toString();
        }

        // 5.2 (6c-f)
        String np = normalize(path);

        // 5.2 (6g): If the result is absolute but the path begins with "../",
        // then we simply leave the path as-is

        return np;
    }

    private static URI resolve(URI base, URI child) {
        // check if child if opaque first so that NPE is thrown
        // if child is null.
        if (child.isOpaque() || base.isOpaque())
            return child;

        // 5.2 (2): Reference to current document (lone fragment)
        if ((child.scheme == null) && (child.authority == null)
                && child.path.isEmpty() && (child.fragment != null)
                && (child.query == null)) {
            if ((base.fragment != null)
                    && child.fragment.equals(base.fragment)) {
                return base;
            }
            URI ru = new URI();
            ru.scheme = base.scheme;
            ru.authority = base.authority;
            ru.userInfo = base.userInfo;
            ru.host = base.host;
            ru.port = base.port;
            ru.path = base.path;
            ru.fragment = child.fragment;
            ru.query = base.query;
            return ru;
        }

        // 5.2 (3): Child is absolute
        if (child.scheme != null)
            return child;

        URI ru = new URI();             // Resolved URI
        ru.scheme = base.scheme;
        ru.query = child.query;
        ru.fragment = child.fragment;

        // 5.2 (4): Authority
        if (child.authority == null) {
            ru.authority = base.authority;
            ru.host = base.host;
            ru.userInfo = base.userInfo;
            ru.port = base.port;

            String cp = (child.path == null) ? "" : child.path;
            if (!cp.isEmpty() && cp.charAt(0) == '/') {
                // 5.2 (5): Child path is absolute
                ru.path = child.path;
            } else {
                // 5.2 (6): Resolve relative path
                ru.path = resolvePath(base.path, cp, base.isAbsolute());
            }
        } else {
            ru.authority = child.authority;
            ru.host = child.host;
            ru.userInfo = child.userInfo;
            ru.host = child.host;
            ru.port = child.port;
            ru.path = child.path;
        }

        // 5.2 (7): Recombine (nothing to do here)
        return ru;
    }

    private static URI normalize(URI u) {
        if (u.isOpaque() || u.path == null || u.path.isEmpty())
            return u;

        String np = normalize(u.path);
        if (np == u.path)
            return u;

        URI v = new URI();
        v.scheme = u.scheme;
        v.fragment = u.fragment;
        v.authority = u.authority;
        v.userInfo = u.userInfo;
        v.host = u.host;
        v.port = u.port;
        v.path = np;
        v.query = u.query;
        return v;
    }

    private static URI relativize(URI base, URI child) {
        // check if child if opaque first so that NPE is thrown
        // if child is null.
        if (child.isOpaque() || base.isOpaque())
            return child;
        if (!equalIgnoringCase(base.scheme, child.scheme)
                || !equal(base.authority, child.authority))
            return child;

        String bp = normalize(base.path);
        String cp = normalize(child.path);
        if (!bp.equals(cp)) {
            if (!bp.endsWith("/"))
                bp = bp + "/";
            if (!cp.startsWith(bp))
                return child;
        }

        URI v = new URI();
        v.path = cp.substring(bp.length());
        v.query = child.query;
        v.fragment = child.fragment;
        return v;
    }

    // -- Path normalization --

    private static int needsNormalization(String path) {
        boolean normal = true;
        int ns = 0;                     // Number of segments
        int end = path.length() - 1;    // Index of last char in path
        int p = 0;                      // Index of next char in path

        // Skip initial slashes
        while (p <= end) {
            if (path.charAt(p) != '/') break;
            p++;
        }
        if (p > 1) normal = false;

        // Scan segments
        while (p <= end) {

            // Looking at "." or ".." ?
            if ((path.charAt(p) == '.')
                    && ((p == end)
                    || ((path.charAt(p + 1) == '/')
                    || ((path.charAt(p + 1) == '.')
                    && ((p + 1 == end)
                    || (path.charAt(p + 2) == '/')))))) {
                normal = false;
            }
            ns++;

            // Find beginning of next segment
            while (p <= end) {
                if (path.charAt(p++) != '/')
                    continue;

                // Skip redundant slashes
                while (p <= end) {
                    if (path.charAt(p) != '/') break;
                    normal = false;
                    p++;
                }

                break;
            }
        }

        return normal ? -1 : ns;
    }

    private static void split(char[] path, int[] segs) {
        int end = path.length - 1;      // Index of last char in path
        int p = 0;                      // Index of next char in path
        int i = 0;                      // Index of current segment

        // Skip initial slashes
        while (p <= end) {
            if (path[p] != '/') break;
            path[p] = '\0';
            p++;
        }

        while (p <= end) {

            // Note start of segment
            segs[i++] = p++;

            // Find beginning of next segment
            while (p <= end) {
                if (path[p++] != '/')
                    continue;
                path[p - 1] = '\0';

                // Skip redundant slashes
                while (p <= end) {
                    if (path[p] != '/') break;
                    path[p++] = '\0';
                }
                break;
            }
        }

        if (i != segs.length)
            throw new InternalError();  // ASSERT
    }

    private static int join(char[] path, int[] segs) {
        int ns = segs.length;           // Number of segments
        int end = path.length - 1;      // Index of last char in path
        int p = 0;                      // Index of next path char to write

        if (path[p] == '\0') {
            // Restore initial slash for absolute paths
            path[p++] = '/';
        }

        for (int i = 0; i < ns; i++) {
            int q = segs[i];            // Current segment
            if (q == -1)
                // Ignore this segment
                continue;

            if (p == q) {
                // We're already at this segment, so just skip to its end
                while ((p <= end) && (path[p] != '\0'))
                    p++;
                if (p <= end) {
                    // Preserve trailing slash
                    path[p++] = '/';
                }
            } else if (p < q) {
                // Copy q down to p
                while ((q <= end) && (path[q] != '\0'))
                    path[p++] = path[q++];
                if (q <= end) {
                    // Preserve trailing slash
                    path[p++] = '/';
                }
            } else
                throw new InternalError(); // ASSERT false
        }

        return p;
    }

    private static void removeDots(char[] path, int[] segs) {
        int ns = segs.length;
        int end = path.length - 1;

        for (int i = 0; i < ns; i++) {
            int dots = 0;               // Number of dots found (0, 1, or 2)

            // Find next occurrence of "." or ".."
            do {
                int p = segs[i];
                if (path[p] == '.') {
                    if (p == end) {
                        dots = 1;
                        break;
                    } else if (path[p + 1] == '\0') {
                        dots = 1;
                        break;
                    } else if ((path[p + 1] == '.')
                            && ((p + 1 == end)
                            || (path[p + 2] == '\0'))) {
                        dots = 2;
                        break;
                    }
                }
                i++;
            } while (i < ns);
            if ((i > ns) || (dots == 0))
                break;

            if (dots == 1) {
                // Remove this occurrence of "."
                segs[i] = -1;
            } else {
                // If there is a preceding non-".." segment, remove both that
                // segment and this occurrence of ".."; otherwise, leave this
                // ".." segment as-is.
                int j;
                for (j = i - 1; j >= 0; j--) {
                    if (segs[j] != -1) break;
                }
                if (j >= 0) {
                    int q = segs[j];
                    if (!((path[q] == '.')
                            && (path[q + 1] == '.')
                            && (path[q + 2] == '\0'))) {
                        segs[i] = -1;
                        segs[j] = -1;
                    }
                }
            }
        }
    }

    private static void maybeAddLeadingDot(char[] path, int[] segs) {

        if (path[0] == '\0')
            // The path is absolute
            return;

        int ns = segs.length;
        int f = 0;                      // Index of first segment
        while (f < ns) {
            if (segs[f] >= 0)
                break;
            f++;
        }
        if ((f >= ns) || (f == 0))
            // The path is empty, or else the original first segment survived,
            // in which case we already know that no leading "." is needed
            return;

        int p = segs[f];
        while ((p < path.length) && (path[p] != ':') && (path[p] != '\0')) p++;
        if (p >= path.length || path[p] == '\0')
            // No colon in first segment, so no "." needed
            return;

        // At this point we know that the first segment is unused,
        // hence we can insert a "." segment at that position
        path[0] = '.';
        path[1] = '\0';
        segs[0] = 0;
    }

    private static String normalize(String ps) {

        // Does this path need normalization?
        int ns = needsNormalization(ps);        // Number of segments
        if (ns < 0)
            // Nope -- just return it
            return ps;

        char[] path = ps.toCharArray();         // Path in char-array form

        // Split path into segments
        int[] segs = new int[ns];               // Segment-index array
        split(path, segs);

        // Remove dots
        removeDots(path, segs);

        // Prevent scheme-name confusion
        maybeAddLeadingDot(path, segs);

        // Join the remaining segments and return the result
        String s = new String(path, 0, join(path, segs));
        if (s.equals(ps)) {
            // string was already normalized
            return ps;
        }
        return s;
    }

    // -- Character classes for parsing --

    private static boolean match(char c, long lowMask, long highMask) {
        if (c == 0) // 0 doesn't have a slot in the mask. So, it never matches.
            return false;
        if (c < 64)
            return ((1L << c) & lowMask) != 0;
        if (c < 128)
            return ((1L << (c - 64)) & highMask) != 0;
        return false;
    }

    private static final long L_DIGIT = 0x3FF000000000000L; // lowMask('0', '9');

    private static final long H_DIGIT = 0L;

    private static final long L_UPALPHA = 0L;

    private static final long H_UPALPHA = 0x7FFFFFEL; // highMask('A', 'Z');

    private static final long L_LOWALPHA = 0L;

    private static final long H_LOWALPHA = 0x7FFFFFE00000000L; // highMask('a', 'z');

    private static final long L_ALPHA = L_LOWALPHA | L_UPALPHA;

    private static final long H_ALPHA = H_LOWALPHA | H_UPALPHA;

    private static final long L_ALPHANUM = L_DIGIT | L_ALPHA;

    private static final long H_ALPHANUM = H_DIGIT | H_ALPHA;

    private static final long L_HEX = L_DIGIT;

    private static final long H_HEX = 0x7E0000007EL; // highMask('A', 'F') | highMask('a', 'f');

    private static final long L_MARK = 0x678200000000L; // lowMask("-_.!~*'()");

    private static final long H_MARK = 0x4000000080000000L; // highMask("-_.!~*'()");

    private static final long L_UNRESERVED = L_ALPHANUM | L_MARK;

    private static final long H_UNRESERVED = H_ALPHANUM | H_MARK;

    private static final long L_RESERVED = 0xAC00985000000000L; // lowMask(";/?:@&=+$,[]");

    private static final long H_RESERVED = 0x28000001L; // highMask(";/?:@&=+$,[]");

    private static final long L_ESCAPED = 1L;

    private static final long H_ESCAPED = 0L;

    private static final long L_URIC = L_RESERVED | L_UNRESERVED | L_ESCAPED;

    private static final long H_URIC = H_RESERVED | H_UNRESERVED | H_ESCAPED;

    private static final long L_PCHAR = L_UNRESERVED | L_ESCAPED | 0x2400185000000000L; // lowMask(":@&=+$,");

    private static final long H_PCHAR = H_UNRESERVED | H_ESCAPED | 0x1L; // highMask(":@&=+$,");

    // All valid path characters

    private static final long L_PATH = L_PCHAR | 0x800800000000000L; // lowMask(";/");

    private static final long H_PATH = H_PCHAR; // highMask(";/") == 0x0L;

    private static final long L_DASH = 0x200000000000L; // lowMask("-");

    private static final long H_DASH = 0x0L; // highMask("-");

    private static final long L_DOT = 0x400000000000L; // lowMask(".");

    private static final long H_DOT = 0x0L; // highMask(".");

    private static final long L_USERINFO = L_UNRESERVED | L_ESCAPED | 0x2C00185000000000L; // lowMask(";:&=+$,");

    private static final long H_USERINFO = H_UNRESERVED | H_ESCAPED; // | highMask(";:&=+$,") == 0L;

    private static final long L_REG_NAME = L_UNRESERVED | L_ESCAPED | 0x2C00185000000000L; // lowMask("$,;:@&=+");

    private static final long H_REG_NAME = H_UNRESERVED | H_ESCAPED | 0x1L; // highMask("$,;:@&=+");

    private static final long L_SERVER = L_USERINFO | L_ALPHANUM | L_DASH | 0x400400000000000L; // lowMask(".:@[]");

    private static final long H_SERVER = H_USERINFO | H_ALPHANUM | H_DASH | 0x28000001L; // highMask(".:@[]");

    private static final long L_SERVER_PERCENT = L_SERVER | 0x2000000000L; // lowMask("%");

    private static final long H_SERVER_PERCENT = H_SERVER; // | highMask("%") == 0L;

    private static final long L_SCHEME = L_ALPHA | L_DIGIT | 0x680000000000L; // lowMask("+-.");

    private static final long H_SCHEME = H_ALPHA | H_DIGIT; // | highMask("+-.") == 0L

    private static final long L_SCOPE_ID = L_ALPHANUM | 0x400000000000L; // lowMask("_.");

    private static final long H_SCOPE_ID = H_ALPHANUM | 0x80000000L; // highMask("_.");

    // -- Escaping and encoding --

    private static final char[] hexDigits = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private static void appendEscape(StringBuilder sb, byte b) {
        sb.append('%');
        sb.append(hexDigits[(b >> 4) & 0x0f]);
        sb.append(hexDigits[(b >> 0) & 0x0f]);
    }

    private static void appendEncoded(CharsetEncoder encoder, StringBuilder sb, char c) {
        ByteBuffer bb = null;
        try {
            bb = encoder.encode(CharBuffer.wrap("" + c));
        } catch (CharacterCodingException x) {
            assert false;
        }
        while (bb.hasRemaining()) {
            int b = bb.get() & 0xff;
            if (b >= 0x80)
                appendEscape(sb, (byte)b);
            else
                sb.append((char)b);
        }
    }

    private static String quote(String s, long lowMask, long highMask) {
        StringBuilder sb = null;
        CharsetEncoder encoder = null;
        boolean allowNonASCII = ((lowMask & L_ESCAPED) != 0);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '\u0080') {
                if (!match(c, lowMask, highMask)) {
                    if (sb == null) {
                        sb = new StringBuilder();
                        sb.append(s, 0, i);
                    }
                    appendEscape(sb, (byte)c);
                } else {
                    if (sb != null)
                        sb.append(c);
                }
            } else if (allowNonASCII
                    && (Character.isSpaceChar(c)
                    || Character.isISOControl(c))) {
                if (encoder == null)
                    encoder = UTF_8.INSTANCE.newEncoder();
                if (sb == null) {
                    sb = new StringBuilder();
                    sb.append(s, 0, i);
                }
                appendEncoded(encoder, sb, c);
            } else {
                if (sb != null)
                    sb.append(c);
            }
        }
        return (sb == null) ? s : sb.toString();
    }

    private static String encode(String s) {
        int n = s.length();
        if (n == 0)
            return s;

        // First check whether we actually need to encode
        for (int i = 0;;) {
            if (s.charAt(i) >= '\u0080')
                break;
            if (++i >= n)
                return s;
        }

        String ns = Normalizer.normalize(s, Normalizer.Form.NFC);
        ByteBuffer bb = null;
        try {
            bb = UTF_8.INSTANCE.newEncoder().encode(CharBuffer.wrap(ns));

        } catch (CharacterCodingException x) {
            assert false;
        }

        StringBuilder sb = new StringBuilder();
        while (bb.hasRemaining()) {
            int b = bb.get() & 0xff;
            if (b >= 0x80)
                appendEscape(sb, (byte)b);
            else
                sb.append((char)b);
        }
        return sb.toString();
    }

    private static int decode(char c) {
        if ((c >= '0') && (c <= '9'))
            return c - '0';
        if ((c >= 'a') && (c <= 'f'))
            return c - 'a' + 10;
        if ((c >= 'A') && (c <= 'F'))
            return c - 'A' + 10;
        assert false;
        return -1;
    }

    private static byte decode(char c1, char c2) {
        return (byte)(  ((decode(c1) & 0xf) << 4) | ((decode(c2) & 0xf) << 0));
    }

    private static String decode(String s) {
        return decode(s, true);
    }

    private static String decode(String s, boolean ignorePercentInBrackets) {
        if (s == null)
            return s;
        int n = s.length();
        if (n == 0)
            return s;
        if (s.indexOf('%') < 0)
            return s;

        StringBuilder sb = new StringBuilder(n);
        ByteBuffer bb = ByteBuffer.allocate(n);
        CharBuffer cb = CharBuffer.allocate(n);
        CharsetDecoder dec = UTF_8.INSTANCE.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        // This is not horribly efficient, but it will do for now
        char c = s.charAt(0);
        boolean betweenBrackets = false;

        for (int i = 0; i < n;) {
            assert c == s.charAt(i);    // Loop invariant
            if (c == '[') {
                betweenBrackets = true;
            } else if (betweenBrackets && c == ']') {
                betweenBrackets = false;
            }
            if (c != '%' || (betweenBrackets && ignorePercentInBrackets)) {
                sb.append(c);
                if (++i >= n)
                    break;
                c = s.charAt(i);
                continue;
            }
            bb.clear();
            int ui = i;
            for (;;) {
                assert (n - i >= 2);
                bb.put(decode(s.charAt(++i), s.charAt(++i)));
                if (++i >= n)
                    break;
                c = s.charAt(i);
                if (c != '%')
                    break;
            }
            bb.flip();
            cb.clear();
            dec.reset();
            CoderResult cr = dec.decode(bb, cb, true);
            assert cr.isUnderflow();
            cr = dec.flush(cb);
            assert cr.isUnderflow();
            sb.append(cb.flip().toString());
        }

        return sb.toString();
    }

    // -- Parsing --

    private class Parser {
        private String input;
        private boolean requireServerAuthority = false;

        Parser(String s) {
            input = s;
            string = s;
        }

        // -- Methods for throwing URISyntaxException in various ways --

        private void fail(String reason) throws URISyntaxException {
            throw new URISyntaxException(input, reason);
        }

        private void fail(String reason, int p) throws URISyntaxException {
            throw new URISyntaxException(input, reason, p);
        }

        private void failExpecting(String expected, int p) throws URISyntaxException {
            fail("Expected " + expected, p);
        }

        // -- Simple access to the input string --

        private boolean at(int start, int end, char c) {
            return (start < end) && (input.charAt(start) == c);
        }

        private boolean at(int start, int end, String s) {
            int p = start;
            int sn = s.length();
            if (sn > end - p)
                return false;
            int i = 0;
            while (i < sn) {
                if (input.charAt(p++) != s.charAt(i))
                    break;
                i++;
            }
            return (i == sn);
        }

        // -- Scanning --

        private int scan(int start, int end, char c) {
            if ((start < end) && (input.charAt(start) == c))
                return start + 1;
            return start;
        }

        private int scan(int start, int end, String err, String stop) {
            int p = start;
            while (p < end) {
                char c = input.charAt(p);
                if (err.indexOf(c) >= 0)
                    return -1;
                if (stop.indexOf(c) >= 0)
                    break;
                p++;
            }
            return p;
        }

        private int scan(int start, int end, String stop) {
            int p = start;
            while (p < end) {
                char c = input.charAt(p);
                if (stop.indexOf(c) >= 0)
                    break;
                p++;
            }
            return p;
        }

        private int scanEscape(int start, int n, char first) throws URISyntaxException {
            int p = start;
            char c = first;
            if (c == '%') {
                // Process escape pair
                if ((p + 3 <= n)
                        && match(input.charAt(p + 1), L_HEX, H_HEX)
                        && match(input.charAt(p + 2), L_HEX, H_HEX)) {
                    return p + 3;
                }
                fail("Malformed escape pair", p);
            } else if ((c > 128)
                    && !Character.isSpaceChar(c)
                    && !Character.isISOControl(c)) {
                // Allow unescaped but visible non-US-ASCII chars
                return p + 1;
            }
            return p;
        }

        private int scan(int start, int n, long lowMask, long highMask) throws URISyntaxException {
            int p = start;
            while (p < n) {
                char c = input.charAt(p);
                if (match(c, lowMask, highMask)) {
                    p++;
                    continue;
                }
                if ((lowMask & L_ESCAPED) != 0) {
                    int q = scanEscape(p, n, c);
                    if (q > p) {
                        p = q;
                        continue;
                    }
                }
                break;
            }
            return p;
        }

        private void checkChars(int start, int end,
                                long lowMask, long highMask,
                                String what) throws URISyntaxException {
            int p = scan(start, end, lowMask, highMask);
            if (p < end)
                fail("Illegal character in " + what, p);
        }

        private void checkChar(int p,
                               long lowMask, long highMask,
                               String what) throws URISyntaxException {
            checkChars(p, p + 1, lowMask, highMask, what);
        }

        void parse(boolean rsa) throws URISyntaxException {
            requireServerAuthority = rsa;
            int n = input.length();
            int p = scan(0, n, "/?#", ":");
            if ((p >= 0) && at(p, n, ':')) {
                if (p == 0)
                    failExpecting("scheme name", 0);
                checkChar(0, L_ALPHA, H_ALPHA, "scheme name");
                checkChars(1, p, L_SCHEME, H_SCHEME, "scheme name");
                scheme = input.substring(0, p);
                p++;                    // Skip ':'
                if (at(p, n, '/')) {
                    p = parseHierarchical(p, n);
                } else {
                    // opaque; need to create the schemeSpecificPart
                    int q = scan(p, n, "#");
                    if (q <= p)
                        failExpecting("scheme-specific part", p);
                    checkChars(p, q, L_URIC, H_URIC, "opaque part");
                    schemeSpecificPart = input.substring(p, q);
                    p = q;
                }
            } else {
                p = parseHierarchical(0, n);
            }
            if (at(p, n, '#')) {
                checkChars(p + 1, n, L_URIC, H_URIC, "fragment");
                fragment = input.substring(p + 1, n);
                p = n;
            }
            if (p < n)
                fail("end of URI", p);
        }

        private int parseHierarchical(int start, int n) throws URISyntaxException {
            int p = start;
            if (at(p, n, '/') && at(p + 1, n, '/')) {
                p += 2;
                int q = scan(p, n, "/?#");
                if (q > p) {
                    p = parseAuthority(p, q);
                } else if (q < n) {
                    // DEVIATION: Allow empty authority prior to non-empty
                    // path, query component or fragment identifier
                } else
                    failExpecting("authority", p);
            }
            int q = scan(p, n, "?#"); // DEVIATION: May be empty
            checkChars(p, q, L_PATH, H_PATH, "path");
            path = input.substring(p, q);
            p = q;
            if (at(p, n, '?')) {
                p++;
                q = scan(p, n, "#");
                checkChars(p, q, L_URIC, H_URIC, "query");
                query = input.substring(p, q);
                p = q;
            }
            return p;
        }

        private int parseAuthority(int start, int n) throws URISyntaxException {
            int p = start;
            int q = p;
            URISyntaxException ex = null;

            boolean serverChars;
            boolean regChars;
            boolean skipParseException;

            if (scan(p, n, "]") > p) {
                // contains a literal IPv6 address, therefore % is allowed
                serverChars = (scan(p, n, L_SERVER_PERCENT, H_SERVER_PERCENT) == n);
            } else {
                serverChars = (scan(p, n, L_SERVER, H_SERVER) == n);
            }
            regChars = (scan(p, n, L_REG_NAME, H_REG_NAME) == n);

            if (regChars && !serverChars) {
                // Must be a registry-based authority
                authority = input.substring(p, n);
                return n;
            }

            // When parsing a URI, skip creating exception objects if the server-based
            // authority is not required and the registry parse is successful.
            //
            skipParseException = (!requireServerAuthority && regChars);
            if (serverChars) {
                // Might be (probably is) a server-based authority, so attempt
                // to parse it as such.  If the attempt fails, try to treat it
                // as a registry-based authority.
                try {
                    q = parseServer(p, n, skipParseException);
                    if (q < n) {
                        if (skipParseException) {
                            userInfo = null;
                            host = null;
                            port = -1;
                            q = p;
                        } else {
                            failExpecting("end of authority", q);
                        }
                    } else {
                        authority = input.substring(p, n);
                    }
                } catch (URISyntaxException x) {
                    // Undo results of failed parse
                    userInfo = null;
                    host = null;
                    port = -1;
                    if (requireServerAuthority) {
                        // If we're insisting upon a server-based authority,
                        // then just re-throw the exception
                        throw x;
                    } else {
                        // Save the exception in case it doesn't parse as a
                        // registry either
                        ex = x;
                        q = p;
                    }
                }
            }

            if (q < n) {
                if (regChars) {
                    // Registry-based authority
                    authority = input.substring(p, n);
                } else if (ex != null) {
                    // Re-throw exception; it was probably due to
                    // a malformed IPv6 address
                    throw ex;
                } else {
                    fail("Illegal character in authority", q);
                }
            }

            return n;
        }

        private int parseServer(int start, int n, boolean skipParseException) throws URISyntaxException {
            int p = start;
            int q;

            // userinfo
            q = scan(p, n, "/?#", "@");
            if ((q >= p) && at(q, n, '@')) {
                checkChars(p, q, L_USERINFO, H_USERINFO, "user info");
                userInfo = input.substring(p, q);
                p = q + 1;              // Skip '@'
            }

            // hostname, IPv4 address, or IPv6 address
            if (at(p, n, '[')) {
                // DEVIATION from RFC2396: Support IPv6 addresses, per RFC2732
                p++;
                q = scan(p, n, "/?#", "]");
                if ((q > p) && at(q, n, ']')) {
                    // look for a "%" scope id
                    int r = scan (p, q, "%");
                    if (r > p) {
                        parseIPv6Reference(p, r);
                        if (r+1 == q) {
                            fail ("scope id expected");
                        }
                        checkChars (r+1, q, L_SCOPE_ID, H_SCOPE_ID,
                                "scope id");
                    } else {
                        parseIPv6Reference(p, q);
                    }
                    host = input.substring(p-1, q+1);
                    p = q + 1;
                } else {
                    failExpecting("closing bracket for IPv6 address", q);
                }
            } else {
                q = parseIPv4Address(p, n);
                if (q <= p)
                    q = parseHostname(p, n, skipParseException);
                p = q;
            }

            // port
            if (at(p, n, ':')) {
                p++;
                q = scan(p, n, "/");
                if (q > p) {
                    checkChars(p, q, L_DIGIT, H_DIGIT, "port number");
                    try {
                        port = Integer.parseInt(input, p, q, 10);
                    } catch (NumberFormatException x) {
                        fail("Malformed port number", p);
                    }
                    p = q;
                }
            } else if (p < n && skipParseException) {
                return p;
            }

            if (p < n)
                failExpecting("port number", p);

            return p;
        }

        private int scanByte(int start, int n) throws URISyntaxException {
            int p = start;
            int q = scan(p, n, L_DIGIT, H_DIGIT);
            if (q <= p) return q;
            if (Integer.parseInt(input, p, q, 10) > 255) return p;
            return q;
        }

        private int scanIPv4Address(int start, int n, boolean strict) throws URISyntaxException {
            int p = start;
            int q;
            int m = scan(p, n, L_DIGIT | L_DOT, H_DIGIT | H_DOT);
            if ((m <= p) || (strict && (m != n)))
                return -1;
            for (;;) {
                // Per RFC2732: At most three digits per byte
                // Further constraint: Each element fits in a byte
                if ((q = scanByte(p, m)) <= p) break;   p = q;
                if ((q = scan(p, m, '.')) <= p) break;  p = q;
                if ((q = scanByte(p, m)) <= p) break;   p = q;
                if ((q = scan(p, m, '.')) <= p) break;  p = q;
                if ((q = scanByte(p, m)) <= p) break;   p = q;
                if ((q = scan(p, m, '.')) <= p) break;  p = q;
                if ((q = scanByte(p, m)) <= p) break;   p = q;
                if (q < m) break;
                return q;
            }
            fail("Malformed IPv4 address", q);
            return -1;
        }

        private int takeIPv4Address(int start, int n, String expected) throws URISyntaxException {
            int p = scanIPv4Address(start, n, true);
            if (p <= start)
                failExpecting(expected, start);
            return p;
        }

        private int parseIPv4Address(int start, int n) {
            int p;

            try {
                p = scanIPv4Address(start, n, false);
            } catch (URISyntaxException x) {
                return -1;
            } catch (NumberFormatException nfe) {
                return -1;
            }

            if (p > start && p < n) {
                // IPv4 address is followed by something - check that
                // it's a ":" as this is the only valid character to
                // follow an address.
                if (input.charAt(p) != ':') {
                    p = -1;
                }
            }

            if (p > start)
                host = input.substring(start, p);

            return p;
        }

        private int parseHostname(int start, int n, boolean skipParseException) throws URISyntaxException {
            int p = start;
            int q;
            int l = -1;                 // Start of last parsed label

            do {
                // domainlabel = alphanum [ *( alphanum | "-" ) alphanum ]
                q = scan(p, n, L_ALPHANUM, H_ALPHANUM);
                if (q <= p)
                    break;
                l = p;
                if (q > p) {
                    p = q;
                    q = scan(p, n, L_ALPHANUM | L_DASH, H_ALPHANUM | H_DASH);
                    if (q > p) {
                        if (input.charAt(q - 1) == '-')
                            fail("Illegal character in hostname", q - 1);
                        p = q;
                    }
                }
                q = scan(p, n, '.');
                if (q <= p)
                    break;
                p = q;
            } while (p < n);

            if ((p < n) && !at(p, n, ':')) {
                if (skipParseException)
                    return p;
                fail("Illegal character in hostname", p);
            }
            if (l < 0)
                failExpecting("hostname", start);

            // for a fully qualified hostname check that the rightmost
            // label starts with an alpha character.
            if (l > start && !match(input.charAt(l), L_ALPHA, H_ALPHA))
                fail("Illegal character in hostname", l);

            host = input.substring(start, p);
            return p;
        }

        private int ipv6byteCount = 0;

        private int parseIPv6Reference(int start, int n) throws URISyntaxException {
            int p = start;
            int q;
            boolean compressedZeros = false;

            q = scanHexSeq(p, n);

            if (q > p) {
                p = q;
                if (at(p, n, "::")) {
                    compressedZeros = true;
                    p = scanHexPost(p + 2, n);
                } else if (at(p, n, ':')) {
                    p = takeIPv4Address(p + 1,  n, "IPv4 address");
                    ipv6byteCount += 4;
                }
            } else if (at(p, n, "::")) {
                compressedZeros = true;
                p = scanHexPost(p + 2, n);
            }
            if (p < n)
                fail("Malformed IPv6 address", start);
            if (ipv6byteCount > 16)
                fail("IPv6 address too long", start);
            if (!compressedZeros && ipv6byteCount < 16)
                fail("IPv6 address too short", start);
            if (compressedZeros && ipv6byteCount == 16)
                fail("Malformed IPv6 address", start);

            return p;
        }

        private int scanHexPost(int start, int n) throws URISyntaxException {
            int p = start;
            int q;

            if (p == n)
                return p;

            q = scanHexSeq(p, n);
            if (q > p) {
                p = q;
                if (at(p, n, ':')) {
                    p++;
                    p = takeIPv4Address(p, n, "hex digits or IPv4 address");
                    ipv6byteCount += 4;
                }
            } else {
                p = takeIPv4Address(p, n, "hex digits or IPv4 address");
                ipv6byteCount += 4;
            }
            return p;
        }

        private int scanHexSeq(int start, int n) throws URISyntaxException {
            int p = start;
            int q;

            q = scan(p, n, L_HEX, H_HEX);
            if (q <= p)
                return -1;
            if (at(q, n, '.'))          // Beginning of IPv4 address
                return -1;
            if (q > p + 4)
                fail("IPv6 hexadecimal digit sequence too long", p);
            ipv6byteCount += 2;
            p = q;
            while (p < n) {
                if (!at(p, n, ':'))
                    break;
                if (at(p + 1, n, ':'))
                    break;              // "::"
                p++;
                q = scan(p, n, L_HEX, H_HEX);
                if (q <= p)
                    failExpecting("digits for an IPv6 address", p);
                if (at(q, n, '.')) {    // Beginning of IPv4 address
                    p--;
                    break;
                }
                if (q > p + 4)
                    fail("IPv6 hexadecimal digit sequence too long", p);
                ipv6byteCount += 2;
                p = q;
            }

            return p;
        }
    }

    static {
        SharedSecrets.setJavaNetUriAccess(
                new JavaNetUriAccess() {
                    public URI create(String scheme, String path) {
                        return new URI(scheme, path);
                    }
                }
        );
    }
}
