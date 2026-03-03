package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.channels.MembershipKey;
import java.nio.channels.NetworkChannel;

/**
 * 支持互联网协议（IP）多播的网络通道。
 * IP多播是指将IP数据报传输至由单一目标地址标识的零个或多个主机组成的组成员。
 * 对于连接至IPv4套接字的通道，底层操作系统可选支持RFC 2236：互联网组管理协议第2版（IGMPv2）。
 * 当支持IGMPv2时，操作系统还可能额外支持RFC 3376：互联网组管理协议第3版（IGMPv3）所规定的源过滤功能。
 * 对于连接至IPv6套接字的通道，对应标准为RFC 2710：IPv6组播监听器发现（MLD）及RFC 3810：IPv6组播监听器发现第2版（MLDv2）。
 *
 * join(InetAddress, NetworkInterface)方法用于加入组并接收发送到该组的所有多播数据报。
 * 一个通道可加入多个多播组，也可在多个接口上加入同一个组。通过调用返回的MembershipKey上的drop方法可解除组成员身份。
 * 若底层平台支持源地址过滤，则可使用block和unblock方法来阻止或允许来自特定源地址的多播数据报。
 *
 * join(InetAddress, NetworkInterface, InetAddress)方法用于开始接收发往源地址与给定源地址匹配的组的数据报。
 * 若底层平台不支持源地址过滤，该方法将抛出UnsupportedOperationException。成员资格具有累积性，可通过相同组和接口再次调用本方法以接收其他源地址的数据报。该方法返回的 MembershipKey 表示接收来自指定源地址数据报的成员资格。调用该密钥的 drop 方法将解除成员资格，从而停止接收该源地址的数据报。
 * @author liyibo
 * @date 2026-03-02 19:15
 */
public interface MulticastChannel extends NetworkChannel {

    /**
     * 关闭此Channel，若该Channel属于多播组成员，则会解除成员身份，返回后，成员身份key将失效。
     * 此方法的其他行为完全遵循通道接口的规范。
     */
    @Override
    void close() throws IOException;

    /**
     * 加入多播组以开始接收发往该组的所有数据报，并返回成员资格密钥。
     * 若当前通道已在指定接口上加入该组以接收所有数据报，则返回代表该成员资格的成员密钥，
     * 否则，该通道将加入该组并返回生成的新的成员密钥。生成的成员密钥不具有源特定性。
     *
     * 一个多播通道可加入多个多播组，包括在不同接口上加入同一组。实现方可能对同时加入的组数设置上限。
     */
    MembershipKey join(InetAddress group, NetworkInterface interf) throws IOException;

    /**
     * 加入多播组以开始接收来自指定源地址发送到该组的数据报。
     * 若该通道当前已在指定接口上加入该组以接收来自指定源地址的数据报，则返回代表该成员资格的成员资格密钥，
     * 否则，该通道将加入该组，并返回生成的新的成员资格密钥。生成的成员资格密钥具有源地址特异性。
     *
     * 成员资格具有累积性，可再次调用本方法（使用相同组和接口）以接收其他源地址发往该组的数据报。
     */
    MembershipKey join(InetAddress group, NetworkInterface interf, InetAddress source) throws IOException;
}
