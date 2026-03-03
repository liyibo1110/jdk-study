package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 用于读取、写入、映射和操作文件的通道。
 * 文件通道是连接到文件的可寻址字节通道（SeekableByteChannel）。它在文件中具有当前位置，该位置可被查询和修改。
 * 文件本身包含可读写的可变长度字节序列，其当前大小可被查询。当写入字节超出当前大小时，文件会增大；截断文件时，文件会缩小。
 * 文件可能还包含访问权限、内容类型、最后修改时间等元数据，但本类未定义访问元数据的方法。
 *
 * 除ByteBuffer常见的读取、写入和关闭操作外，本类还定义了以下文件专属操作：
 * 1、字节可在文件的绝对位置进行读写，且不影响Channel的position。
 * 2、文件区域可直接映射到内存中，对于大型文件，这通常比调用常规读写方法高效得多。
 * 3、对文件的更新可强制写入底层存储设备，确保系统崩溃时数据不丢失。
 * 4、字节可在文件与其他Channel间双向传输，多数操作系统能优化此过程，实现与文件系统缓存间的高速直接传输。
 * 5、文件区域可被锁定，防止其他程序访问。
 *
 * FileChannel可安全地供多个并发线程使用。根据通道接口的规定，close方法可在任何时候调用。
 * 在任意时刻，仅允许存在一项涉及通道位置或可能改变其文件大小的操作；若在首项操作尚未完成时尝试启动第二项此类操作，系统将阻塞直至首项操作完成。
 * 其他操作（特别是显式指定位置的操作）可并发执行；实际并发性取决于底层实现，故未作明确定义。
 *
 * 本类实例提供的文件视图，保证与同一程序中其他实例提供的相同文件视图保持一致。
 * 然而，由于底层操作系统的缓存机制及网络文件系统协议造成的延迟，本类实例提供的视图可能与其他并发程序所见视图一致，也可能不一致。
 * 此特性与其他程序的编程语言无关，亦不取决于其运行于同一机器或不同机器。此类不一致的具体表现形式因系统而异，故未作规定。
 *
 * FileChannel通过调用本类定义的open方法之一创建。
 * FileChannel也可通过调用现有FileInputStream、FileOutputStream或RandomAccessFile对象的getChannel方法获取，该方法返回与底层文件关联的FileChannel。
 * 当FileChannel源自现有流或随机访问文件时，其状态与调用getChannel方法返回通道的对象状态紧密关联。
 * 无论通过显式操作还是读写字节，改变通道位置都会改变原始对象的文件位置，反之亦然。
 * 通过FileChannel修改文件长度将改变原始对象所见长度，反之亦然。
 * 通过写入字节修改文件内容将改变原始对象所见内容，反之亦然。
 *
 * 本类在多个位置明确要求实例需处于“只读”、“只写”或“读写”状态。
 * 通过FileInputStream实例的getChannel方法获取的通道将处于只读状态；通过FileOutputStream实例的getChannel方法获取的通道将处于只写状态。
 * 最后，通过RandomAccessFile实例的getChannel方法获取的通道：若实例以“r”模式创建则为只读通道，若以“rw”模式创建则为读写通道。
 *
 * 以写入模式打开的文件通道可能处于追加模式，例如当其源自通过调用FileOutputStream(File,boolean)构造器并为第二个参数传入true创建的文件输出流时。
 * 在此模式下，每次相对写入操作都会先将位置移至文件末尾，再写入请求数据。位置推进与数据写入是否作为单个原子操作执行取决于系统，因此未作规定。
 * @author liyibo
 * @date 2026-03-02 22:06
 */
public abstract class FileChannel extends AbstractInterruptibleChannel
        implements SeekableByteChannel, GatheringByteChannel, ScatteringByteChannel {
    protected FileChannel() {}

    public static java.nio.channels.FileChannel open(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        FileSystemProvider provider = path.getFileSystem().provider();
        return provider.newFileChannel(path, options, attrs);
    }

    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];

    public static java.nio.channels.FileChannel open(Path path, OpenOption... options) throws IOException {
        Set<OpenOption> set;
        if(options.length == 0) {
            set = Collections.emptySet();
        }else {
            set = new HashSet<>();
            Collections.addAll(set, options);
        }
        return open(path, set, NO_ATTRIBUTES);
    }

    public abstract int read(ByteBuffer dst) throws IOException;

    public abstract long read(ByteBuffer[] dsts, int offset, int length) throws IOException;

    public final long read(ByteBuffer[] dsts) throws IOException {
        return read(dsts, 0, dsts.length);
    }

    public abstract int write(ByteBuffer src) throws IOException;

    public abstract long write(ByteBuffer[] srcs, int offset, int length) throws IOException;

    public final long write(ByteBuffer[] srcs) throws IOException {
        return write(srcs, 0, srcs.length);
    }

    // -- Other operations --

    public abstract long position() throws IOException;

    public abstract FileChannel position(long newPosition) throws IOException;

    public abstract long size() throws IOException;

    public abstract FileChannel truncate(long size) throws IOException;

    /**
     * 强制将此通道文件的所有更新写入包含该文件的存储设备。
     * 若该通道文件位于本地存储设备上，则当此方法返回时，可确保自通道创建以来或自上次调用此方法以来对文件所做的所有修改均已写入该设备。此特性有助于在系统崩溃时防止关键信息丢失。
     * 若文件未驻留本地设备，则不作此类保证。
     *
     * metaData参数可用于限制本方法所需执行的I/O操作次数。
     * 传入false表示仅需将文件内容更新写入存储；传入true则表示必须同时写入文件内容与元数据更新，通常需额外执行至少一次I/O操作。
     * 该参数是否实际生效取决于底层操作系统，故未作具体规定。
     *
     * 即使通道仅以读取模式打开，调用此方法仍可能触发I/O操作。
     * 例如某些操作系统会将最后访问时间作为文件元数据保存，且每次读取文件时都会更新该时间。此行为是否实际发生取决于系统，故未作具体规定。
     *
     * 本方法仅保证强制写入通过本类定义方法对通道文件所做的修改。
     * 对于通过调用map方法获取的映射字节缓冲区内容修改，是否强制写入则不作保证。调用映射字节缓冲区的force方法将强制写入缓冲区内容的修改。
     */
    public abstract void force(boolean metaData) throws IOException;

    public abstract long transferTo(long position, long count, WritableByteChannel target) throws IOException;

    public abstract long transferFrom(ReadableByteChannel src, long position, long count) throws IOException;

    public abstract int read(ByteBuffer dst, long position) throws IOException;

    public abstract int write(ByteBuffer src, long position) throws IOException;

    // -- Memory-mapped buffers --

    /**
     * 文件映射模式（是个枚举）
     */
    public static class MapMode {

        public static final MapMode READ_ONLY = new MapMode("READ_ONLY");
        public static final MapMode READ_WRITE = new MapMode("READ_WRITE");
        public static final MapMode PRIVATE = new MapMode("PRIVATE");

        private final String name;

        private MapMode(String name) {
            this.name = name;
        }

        public String toString() {
            return name;
        }
    }

    /**
     * 将给Channel文件的某个区域直接映射到内存中。
     * 模式参数指定文件区域的映射方式，可选模式如下：
     * 只读：任何修改结果缓冲区的尝试都会抛出java.nio.ReadOnlyBufferException。（MapMode.READ_ONLY）
     * 读写：对结果缓冲区所做的修改最终会传播到文件中；这些修改可能被映射同一文件的其他程序看到，也可能不会。（MapMode.READ_WRITE）
     * 私有模式：对结果缓冲区的修改不会传播到文件，也不会被映射同一文件的其他程序察觉；相反，这些修改会导致缓冲区被修改部分创建私有副本。(MapMode.PRIVATE)
     */
    public abstract MappedByteBuffer map(MapMode mode, long position, long size) throws IOException;

    // -- Locks --

    /**
     * 获取此通道文件中指定区域的锁。
     * 调用此方法将阻塞，直至该区域被锁定、通道关闭或调用线程被中断（以先发生者为准）。
     * 若在调用过程中该通道被其他线程关闭，则会抛出异步关闭异常（AsynchronousCloseException）。
     *
     * 若调用线程在等待获取锁期间被中断，则其中断状态将被设置并抛出FileLockInterruptionException异常。
     * 若调用此方法时调用者的中断状态已被设置，则该异常将立即抛出；线程的中断状态不会被更改。
     * 由位置和大小参数指定的区域不必包含在实际底层文件内，甚至无需与其重叠。
     * 锁定区域大小固定；若初始锁定区域包含文件末尾，而文件后续增长超出该区域，则新增部分将不受锁保护。
     * 若预期文件会增长且需锁定整个文件，应锁定起始位置为零且不小于预期最大文件大小的区域。
     * 零参数的lock()方法仅锁定大小为Long的区域。MAX_VALUE。
     *
     * 部分操作系统不支持共享锁，此时共享锁请求将自动转换为独占锁请求。可通过调用锁对象的 isShared 方法检测新获取的锁类型。
     * 文件锁由整个 Java 虚拟机统一持有，不适用于控制同一虚拟机内多线程对文件的访问权限。
     */
    public abstract FileLock lock(long position, long size, boolean shared) throws IOException;

    public final FileLock lock() throws IOException {
        return lock(0L, Long.MAX_VALUE, false);
    }

    public abstract FileLock tryLock(long position, long size, boolean shared) throws IOException;

    public final FileLock tryLock() throws IOException {
        return tryLock(0L, Long.MAX_VALUE, false);
    }
}
