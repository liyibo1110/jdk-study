package com.github.liyibo1110.jdk.java.lang;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Throwable类是Java语言中所有错误和异常的基类。只有该类（或其子类）的实例对象才能被Java虚拟机抛出，或通过Java的throw语句抛出。
 * 同样地，只有该类或其子类才能作为catch子句中的参数类型。
 * 为实现编译时异常检查，Throwable及其所有未同时继承自RuntimeException或Error的子类均被视为检查异常。
 *
 * Error与Exception这两个子类的实例通常用于指示异常情况的发生。这些实例通常在异常发生时动态创建，以便包含相关信息（如堆栈跟踪数据）。
 *
 * Throwable包含其创建时线程执行堆栈的快照，还可包含提供错误详细信息的错误消息字符串。
 * 随着时间推移，Throwable可抑制其他异常的传播。最后，Throwable还可包含cause：导致该异常构造的另一个Throwable。
 * 这种因果信息的记录机制被称为链式异常功能，因为根因本身可能存在更深层的根因，由此形成由逐级引发的异常“链条”。
 *
 * 抛出异常的根因之一在于：抛出该异常的类构建于底层抽象之上，当底层操作失败时，上层操作随之失效。
 * 若允许底层抛出的异常向上层传播，将构成不良设计——这类异常通常与上层提供的抽象无关。
 * 更甚者，若底层异常属于受检查异常，此举将使上层API与底层实现细节绑定。
 * 抛出“包装异常”（即包含根源信息的异常）能让上层在不引发上述缺陷的前提下，向调用方传递失败细节。
 * 这种设计保留了上层实现的灵活性——无需修改API（特别是方法抛出的异常集）即可变更内部实现。
 *
 * 抛出异常可能包含原因的第二个原因是：抛出该异常的方法必须遵循通用接口规范，而该接口不允许方法直接抛出原因。
 * 例如，假设持久化集合遵循Collection接口，其持久化功能基于java.io实现。若add方法内部可能抛出IOException：
 * 实现可通过将IOException包装为适当的未检查异常，在符合Collection接口要求的同时向调用方传递IOException的细节（持久化集合的规范应说明其可抛出此类异常）。
 *
 * 可通过两种方式将cause关联至抛出异常：采用将原因作为参数的构造函数，或通过initCause(Throwable)方法。
 * 新创建的抛出类若需支持关联原因，应提供接收原因参数的构造器，并（可能间接）委托给接收原因参数的Throwable构造器。
 * 由于initCause方法为 public，它允许将原因关联至任何抛出类，甚至包括在Throwable添加异常链机制前实现的“旧式抛出类”。
 *
 * 按惯例，Throwable类及其子类应提供两个构造器：无参数构造器和接受字符串参数（用于生成详细信息消息）的构造器。
 * 此外，可能需要关联原因的子类还应提供两个额外构造器：接受Throwable对象（作为原因）的构造器，以及同时接受字符串（详细信息消息）和Throwable对象（作为原因）的构造器。
 *
 * 上面只是翻译，学习这个Throwable要明确它要解决的三个问题：
 * 1、如何表达错误本身：message
 * 2、如果保存错误上下文：stack trace
 * 3、如果描述错误之间的关系：cause和suppressed
 * @author liyibo
 * @date 2026-03-08 21:37
 */
public class Throwable implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = -3042686055658047285L;

    /**
     * JVM将栈回溯的某些指示信息保存在此对象中，数据来自JVM操作
     */
    private transient Object backtrace;

    /**
     * 为异常提供可读的上下文信息。
     */
    private String detailMessage;

    /**
     * 用于延迟初始化仅用于序列化的哨兵对象
     */
    private static class SentinelHolder {
        public static final StackTraceElement STACK_TRACE_ELEMENT_SENTINEL =
                new StackTraceElement("", "", null, Integer.MIN_VALUE);

        public static final StackTraceElement[] STACK_TRACE_SENTINEL = new StackTraceElement[] {STACK_TRACE_ELEMENT_SENTINEL};
    }

    /** 空的StackTrace实例 */
    private static final StackTraceElement[] UNASSIGNED_STACK = new StackTraceElement[0];

    /**
     * 异常链，在Java1.4引入，非常重要。
     * 注意这个有一个sentinel，即哨兵值的设计：
     * cause == this：cause还没有初始化
     * cause == null：没有cause
     * cause == 其他异常：有cause
     */
    private Throwable cause = this;

    /**
     * 异常栈容器，但真正的数据来自backtrace和depth字段，它们是由JVM来填充的
     */
    private StackTraceElement[] stackTrace = UNASSIGNED_STACK;  // 注意初始值也是个哨兵值

    /** 数据来自JVM操作 */
    private transient int depth;

    private static final List<Throwable> SUPPRESSED_SENTINEL = Collections.emptyList();

    private List<Throwable> suppressedExceptions = SUPPRESSED_SENTINEL;

    private static final String NULL_CAUSE_MESSAGE = "Cannot suppress a null exception.";

    private static final String SELF_SUPPRESSION_MESSAGE = "Self-suppression not permitted";

    private static final String CAUSE_CAPTION = "Caused by: ";

    private static final String SUPPRESSED_CAPTION = "Suppressed: ";

    public Throwable() {
        fillInStackTrace();
    }

    public Throwable(String message) {
        fillInStackTrace();
        detailMessage = message;
    }

    public Throwable(String message, Throwable cause) {
        fillInStackTrace();
        detailMessage = message;
        this.cause = cause;
    }

    public Throwable(Throwable cause) {
        fillInStackTrace();
        detailMessage = (cause == null ? null : cause.toString());
        this.cause = cause;
    }

    protected Throwable(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        if(writableStackTrace)
            fillInStackTrace();
        else
            stackTrace = null;
        detailMessage = message;
        this.cause = cause;
        if(!enableSuppression)
            suppressedExceptions = null;
    }

    public String getMessage() {
        return detailMessage;
    }

    public String getLocalizedMessage() {
        return getMessage();
    }

    /**
     * 注意cause字段的哨兵语义：cause == this等同于cause还未初始化，返回时也算null
     */
    public synchronized Throwable getCause() {
        return (cause==this ? null : cause);
    }

    public synchronized Throwable initCause(Throwable cause) {
        // initCause只能被调用一次，如果cause已经不是未初始化状态了，则抛出IllegalStateException
        if(this.cause != this)
            throw new IllegalStateException("Can't overwrite cause with " + Objects.toString(cause, "a null"), this);
        // cause很明显也不能是自己
        if(cause == this)
            throw new IllegalArgumentException("Self-causation not permitted", this);
        this.cause = cause;
        return this;
    }

    final void setCause(Throwable t) {
        this.cause = t;
    }

    public String toString() {
        String s = getClass().getName();
        String message = getLocalizedMessage();
        return message != null ? s + ": " + message : s;
    }

    public void printStackTrace() {
        printStackTrace(System.err);
    }

    public void printStackTrace(PrintStream s) {
        printStackTrace(new WrappedPrintStream(s));
    }

    /**
     * 重要的方法：拼接并输出stackTrace信息
     */
    private void printStackTrace(PrintStreamOrWriter s) {
        /**
         * 这玩意儿是一个重要的防护机制，防止异常间形成循环引用。
         * 例如：A cause B，同时B cause A，如果没有保护，后面对cause的递归打印就会变成：A -> B -> A -> B -> A，即无限循环
         * 所以这个dejaVu应该记录已经打印过的异常（后面用到dejaVu的代码挺简单的）
         *
         * 还有一个点就是这里用了IdentityHashMap配合SetFromMap实现（并不是HashSet），是因为异常子类可能会重写equals方法，
         * 如果用普通的HashSet，在后面比较相等的时候可能equals永远是true，
         * 而IdentityHashMap在比较时会直接使用==来比较。
         */
        Set<Throwable> dejaVu = Collections.newSetFromMap(new IdentityHashMap<>());
        dejaVu.add(this);   // 先加自己

        synchronized(s.lock()) {
            s.println(this);    // 先输出自己，会调用实际类的toString

            /**
             * 获取stackTrace并循环输出
             * 例如：at com.foo.Test.main(Test.java:10)
             */
            StackTraceElement[] trace = getOurStackTrace();
            for(StackTraceElement traceElement : trace)
                s.println("\tat " + traceElement);

            /**
             * 接下来是输出suppressed exceptions
             * 就是JDK7出的try-with-resources这种，如果自动调用的close里面有异常了，则会被记录为suppressed（即被抑制的异常）
             *
             * 打印使用了printEnclosedStackTrace，因为suppressed的打印格式不同（多一个缩进，里面文字也不同）
             */
             for(Throwable se : getSuppressed())
                 se.printEnclosedStackTrace(s, trace, SUPPRESSED_CAPTION, "\t", dejaVu);

             // 最后输出cause
             Throwable ourCause = getCause();
             if(ourCause != null)
                 ourCause.printEnclosedStackTrace(s, trace, CAUSE_CAPTION, "", dejaVu);
        }
    }

    /**
     * 将stackTrace作为指定stackTrace跟踪的封闭异常打印出来
     */
    private void printEnclosedStackTrace(Throwable.PrintStreamOrWriter s,
                                         StackTraceElement[] enclosingTrace,
                                         String caption,
                                         String prefix,
                                         Set<java.lang.Throwable> dejaVu) {
        assert Thread.holdsLock(s.lock());
        if(dejaVu.contains(this)) {
            s.println(prefix + caption + "[CIRCULAR REFERENCE:  + this + ]");
        }else {
            dejaVu.add(this);
            StackTraceElement[] trace = getOurStackTrace();

            // 合并并简写相同的内容

            /**
             * 优化点，计算两个stack trace尾部相同的帧数，例如：
             * Exception A
             *     at foo()
             *     at bar()
             *     at main()
             *
             * Exception B
             *     at baz()
             *     at foo()
             *     at bar()
             *     at main()
             * 可见它们最后3行内容是一样的
             *
             * 最终优化后只打印：
             * Exception B
             *     at baz()
             *     ... 3 more
             */
            int m = trace.length - 1;
            int n = enclosingTrace.length - 1;
            while(m >= 0 && n >=0 && trace[m].equals(enclosingTrace[n])) {
                m--;
                n--;
            }
            int framesInCommon = trace.length - 1 - m;

            // 输出stack trace
            s.println(prefix + caption + this);
            for(int i = 0; i <= m; i++) // 打印非重复部分
                s.println(prefix + "\tat " + trace[i]);
            if(framesInCommon != 0)
                s.println(prefix + "\t... " + framesInCommon + " more");

            // 输出suppressed exception
            for(Throwable se : getSuppressed())
                se.printEnclosedStackTrace(s, trace, SUPPRESSED_CAPTION, prefix + "\t", dejaVu);

            // 输出cause
            Throwable ourCause = getCause();
            if(ourCause != null)
                ourCause.printEnclosedStackTrace(s, trace, CAUSE_CAPTION, prefix, dejaVu);
        }
    }

    public void printStackTrace(PrintWriter s) {
        printStackTrace(new WrappedPrintWriter(s));
    }

    private abstract static class PrintStreamOrWriter {
        /** 返回使用此StreamOrWriter要锁定的对象 */
        abstract Object lock();

        /** 将给定字符串作为一行，打印到此StreamOrWriter上 */
        abstract void println(Object obj);
    }

    private static class WrappedPrintStream extends PrintStreamOrWriter {
        private final PrintStream printStream;

        WrappedPrintStream(PrintStream printStream) {
            this.printStream = printStream;
        }

        @Override
        Object lock() {
            return printStream;
        }

        @Override
        void println(Object obj) {
            printStream.println(obj);
        }
    }

    private static class WrappedPrintWriter extends PrintStreamOrWriter {
        private final PrintWriter printWriter;

        WrappedPrintWriter(PrintWriter printWriter) {
            this.printWriter = printWriter;
        }

        Object lock() {
            return printWriter;
        }

        void println(Object o) {
            printWriter.println(o);
        }
    }

    /**
     * 填充执行堆栈跟踪，此方法在本Throwable对象内记录当前线程堆栈帧的当前状态信息。
     * 若此Throwable的堆栈跟踪不可写，调用此方法将无效。
     *
     * 注意这个操作其实比较重（即创建异常，就需要调用native方法，微秒时间级，普通对象创建是纳秒时间级，差了100倍），
     * 因此很多开源项目都会针对new Throwable()这种写法进行优化。
     */
    public synchronized Throwable fillInStackTrace() {
        if(stackTrace != null || backtrace != null) {
            fillInStackTrace(0);
            stackTrace = UNASSIGNED_STACK;
        }
        return this;
    }

    private native Throwable fillInStackTrace(int dummy);

    /**
     * 提供对printStackTrace()打印的堆栈跟踪信息的程序化访问。返回一个堆栈跟踪元素数组，每个元素代表一个堆栈帧。
     * 数组的零索引元素（假设数组长度不为零）代表栈顶，即序列中的最后一次方法调用。通常，此处即为该抛出对象创建并抛出的位置。
     * 数组的末尾元素（假设数组长度不为零）代表栈底，即序列中的首次方法调用。
     * 某些虚拟机在特定情况下可能省略堆栈跟踪中的一个或多个堆栈帧。极端情况下，若虚拟机缺乏该抛出对象的堆栈跟踪信息，允许该方法返回零长度数组。通常而言，本方法返回的数组将包含与 printStackTrace 方法打印的每个帧对应的元素。对返回数组的写入操作不会影响后续对本方法的调用。
     */
    public StackTraceElement[] getStackTrace() {
        return getOurStackTrace().clone();
    }

    private synchronized StackTraceElement[] getOurStackTrace() {
        /**
         * 如果此为首次调用该方法，则使用backtrace初始化stackTrace字段
         */
        if(stackTrace == UNASSIGNED_STACK || (stackTrace == null && backtrace != null))
            stackTrace = StackTraceElement.of(this, depth);
        else if(stackTrace == null)
            return UNASSIGNED_STACK;
        return stackTrace;
    }


    @java.io.Serial
    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        s.defaultReadObject();     // read in all fields

        // Set suppressed exceptions and stack trace elements fields
        // to marker values until the contents from the serial stream
        // are validated.
        List<Throwable> candidateSuppressedExceptions = suppressedExceptions;
        suppressedExceptions = SUPPRESSED_SENTINEL;

        StackTraceElement[] candidateStackTrace = stackTrace;
        stackTrace = UNASSIGNED_STACK.clone();

        if (candidateSuppressedExceptions != null) {
            int suppressedSize = validateSuppressedExceptionsList(candidateSuppressedExceptions);
            if (suppressedSize > 0) { // Copy valid Throwables to new list
                var suppList  = new ArrayList<Throwable>(Math.min(100, suppressedSize));

                for (Throwable t : candidateSuppressedExceptions) {
                    // Enforce constraints on suppressed exceptions in
                    // case of corrupt or malicious stream.
                    Objects.requireNonNull(t, NULL_CAUSE_MESSAGE);
                    if (t == this)
                        throw new IllegalArgumentException(SELF_SUPPRESSION_MESSAGE);
                    suppList.add(t);
                }
                // If there are any invalid suppressed exceptions,
                // implicitly use the sentinel value assigned earlier.
                suppressedExceptions = suppList;
            }
        } else {
            suppressedExceptions = null;
        }

        /*
         * For zero-length stack traces, use a clone of
         * UNASSIGNED_STACK rather than UNASSIGNED_STACK itself to
         * allow identity comparison against UNASSIGNED_STACK in
         * getOurStackTrace.  The identity of UNASSIGNED_STACK in
         * stackTrace indicates to the getOurStackTrace method that
         * the stackTrace needs to be constructed from the information
         * in backtrace.
         */
        if (candidateStackTrace != null) {
            // Work from a clone of the candidateStackTrace to ensure
            // consistency of checks.
            candidateStackTrace = candidateStackTrace.clone();
            if (candidateStackTrace.length >= 1) {
                if (candidateStackTrace.length == 1 &&
                        // Check for the marker of an immutable stack trace
                        Throwable.SentinelHolder.STACK_TRACE_ELEMENT_SENTINEL.equals(candidateStackTrace[0])) {
                    stackTrace = null;
                } else { // Verify stack trace elements are non-null.
                    for (StackTraceElement ste : candidateStackTrace) {
                        Objects.requireNonNull(ste, "null StackTraceElement in serial stream.");
                    }
                    stackTrace = candidateStackTrace;
                }
            }
        }
        // A null stackTrace field in the serial form can result from
        // an exception serialized without that field in older JDK
        // releases; treat such exceptions as having empty stack
        // traces by leaving stackTrace assigned to a clone of
        // UNASSIGNED_STACK.
    }

    private int validateSuppressedExceptionsList(List<Throwable> deserSuppressedExceptions) throws IOException {
        if(!Object.class.getModule().equals(deserSuppressedExceptions.getClass().getModule())) {
            throw new StreamCorruptedException("List implementation not in base module.");
        }else {
            int size = deserSuppressedExceptions.size();
            if(size < 0)
                throw new StreamCorruptedException("Negative list size reported.");
            return size;
        }
    }

    @java.io.Serial
    private synchronized void writeObject(ObjectOutputStream s)
            throws IOException {
        // Ensure that the stackTrace field is initialized to a
        // non-null value, if appropriate.  As of JDK 7, a null stack
        // trace field is a valid value indicating the stack trace
        // should not be set.
        getOurStackTrace();

        StackTraceElement[] oldStackTrace = stackTrace;
        try {
            if(stackTrace == null)
                stackTrace = Throwable.SentinelHolder.STACK_TRACE_SENTINEL;
            s.defaultWriteObject();
        } finally {
            stackTrace = oldStackTrace;
        }
    }

    /**
     * 将指定的异常附加到为传递此异常而被抑制的异常列表中。此方法是线程安全的，通常由try-with-resources语句（自动且隐式地）调用。
     * 除非通过构造函数禁用，否则抑制行为处于启用状态。当抑制被禁用时，此方法除了验证其参数外不会执行任何操作。
     *
     * 需注意：当一个异常引发另一个异常时，通常会先捕获前一个异常，再抛出后一个异常作为响应。
     * 换言之，两者存在因果关联。但某些情况下，两个独立异常可能在并列代码块中被抛出——尤其在try-with-resources语句的try代码块与编译器生成的finally代码块（用于关闭资源）之间。
     * 在此类场景中，仅能传播其中一个抛出的异常。在try-with-resources语句中，当存在两个此类异常时，来自try代码块的异常将被传播，而来自finally代码块的异常会被添加到try异常的抑制异常列表中。
     * 随着异常沿栈回溯，它可能累积多个被抑制的异常。
     *
     * 异常可能同时存在被抑制的异常，也可能由其他异常引发。
     * 异常是否具有引发原因在创建时即具有语义确定性，而异常是否会抑制其他异常通常仅在抛出后才能确定。
     * 需注意，当存在多个同级异常且仅能传播其中一个时，程序员编写的代码也可通过调用此方法实现类似效果。
     */
    public final synchronized void addSuppressed(Throwable exception) {
        if(exception == this)
            throw new IllegalArgumentException(SELF_SUPPRESSION_MESSAGE, exception);
        Objects.requireNonNull(exception, NULL_CAUSE_MESSAGE);
        if(suppressedExceptions == null)    // 注意为null代表不记录，值为SUPPRESSED_SENTINEL才是未初始化的标记值
            return;
        if(suppressedExceptions == SUPPRESSED_SENTINEL)
            suppressedExceptions = new ArrayList<>(1);
        suppressedExceptions.add(exception);
    }

    private static final Throwable[] EMPTY_THROWABLE_ARRAY = new Throwable[0];

    /**
     * 返回一个数组，其中包含所有被抑制的异常（通常由try-with-resources语句抑制），以便传递此异常。
     * 如果没有异常被抑制或抑制功能被禁用，则返回空数组。
     * 此方法是线程安全的。对返回数组的写入操作不会影响此方法的后续调用。
     */
    public final synchronized Throwable[] getSuppressed() {
        if(suppressedExceptions== SUPPRESSED_SENTINEL || suppressedExceptions == null)
            return EMPTY_THROWABLE_ARRAY;
        else
            return suppressedExceptions.toArray(EMPTY_THROWABLE_ARRAY);
    }
}
