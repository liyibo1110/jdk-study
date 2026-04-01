package com.github.liyibo1110.jdk.java.net;

import jdk.internal.access.JavaNetInetAddressAccess;
import jdk.internal.access.SharedSecrets;
import sun.net.InetAddressCachePolicy;
import sun.net.util.IPAddressUtil;
import sun.nio.cs.UTF_8;
import sun.security.action.GetPropertyAction;

import java.io.File;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.annotation.Native;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddressImpl;
import java.net.InetAddressImplFactory;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络通信中的主机地址抽象，核心表示的是一个IP地址，以及可能关联的主机名解析结果，提供的功能有：
 * 1、统一表示IP地址。
 * 2、支持主机名和IP之间的解析。
 * 3、区分IPv4和IPv6。
 * 4、提供一些地址类型的判断方法。
 * 注意这个类本身不包括端口号，同时有2个子类：IPv4和IPv6。
 * @author liyibo
 * @date 2026-03-31 17:21
 */
public class InetAddress implements Serializable {

    @Native
    static final int PREFER_IPV4_VALUE = 0;
    @Native static final int PREFER_IPV6_VALUE = 1;
    @Native static final int PREFER_SYSTEM_VALUE = 2;

    @Native static final int IPv4 = 1;

    @Native static final int IPv6 = 2;

    static transient final int preferIPv6Address;

    static class InetAddressHolder {
        String originalHostName;

        InetAddressHolder() {}

        InetAddressHolder(String hostName, int address, int family) {
            this.originalHostName = hostName;
            this.hostName = hostName;
            this.address = address;
            this.family = family;
        }

        void init(String hostName, int family) {
            this.originalHostName = hostName;
            this.hostName = hostName;
            if (family != -1)
                this.family = family;
        }

        String hostName;

        String getHostName() {
            return hostName;
        }

        String getOriginalHostName() {
            return originalHostName;
        }

        /** IPv4地址 */
        int address;

        int getAddress() {
            return address;
        }

        /** 1是IPv4、2是IPv6 */
        int family;

        int getFamily() {
            return family;
        }
    }

    final transient InetAddressHolder holder;

    InetAddressHolder holder() {
        return holder;
    }

    private static transient NameService nameService;

    private transient String canonicalHostName = null;

    @java.io.Serial
    private static final long serialVersionUID = 3286316764910316507L;

    static {
        String str = GetPropertyAction.privilegedGetProperty("java.net.preferIPv6Addresses");
        if (str == null)
            preferIPv6Address = PREFER_IPV4_VALUE;
        else if (str.equalsIgnoreCase("true"))
            preferIPv6Address = PREFER_IPV6_VALUE;
        else if (str.equalsIgnoreCase("false"))
            preferIPv6Address = PREFER_IPV4_VALUE;
        else if (str.equalsIgnoreCase("system"))
            preferIPv6Address = PREFER_SYSTEM_VALUE;
        else
            preferIPv6Address = PREFER_IPV4_VALUE;

        jdk.internal.loader.BootLoader.loadLibrary("net");
        SharedSecrets.setJavaNetInetAddressAccess(
                new JavaNetInetAddressAccess() {
                    public String getOriginalHostName(InetAddress ia) {
                        return ia.holder.getOriginalHostName();
                    }

                    public InetAddress getByName(String hostName, InetAddress hostAddress) throws UnknownHostException {
                        return InetAddress.getByName(hostName, hostAddress);
                    }

                    public int addressValue(Inet4Address inet4Address) {
                        return inet4Address.addressValue();
                    }

                    public byte[] addressBytes(Inet6Address inet6Address) {
                        return inet6Address.addressBytes();
                    }
                }
        );
        init();
    }

    InetAddress() {
        holder = new InetAddressHolder();
    }

    @java.io.Serial
    private Object readResolve() throws ObjectStreamException {
        // will replace the deserialized 'this' object
        return new Inet4Address(holder().getHostName(), holder().getAddress());
    }

    public boolean isMulticastAddress() {
        return false;
    }

    public boolean isAnyLocalAddress() {
        return false;
    }

    public boolean isLoopbackAddress() {
        return false;
    }

    public boolean isLinkLocalAddress() {
        return false;
    }

    public boolean isSiteLocalAddress() {
        return false;
    }

    public boolean isMCGlobal() {
        return false;
    }

    public boolean isMCNodeLocal() {
        return false;
    }

    public boolean isMCLinkLocal() {
        return false;
    }

    public boolean isMCSiteLocal() {
        return false;
    }

    public boolean isMCOrgLocal() {
        return false;
    }

    public boolean isReachable(int timeout) throws IOException {
        return isReachable(null, 0 , timeout);
    }

    public boolean isReachable(NetworkInterface netif, int ttl, int timeout) throws IOException {
        if (ttl < 0)
            throw new IllegalArgumentException("ttl can't be negative");
        if (timeout < 0)
            throw new IllegalArgumentException("timeout can't be negative");
        return impl.isReachable(this, timeout, netif, ttl);
    }

    public String getHostName() {
        return getHostName(true);
    }

    String getHostName(boolean check) {
        if (holder().getHostName() == null)
            holder().hostName = InetAddress.getHostFromNameService(this, check);
        return holder().getHostName();
    }

    public String getCanonicalHostName() {
        String value = canonicalHostName;
        if (value == null)
            canonicalHostName = value = InetAddress.getHostFromNameService(this, true);
        return value;
    }

    private static String getHostFromNameService(InetAddress addr, boolean check) {
        String host = null;
        try {
            // first lookup the hostname
            host = nameService.getHostByAddr(addr.getAddress());

            /* check to see if calling code is allowed to know
             * the hostname for this IP address, ie, connect to the host
             */
            if (check) {
                @SuppressWarnings("removal")
                SecurityManager sec = System.getSecurityManager();
                if (sec != null)
                    sec.checkConnect(host, -1);
            }

            /* now get all the IP addresses for this hostname,
             * and make sure one of them matches the original IP
             * address. We do this to try and prevent spoofing.
             */

            InetAddress[] arr = InetAddress.getAllByName0(host, check);
            boolean ok = false;

            if(arr != null) {
                for(int i = 0; !ok && i < arr.length; i++)
                    ok = addr.equals(arr[i]);
            }

            //XXX: if it looks a spoof just return the address?
            if (!ok) {
                host = addr.getHostAddress();
                return host;
            }
        } catch (SecurityException e) {
            host = addr.getHostAddress();
        } catch (UnknownHostException e) {
            host = addr.getHostAddress();
            // let next provider resolve the hostname
        }
        return host;
    }

    public byte[] getAddress() {
        return null;
    }

    public String getHostAddress() {
        return null;
    }

    public int hashCode() {
        return -1;
    }

    public boolean equals(Object obj) {
        return false;
    }

    public String toString() {
        String hostName = holder().getHostName();
        return Objects.toString(hostName, "") + "/" + getHostAddress();
    }

    private static final ConcurrentMap<String, Addresses> cache = new ConcurrentHashMap<>();

    private static final NavigableSet<CachedAddresses> expirySet = new ConcurrentSkipListSet<>();

    private interface Addresses {
        InetAddress[] get() throws UnknownHostException;
    }

    private static final class CachedAddresses  implements Addresses, Comparable<CachedAddresses> {
        private static final AtomicLong seq = new AtomicLong();
        final String host;
        final InetAddress[] inetAddresses;
        final long expiryTime; // time of expiry (in terms of System.nanoTime())
        final long id = seq.incrementAndGet(); // each instance is unique

        CachedAddresses(String host, InetAddress[] inetAddresses, long expiryTime) {
            this.host = host;
            this.inetAddresses = inetAddresses;
            this.expiryTime = expiryTime;
        }

        @Override
        public InetAddress[] get() throws UnknownHostException {
            if (inetAddresses == null)
                throw new UnknownHostException(host);
            return inetAddresses;
        }

        @Override
        public int compareTo(CachedAddresses other) {
            // natural order is expiry time -
            // compare difference of expiry times rather than
            // expiry times directly, to avoid possible overflow.
            // (see System.nanoTime() recommendations...)
            long diff = this.expiryTime - other.expiryTime;
            if (diff < 0L) return -1;
            if (diff > 0L) return 1;
            // ties are broken using unique id
            return Long.compare(this.id, other.id);
        }
    }

    private static final class NameServiceAddresses implements java.net.InetAddress.Addresses {
        private final String host;
        private final java.net.InetAddress reqAddr;

        NameServiceAddresses(String host, java.net.InetAddress reqAddr) {
            this.host = host;
            this.reqAddr = reqAddr;
        }

        @Override
        public InetAddress[] get() throws UnknownHostException {
            Addresses addresses;
            // only one thread is doing lookup to name service
            // for particular host at any time.
            synchronized (this) {
                // re-check that we are still us + re-install us if slot empty
                addresses = cache.putIfAbsent(host, this);
                if (addresses == null) {
                    // this can happen when we were replaced by CachedAddresses in
                    // some other thread, then CachedAddresses expired and were
                    // removed from cache while we were waiting for lock...
                    addresses = this;
                }
                // still us ?
                if (addresses == this) {
                    // lookup name services
                    InetAddress[] inetAddresses;
                    UnknownHostException ex;
                    int cachePolicy;
                    try {
                        inetAddresses = getAddressesFromNameService(host, reqAddr);
                        ex = null;
                        cachePolicy = InetAddressCachePolicy.get();
                    } catch (UnknownHostException uhe) {
                        inetAddresses = null;
                        ex = uhe;
                        cachePolicy = InetAddressCachePolicy.getNegative();
                    }
                    // remove or replace us with cached addresses according to cachePolicy
                    if (cachePolicy == InetAddressCachePolicy.NEVER) {
                        cache.remove(host, this);
                    } else {
                        CachedAddresses cachedAddresses = new InetAddress.CachedAddresses(
                                host,
                                inetAddresses,
                                cachePolicy == InetAddressCachePolicy.FOREVER
                                        ? 0L
                                        // cachePolicy is in [s] - we need [ns]
                                        : System.nanoTime() + 1000_000_000L * cachePolicy
                        );
                        if (cache.replace(host, this, cachedAddresses) && cachePolicy != InetAddressCachePolicy.FOREVER)
                            // schedule expiry
                            expirySet.add(cachedAddresses);
                    }
                    if (inetAddresses == null)
                        throw ex == null ? new UnknownHostException(host) : ex;
                    return inetAddresses;
                }
                // else addresses != this
            }
            // delegate to different addresses when we are already replaced
            // but outside of synchronized block to avoid any chance of dead-locking
            return addresses.get();
        }
    }

    private interface NameService {
        InetAddress[] lookupAllHostAddr(String host) throws UnknownHostException;

        String getHostByAddr(byte[] addr) throws UnknownHostException;
    }

    private static final class PlatformNameService implements NameService {
        public InetAddress[] lookupAllHostAddr(String host) throws UnknownHostException {
            validate(host);
            return impl.lookupAllHostAddr(host);
        }

        public String getHostByAddr(byte[] addr) throws UnknownHostException {
            return impl.getHostByAddr(addr);
        }
    }

    private static final class HostsFileNameService implements NameService {
        private static final InetAddress[] EMPTY_ARRAY = new InetAddress[0];

        private static final boolean preferIPv4Stack = Boolean.parseBoolean(GetPropertyAction.privilegedGetProperty("java.net.preferIPv4Stack"));

        private final String hostsFile;

        public HostsFileNameService(String hostsFileName) {
            this.hostsFile = hostsFileName;
        }

        @Override
        public String getHostByAddr(byte[] addr) throws UnknownHostException {
            String hostEntry;
            String host = null;

            try (Scanner hostsFileScanner = new Scanner(new File(hostsFile), UTF_8.INSTANCE)) {
                while (hostsFileScanner.hasNextLine()) {
                    hostEntry = hostsFileScanner.nextLine();
                    if (!hostEntry.startsWith("#")) {
                        hostEntry = removeComments(hostEntry);
                        String[] mapping = hostEntry.split("\\s+");
                        if (mapping.length >= 2 && Arrays.equals(addr, createAddressByteArray(mapping[0]))) {
                            host = mapping[1];
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                throw new UnknownHostException("Unable to resolve address "
                        + Arrays.toString(addr) + " as hosts file " + hostsFile
                        + " not found ");
            }

            if ((host == null) || (host.isEmpty()) || (host.equals(" "))) {
                throw new UnknownHostException("Requested address "
                        + Arrays.toString(addr)
                        + " resolves to an invalid entry in hosts file "
                        + hostsFile);
            }
            return host;
        }

        public InetAddress[] lookupAllHostAddr(String host) throws UnknownHostException {
            String hostEntry;
            String addrStr;
            byte addr[];
            List<InetAddress> inetAddresses = new ArrayList<>();
            List<InetAddress> inet4Addresses = new ArrayList<>();
            List<InetAddress> inet6Addresses = new ArrayList<>();

            // lookup the file and create a list InetAddress for the specified host
            try (Scanner hostsFileScanner = new Scanner(new File(hostsFile), UTF_8.INSTANCE)) {
                while (hostsFileScanner.hasNextLine()) {
                    hostEntry = hostsFileScanner.nextLine();
                    if (!hostEntry.startsWith("#")) {
                        hostEntry = removeComments(hostEntry);
                        if (hostEntry.contains(host)) {
                            addrStr = extractHostAddr(hostEntry, host);
                            if ((addrStr != null) && (!addrStr.isEmpty())) {
                                addr = createAddressByteArray(addrStr);
                                if (addr != null) {
                                    InetAddress address = InetAddress.getByAddress(host, addr);
                                    inetAddresses.add(address);
                                    if (address instanceof Inet4Address)
                                        inet4Addresses.add(address);
                                    if (address instanceof Inet6Address)
                                        inet6Addresses.add(address);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UnknownHostException("Unable to resolve host " + host + " as hosts file " + hostsFile + " not found ");
            }

            List<InetAddress> res;
            // If "preferIPv4Stack" system property is set to "true" then return
            // only IPv4 addresses
            if (preferIPv4Stack) {
                res = inet4Addresses;
            } else {
                // Otherwise, analyse "preferIPv6Addresses" value
                res = switch (preferIPv6Address) {
                    case PREFER_IPV4_VALUE -> concatAddresses(inet4Addresses, inet6Addresses);
                    case PREFER_IPV6_VALUE -> concatAddresses(inet6Addresses, inet4Addresses);
                    default -> inetAddresses;
                };
            }

            if (res.isEmpty())
                throw new UnknownHostException("Unable to resolve host " + host + " in hosts file " + hostsFile);

            return res.toArray(EMPTY_ARRAY);
        }

        private static List<InetAddress> concatAddresses(List<InetAddress> firstPart, List<InetAddress> secondPart) {
            List<InetAddress> result = new ArrayList<>(firstPart);
            result.addAll(secondPart);
            return result;
        }

        private String removeComments(String hostsEntry) {
            String filteredEntry = hostsEntry;
            int hashIndex;

            if ((hashIndex = hostsEntry.indexOf("#")) != -1)
                filteredEntry = hostsEntry.substring(0, hashIndex);
            return filteredEntry;
        }

        private byte [] createAddressByteArray(String addrStr) {
            byte[] addrArray;
            // check if IPV4 address - most likely
            try {
                addrArray = IPAddressUtil.validateNumericFormatV4(addrStr);
            } catch (IllegalArgumentException iae) {
                return null;
            }
            if (addrArray == null)
                addrArray = IPAddressUtil.textToNumericFormatV6(addrStr);
            return addrArray;
        }

        private String extractHostAddr(String hostEntry, String host) {
            String[] mapping = hostEntry.split("\\s+");
            String hostAddr = null;

            if (mapping.length >= 2) {
                // look at the host aliases
                for (int i = 1; i < mapping.length; i++) {
                    if (mapping[i].equalsIgnoreCase(host))
                        hostAddr = mapping[0];
                }
            }
            return hostAddr;
        }
    }

    static final InetAddressImpl impl;

    static {
        // create the impl
        impl = InetAddressImplFactory.create();
        // create name service
        nameService = createNameService();
    }

    private static InetAddress.NameService createNameService() {
        String hostsFileName = GetPropertyAction.privilegedGetProperty("jdk.net.hosts.file");
        InetAddress.NameService theNameService;
        if (hostsFileName != null)
            theNameService = new InetAddress.HostsFileNameService(hostsFileName);
        else
            theNameService = new InetAddress.PlatformNameService();
        return theNameService;
    }

    public static InetAddress getByAddress(String host, byte[] addr) throws UnknownHostException {
        if (host != null && !host.isEmpty() && host.charAt(0) == '[') {
            if (host.charAt(host.length()-1) == ']')
                host = host.substring(1, host.length() -1);
        }
        if (addr != null) {
            if (addr.length == Inet4Address.INADDRSZ) {
                return new Inet4Address(host, addr);
            } else if (addr.length == Inet6Address.INADDRSZ) {
                byte[] newAddr = IPAddressUtil.convertFromIPv4MappedAddress(addr);
                if (newAddr != null)
                    return new Inet4Address(host, newAddr);
                else
                    return new Inet6Address(host, addr);
            }
        }
        throw new UnknownHostException("addr is of illegal length");
    }

    public static InetAddress getByName(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host)[0];
    }

    private static InetAddress getByName(String host, InetAddress reqAddr) throws UnknownHostException {
        return InetAddress.getAllByName(host, reqAddr)[0];
    }

    public static InetAddress[] getAllByName(String host) throws UnknownHostException {
        return getAllByName(host, null);
    }

    private static InetAddress[] getAllByName(String host, InetAddress reqAddr) throws UnknownHostException {
        if (host == null || host.isEmpty()) {
            InetAddress[] ret = new InetAddress[1];
            ret[0] = impl.loopbackAddress();
            return ret;
        }

        validate(host);
        boolean ipv6Expected = false;
        if (host.charAt(0) == '[') {
            // This is supposed to be an IPv6 literal
            if (host.length() > 2 && host.charAt(host.length()-1) == ']') {
                host = host.substring(1, host.length() -1);
                ipv6Expected = true;
            } else {
                // This was supposed to be a IPv6 literal, but it's not
                throw invalidIPv6LiteralException(host, false);
            }
        }

        // Check and try to parse host string as an IP address literal
        if (IPAddressUtil.digit(host.charAt(0), 16) != -1 || (host.charAt(0) == ':')) {
            byte[] addr = null;
            int numericZone = -1;
            String ifname = null;

            if (!ipv6Expected) {
                // check if it is IPv4 address only if host is not wrapped in '[]'
                try {
                    addr = IPAddressUtil.validateNumericFormatV4(host);
                } catch (IllegalArgumentException iae) {
                    var uhe = new UnknownHostException(host);
                    uhe.initCause(iae);
                    throw uhe;
                }
            }
            if (addr == null) {
                // Try to parse host string as an IPv6 literal
                // Check if a numeric or string zone id is present first
                int pos;
                if ((pos = host.indexOf('%')) != -1) {
                    numericZone = checkNumericZone(host);
                    if (numericZone == -1) /* remainder of string must be an ifname */
                        ifname = host.substring(pos + 1);
                }
                if ((addr = IPAddressUtil.textToNumericFormatV6(host)) == null && (host.contains(":") || ipv6Expected))
                    throw invalidIPv6LiteralException(host, ipv6Expected);
            }
            if(addr != null) {
                InetAddress[] ret = new InetAddress[1];
                if (addr.length == Inet4Address.INADDRSZ) {
                    if (numericZone != -1 || ifname != null) {
                        // IPv4-mapped address must not contain zone-id
                        throw new UnknownHostException(host + ": invalid IPv4-mapped address");
                    }
                    ret[0] = new Inet4Address(null, addr);
                } else {
                    if (ifname != null)
                        ret[0] = new Inet6Address(null, addr, ifname);
                    else
                        ret[0] = new Inet6Address(null, addr, numericZone);
                }
                return ret;
            }
        } else if (ipv6Expected) {
            // We were expecting an IPv6 Literal since host string starts
            // and ends with square brackets, but we got something else.
            throw invalidIPv6LiteralException(host, true);
        }
        return getAllByName0(host, reqAddr, true, true);
    }

    private static UnknownHostException invalidIPv6LiteralException(String host, boolean wrapInBrackets) {
        String hostString = wrapInBrackets ? "[" + host + "]" : host;
        return new UnknownHostException(hostString + ": invalid IPv6 address literal");
    }

    public static InetAddress getLoopbackAddress() {
        return impl.loopbackAddress();
    }

    private static int checkNumericZone (String s) throws UnknownHostException {
        int percent = s.indexOf ('%');
        int slen = s.length();
        int digit, zone=0;
        int multmax = Integer.MAX_VALUE / 10; // for int overflow detection
        if (percent == -1)
            return -1;

        for (int i=percent+1; i<slen; i++) {
            char c = s.charAt(i);
            if ((digit = IPAddressUtil.parseAsciiDigit(c, 10)) < 0)
                return -1;
            if (zone > multmax)
                return -1;
            zone = (zone * 10) + digit;
            if (zone < 0)
                return -1;
        }
        return zone;
    }

    private static InetAddress[] getAllByName0 (String host) throws UnknownHostException {
        return getAllByName0(host, true);
    }

    static InetAddress[] getAllByName0 (String host, boolean check) throws UnknownHostException  {
        return getAllByName0(host, null, check, true);
    }

    private static InetAddress[] getAllByName0(String host, InetAddress reqAddr,
                                               boolean check, boolean useCache) throws UnknownHostException  {
        /* If it gets here it is presumed to be a hostname */

        /* make sure the connection to the host is allowed, before we
         * give out a hostname
         */
        if (check) {
            @SuppressWarnings("removal")
            SecurityManager security = System.getSecurityManager();
            if (security != null)
                security.checkConnect(host, -1);
        }

        // remove expired addresses from cache - expirySet keeps them ordered
        // by expiry time so we only need to iterate the prefix of the NavigableSet...
        long now = System.nanoTime();
        for (InetAddress.CachedAddresses caddrs : expirySet) {
            // compare difference of time instants rather than
            // time instants directly, to avoid possible overflow.
            // (see System.nanoTime() recommendations...)
            if ((caddrs.expiryTime - now) < 0L) {
                // ConcurrentSkipListSet uses weakly consistent iterator,
                // so removing while iterating is OK...
                if (expirySet.remove(caddrs))
                    // ... remove from cache
                    cache.remove(caddrs.host, caddrs);
            } else {
                // we encountered 1st element that expires in future
                break;
            }
        }

        // look-up or remove from cache
        Addresses addrs;
        if (useCache) {
            addrs = cache.get(host);
        } else {
            addrs = cache.remove(host);
            if (addrs != null) {
                if (addrs instanceof CachedAddresses) {
                    // try removing from expirySet too if CachedAddresses
                    expirySet.remove(addrs);
                }
                addrs = null;
            }
        }

        if (addrs == null) {
            // create a NameServiceAddresses instance which will look up
            // the name service and install it within cache...
            Addresses oldAddrs = cache.putIfAbsent(host, addrs = new NameServiceAddresses(host, reqAddr));
            if (oldAddrs != null) // lost putIfAbsent race
                addrs = oldAddrs;
        }

        // ask Addresses to get an array of InetAddress(es) and clone it
        return addrs.get().clone();
    }

    static InetAddress[] getAddressesFromNameService(String host, InetAddress reqAddr) throws UnknownHostException {
        InetAddress[] addresses = null;
        UnknownHostException ex = null;

        try {
            addresses = nameService.lookupAllHostAddr(host);
        } catch (UnknownHostException uhe) {
            if (host.equalsIgnoreCase("localhost"))
                addresses = new InetAddress[] { impl.loopbackAddress() };
            else
                ex = uhe;
        }

        if (addresses == null)
            throw ex == null ? new UnknownHostException(host) : ex;

        // More to do?
        if (reqAddr != null && addresses.length > 1 && !addresses[0].equals(reqAddr)) {
            // Find it?
            int i = 1;
            for (; i < addresses.length; i++) {
                if (addresses[i].equals(reqAddr))
                    break;
            }
            // Rotate
            if (i < addresses.length) {
                InetAddress tmp, tmp2 = reqAddr;
                for (int j = 0; j < i; j++) {
                    tmp = addresses[j];
                    addresses[j] = tmp2;
                    tmp2 = tmp;
                }
                addresses[i] = tmp2;
            }
        }

        return addresses;
    }

    public static InetAddress getByAddress(byte[] addr) throws UnknownHostException {
        return getByAddress(null, addr);
    }

    private static final class CachedLocalHost {
        final String host;
        final InetAddress addr;
        final long expiryTime = System.nanoTime() + 5000_000_000L; // now + 5s;

        CachedLocalHost(String host, InetAddress addr) {
            this.host = host;
            this.addr = addr;
        }
    }

    private static volatile CachedLocalHost cachedLocalHost;

    public static InetAddress getLocalHost() throws UnknownHostException {

        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        try {
            // is cached data still valid?
            CachedLocalHost clh = cachedLocalHost;
            if (clh != null && (clh.expiryTime - System.nanoTime()) >= 0L) {
                if (security != null)
                    security.checkConnect(clh.host, -1);
                return clh.addr;
            }

            String local = impl.getLocalHostName();

            if (security != null)
                security.checkConnect(local, -1);

            InetAddress localAddr;
            if (local.equals("localhost")) {
                // shortcut for "localhost" host name
                localAddr = impl.loopbackAddress();
            } else {
                // call getAllByName0 without security checks and
                // without using cached data
                try {
                    localAddr = getAllByName0(local, null, false, false)[0];
                } catch (UnknownHostException uhe) {
                    // Rethrow with a more informative error message.
                    UnknownHostException uhe2 = new UnknownHostException(local + ": " + uhe.getMessage());
                    uhe2.initCause(uhe);
                    throw uhe2;
                }
            }
            cachedLocalHost = new CachedLocalHost(local, localAddr);
            return localAddr;
        } catch (SecurityException e) {
            return impl.loopbackAddress();
        }
    }

    private static native void init();

    static InetAddress anyLocalAddress() {
        return impl.anyLocalAddress();
    }

    static InetAddressImpl loadImpl(String implName) {
        Object impl = null;

        /*
         * Property "impl.prefix" will be prepended to the classname
         * of the implementation object we instantiate, to which we
         * delegate the real work (like native methods).  This
         * property can vary across implementations of the java.
         * classes.  The default is an empty String "".
         */
        String prefix = GetPropertyAction.privilegedGetProperty("impl.prefix", "");
        try {
            @SuppressWarnings("deprecation")
            Object tmp = Class.forName("java.net." + prefix + implName).newInstance();
            impl = tmp;
        } catch (ClassNotFoundException e) {
            System.err.println("Class not found: java.net." + prefix + implName + ":\ncheck impl.prefix property " + "in your properties file.");
        } catch (InstantiationException e) {
            System.err.println("Could not instantiate: java.net." + prefix + implName + ":\ncheck impl.prefix property " + "in your properties file.");
        } catch (IllegalAccessException e) {
            System.err.println("Cannot access class: java.net." + prefix + implName + ":\ncheck impl.prefix property " + "in your properties file.");
        }

        if (impl == null) {
            try {
                @SuppressWarnings("deprecation")
                Object tmp = Class.forName(implName).newInstance();
                impl = tmp;
            } catch (Exception e) {
                throw new Error("System property impl.prefix incorrect");
            }
        }
        return (InetAddressImpl) impl;
    }

    @java.io.Serial
    private void readObjectNoData () {
        if (getClass().getClassLoader() != null)
            throw new SecurityException ("invalid address type");
    }

    private static final jdk.internal.misc.Unsafe UNSAFE = jdk.internal.misc.Unsafe.getUnsafe();

    private static final long FIELDS_OFFSET = UNSAFE.objectFieldOffset(java.net.InetAddress.class, "holder");

    @java.io.Serial
    private void readObject (ObjectInputStream s) throws IOException, ClassNotFoundException {
        if (getClass().getClassLoader() != null)
            throw new SecurityException ("invalid address type");
        ObjectInputStream.GetField gf = s.readFields();
        String host = (String)gf.get("hostName", null);
        int address = gf.get("address", 0);
        int family = gf.get("family", 0);
        if (family != IPv4 && family != IPv6)
            throw new InvalidObjectException("invalid address family type: " + family);

        InetAddressHolder h = new InetAddressHolder(host, address, family);
        UNSAFE.putReference(this, FIELDS_OFFSET, h);
    }

    @java.io.Serial
    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("hostName", String.class),
            new ObjectStreamField("address", int.class),
            new ObjectStreamField("family", int.class),
    };

    @java.io.Serial
    private void writeObject(ObjectOutputStream s) throws IOException {
        if (getClass().getClassLoader() != null)
            throw new SecurityException ("invalid address type");
        ObjectOutputStream.PutField pf = s.putFields();
        pf.put("hostName", holder().getHostName());
        pf.put("address", holder().getAddress());
        pf.put("family", holder().getFamily());
        s.writeFields();
    }

    private static void validate(String host) throws UnknownHostException {
        if (host.indexOf(0) != -1)
            throw new UnknownHostException("NUL character not allowed in hostname");
    }
}

class InetAddressImplFactory {

    static InetAddressImpl create() {
        return InetAddress.loadImpl(isIPv6Supported() ? "Inet6AddressImpl" : "Inet4AddressImpl");
    }

    static native boolean isIPv6Supported();
}
