package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.util.Set;

/**
 * 连接到网络套接字的Channel，实现此接口的Channel即为连接到网络套接字的Channel。
 * bind方法用于将套接字绑定到本地地址，getLocalAddress方法返回套接字绑定的地址，setOption和getOption方法用于设置和查询套接字选项。
 * 此接口的实现应指定其支持的套接字选项。
 *
 * bind和setOption方法在无其他返回值时，被指定为返回调用它们的网络通道。这使得方法调用能够进行链式调用。
 * 本接口的实现应专门化返回类型，以便在实现类上进行链式方法调用。
 * @author liyibo
 * @date 2026-03-02 18:32
 */
public interface NetworkChannel extends Channel {
    /**
     * 将Channel的套接字绑定到本地地址。
     * 此方法用于在套接字与本地地址之间建立关联。关联建立后，套接字将保持绑定状态直至通道关闭。
     * 若本地参数值为null，则套接字将绑定到自动分配的地址。
     */
    NetworkChannel bind(SocketAddress local) throws IOException;

    /**
     * 返回此Channel的套接字所绑定的套接字地址。
     * 当Channel绑定到互联网协议套接字地址时，此方法的返回值类型为java.net.InetSocketAddress。
     */
    SocketAddress getLocalAddress() throws IOException;

    /**
     * 设置特定name的SocketOption
     */
    <T> NetworkChannel setOption(SocketOption<T> name, T value) throws IOException;

    /**
     * 根据特定name获取SocketOption
     */
    <T> T getOption(SocketOption<T> name) throws IOException;

    /**
     * 返回该Channel支持的SocketOption
     * 即使Channel已关闭，该方法仍会继续返回SocketOption。
     */
    Set<SocketOption<?>> supportedOptions();
}
