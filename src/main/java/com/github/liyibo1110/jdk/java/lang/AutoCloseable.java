package com.github.liyibo1110.jdk.java.lang;

/**
 * 一种可能持有资源（如文件或Socket实例）直至关闭的对象。
 * 当退出包含该对象声明的try-with-resources代码块其，其close()方法会自动调用。
 * 此设计确保资源会及时释放，避免因资源耗尽引发的异常和错误。
 *
 * 基类实现AutoCloseable接口是很常见的，即使并非其所有子类或实例都会持有可释放的资源。
 * 对于必须完全通用的代码，或已知需要释放AutoCloseable实例资源的情况，建议使用try-with-resources结构。
 * 但当使用java.util.stream.Stream等同时支持基于I/O和非基于I/O形式的设施时，在采用非基于I/O的形式时通常无需使用try-with-resources代码块。
 * @author liyibo
 * @date 2026-02-26 00:36
 */
public interface AutoCloseable {

    /**
     * 关闭此资源，释放所有底层资源，此方法会在由try-with-resources语句管理的对象上自动调用。
     * 尽管该接口方法声明会抛出异常，但强烈建议实现者声明具体的close方法实现，使其抛出更具体的异常，或者在关闭操作不可能失败时完全不抛出异常。
     * 实现者需特别关注可能导致关闭操作失败的情形。强烈建议在抛出异常前先释放底层资源，并在内部标记该资源为已关闭状态。
     * 由于close方法通常仅被调用一次，此举可确保资源及时释放。此外还能减少因资源被其他资源封装或自身被封装时可能引发的问题。
     *
     * 强烈建议该接口的实现者避免让close方法抛出InterruptedException。该异常与线程中断状态相关联，若被抑制则可能引发运行时异常行为。
     * 更普遍而言，若异常被抑制会导致问题，则AutoCloseable的close方法不应抛出该异常。
     *
     * 需注意：与java.io.Closeable的close方法不同，此close方法无需具备幂等性。
     * 换言之，多次调用该close方法可能产生可见副作用，而Closeable的close方法若被多次调用则必须保持无影响。
     * 尽管如此，强烈建议该接口的实现者使close方法具有幂等性。
     */
    void close() throws Exception;
}
