package com.github.liyibo1110.jdk.java.lang;

import jdk.internal.event.ProcessStartEvent;
import sun.security.action.GetPropertyAction;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 用来描述：如何启动一个子进程，并在调用start()时真正创建该子进程并返回Process，要配置的元素包括：
 * 1、要执行的命令
 * 2、执行命令的参数
 * 3、工作目录
 * 4、环境变量
 * 5、标准输入/输出/错误如何重定向
 * 6、是否把stderr合并到stdout
 * 基本功能这里就不多写了，值得注意的高级功能是：做管道式或批处理式系统继承，例如充当调度者，去编排多个外部命令。
 *
 * @author liyibo
 * @date 2026-03-26 15:16
 */
public final class ProcessBuilder {

    /** 要启动的命令以其参数列表，例如["java", "-version"] */
    private List<String> command;

    /** 子进程启动时的工作目录 */
    private File directory;

    /** 要传给子进程的环境变量集合 */
    private Map<String,String> environment;

    /** 是否把标准错误合并到标准输出 */
    private boolean redirectErrorStream;

    /** 3个标准通道分别如何重定向 */
    private Redirect[] redirects;

    public ProcessBuilder(List<String> command) {
        if(command == null)
            throw new NullPointerException();
        this.command = command;
    }

    public ProcessBuilder(String... command) {
        this.command = new ArrayList<>(command.length);
        for(String arg : command)
            this.command.add(arg);
    }

    public ProcessBuilder command(List<String> command) {
        if(command == null)
            throw new NullPointerException();
        this.command = command;
        return this;
    }

    public ProcessBuilder command(String... command) {
        this.command = new ArrayList<>(command.length);
        for(String arg : command)
            this.command.add(arg);
        return this;
    }

    public List<String> command() {
        return command;
    }

    public Map<String,String> environment() {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null)
            security.checkPermission(new RuntimePermission("getenv.*"));

        if(environment == null)
            environment = ProcessEnvironment.environment();

        assert environment != null;

        return environment;
    }

    ProcessBuilder environment(String[] envp) {
        assert environment == null;
        if(envp != null) {
            environment = ProcessEnvironment.emptyEnvironment(envp.length);
            assert environment != null;

            for(String envstring : envp) {
                // Before 1.5, we blindly passed invalid envstrings
                // to the child process.
                // We would like to throw an exception, but do not,
                // for compatibility with old broken code.

                // Silently discard any trailing junk.
                if(envstring.indexOf('\u0000') != -1)
                    envstring = envstring.replaceFirst("\u0000.*", "");

                int eqlsign = envstring.indexOf('=', ProcessEnvironment.MIN_NAME_LENGTH);
                // Silently ignore envstrings lacking the required `='.
                if(eqlsign != -1)
                    environment.put(envstring.substring(0,eqlsign), envstring.substring(eqlsign+1));
            }
        }
        return this;
    }

    public File directory() {
        return directory;
    }

    public ProcessBuilder directory(File directory) {
        this.directory = directory;
        return this;
    }

    // ---------------- I/O Redirection ----------------

    static class NullInputStream extends InputStream {
        static final NullInputStream INSTANCE = new NullInputStream();

        private NullInputStream() {}

        @Override
        public int read() {
            return -1;
        }

        @Override
        public int available() {
            return 0;
        }
    }

    static class NullOutputStream extends OutputStream {
        static final NullOutputStream INSTANCE = new NullOutputStream();

        private NullOutputStream() {}

        @Override
        public void write(int b) throws IOException {
            throw new IOException("Stream closed");
        }
    }

    public abstract static class Redirect {
        private static final File NULL_FILE = new File((GetPropertyAction.privilegedGetProperty("os.name")
                        .startsWith("Windows") ? "NUL" : "/dev/null"));

        public enum Type {
            PIPE,
            INHERIT,
            READ,
            WRITE,
            APPEND
        }

        public abstract Type type();

        public static final Redirect PIPE = new Redirect() {
            public Type type() {
                return Type.PIPE;
            }

            public String toString() {
                return type().toString();
            }
        };

        public static final Redirect INHERIT = new Redirect() {
            public Type type() {
                return Type.INHERIT;
            }
            public String toString() {
                return type().toString();
            }
        };

        public static final Redirect DISCARD = new Redirect() {
            public Type type() {
                return Type.WRITE;
            }

            public String toString() {
                return type().toString();
            }

            public File file() {
                return NULL_FILE;
            }

            boolean append() {
                return false;
            }
        };

        public File file() {
            return null;
        }

        boolean append() {
            throw new UnsupportedOperationException();
        }

        public static Redirect from(final File file) {
            if(file == null)
                throw new NullPointerException();
            return new Redirect() {
                public Type type() {
                    return Type.READ;
                }

                public File file() {
                    return file;
                }

                public String toString() {
                    return "redirect to read from file \"" + file + "\"";
                }
            };
        }

        public static Redirect to(final File file) {
            if(file == null)
                throw new NullPointerException();
            return new Redirect() {
                public Type type() {
                    return Type.WRITE;
                }

                public File file() {
                    return file;
                }

                public String toString() {
                    return "redirect to write to file \"" + file + "\"";
                }

                boolean append() {
                    return false;
                }
            };
        }

        public static Redirect appendTo(final File file) {
            if(file == null)
                throw new NullPointerException();
            return new Redirect() {
                public Type type() {
                    return Type.APPEND;
                }

                public File file() {
                    return file;
                }

                public String toString() {
                    return "redirect to append to file \"" + file + "\"";
                }

                boolean append() {
                    return true;
                }
            };
        }

        public boolean equals(Object obj) {
            if(obj == this)
                return true;
            if(!(obj instanceof Redirect r))
                return false;
            if(r.type() != this.type())
                return false;
            assert this.file() != null;
            return this.file().equals(r.file());
        }

        public int hashCode() {
            File file = file();
            if(file == null)
                return super.hashCode();
            else
                return file.hashCode();
        }

        private Redirect() {}
    }

    static class RedirectPipeImpl extends Redirect {
        final FileDescriptor fd;

        RedirectPipeImpl() {
            this.fd = new FileDescriptor();
        }
        @Override
        public Type type() {
            return Type.PIPE;
        }

        @Override
        public String toString() {
            return type().toString();
        }

        FileDescriptor getFd() {
            return fd;
        }
    }

    private Redirect[] redirects() {
        if(redirects == null)
            redirects = new Redirect[] {Redirect.PIPE, Redirect.PIPE, Redirect.PIPE};
        return redirects;
    }

    public ProcessBuilder redirectInput(Redirect source) {
        if(source.type() == Redirect.Type.WRITE || source.type() == Redirect.Type.APPEND)
            throw new IllegalArgumentException("Redirect invalid for reading: " + source);
        redirects()[0] = source;
        return this;
    }

    public ProcessBuilder redirectOutput(Redirect destination) {
        if(destination.type() == Redirect.Type.READ)
            throw new IllegalArgumentException("Redirect invalid for writing: " + destination);
        redirects()[1] = destination;
        return this;
    }

    public ProcessBuilder redirectError(Redirect destination) {
        if(destination.type() == Redirect.Type.READ)
            throw new IllegalArgumentException("Redirect invalid for writing: " + destination);
        redirects()[2] = destination;
        return this;
    }

    public ProcessBuilder redirectInput(File file) {
        return redirectInput(Redirect.from(file));
    }

    public ProcessBuilder redirectOutput(File file) {
        return redirectOutput(Redirect.to(file));
    }

    public ProcessBuilder redirectError(File file) {
        return redirectError(Redirect.to(file));
    }

    public Redirect redirectInput() {
        return redirects == null ? Redirect.PIPE : redirects[0];
    }

    public Redirect redirectOutput() {
        return redirects == null ? Redirect.PIPE : redirects[1];
    }

    public Redirect redirectError() {
        return redirects == null ? Redirect.PIPE : redirects[2];
    }

    public ProcessBuilder inheritIO() {
        Arrays.fill(redirects(), Redirect.INHERIT);
        return this;
    }

    public boolean redirectErrorStream() {
        return redirectErrorStream;
    }

    public ProcessBuilder redirectErrorStream(boolean redirectErrorStream) {
        this.redirectErrorStream = redirectErrorStream;
        return this;
    }

    public Process start() throws IOException {
        return start(redirects);
    }

    /**
     * 启动单个进程的入口，本身工作基本就是做做检查，实际工作是委托给特定平台实现类ProcessImpl来完成的。
     */
    private Process start(Redirect[] redirects) throws IOException {
        // Must convert to array first -- a malicious user-supplied
        // list might try to circumvent the security check.
        String[] cmdarray = command.toArray(new String[command.size()]);
        cmdarray = cmdarray.clone();

        for(String arg : cmdarray)
            if(arg == null)
                throw new NullPointerException();
        // Throws IndexOutOfBoundsException if command is empty
        String prog = cmdarray[0];

        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkExec(prog);

        String dir = directory == null ? null : directory.toString();

        for(String s : cmdarray) {
            if(s.indexOf('\u0000') >= 0) {
                throw new IOException("invalid null character in command");
            }
        }

        try {
            Process process = ProcessImpl.start(cmdarray,
                    environment,
                    dir,
                    redirects,
                    redirectErrorStream);
            ProcessStartEvent event = new ProcessStartEvent();
            if(event.isEnabled()) {
                StringJoiner command = new StringJoiner(" ");
                for(String s: cmdarray) {
                    command.add(s);
                }
                event.directory = dir;
                event.command = command.toString();
                event.pid = process.pid();
                event.commit();
            }
            return process;
        } catch (IOException | IllegalArgumentException e) {
            String exceptionInfo = ": " + e.getMessage();
            Throwable cause = e;
            if ((e instanceof IOException) && security != null) {
                // Can not disclose the fail reason for read-protected files.
                try {
                    security.checkRead(prog);
                } catch (SecurityException se) {
                    exceptionInfo = "";
                    cause = se;
                }
            }
            // It's much easier for us to create a high-quality error
            // message than the low-level C code which found the problem.
            throw new IOException(
                    "Cannot run program \"" + prog + "\"" + (dir == null ? "" : " (in directory \"" + dir + "\")") + exceptionInfo,
                    cause);
        }
    }

    /**
     * 按顺序启动多个进程，并把前一个进程的stdout接到后一个进程的stdin，形成类似shell中的管道效果。
     * 方法中会验证每个ProcessBuilder中的Redirect设置是否合理：
     * 1、第一个进程的stdin可以随便配置。
     * 2、第一个进程的stdout以及直到最后一个进程的stdin都要用管道类型。
     * 3、最后一个进程的stdout可以随便配置。
     */
    public static List<Process> startPipeline(List<ProcessBuilder> builders) throws IOException {
        // Accumulate and check the builders
        final int numBuilders = builders.size();
        List<Process> processes = new ArrayList<>(numBuilders);
        try {
            Redirect prevOutput = null;
            for(int index = 0; index < builders.size(); index++) {
                ProcessBuilder builder = builders.get(index);
                Redirect[] redirects = builder.redirects();
                // 从第二个进程开始处理，不包括第一个进程
                if(index > 0) {
                    // check the current Builder to see if it can take input from the previous
                    if(builder.redirectInput() != Redirect.PIPE) {
                        throw new IllegalArgumentException("builder redirectInput()" +
                                " must be PIPE except for the first builder: " + builder.redirectInput());
                    }
                    redirects[0] = prevOutput;  // 前一个输出，指向当前的输入
                }
                // 从第一个进程开始处理，但不包括最后一个进程
                if(index < numBuilders - 1) {
                    // check all but the last stage has output = PIPE
                    if(builder.redirectOutput() != Redirect.PIPE) {
                        throw new IllegalArgumentException("builder redirectOutput()" +
                                " must be PIPE except for the last builder: " + builder.redirectOutput());
                    }
                    // 创建输出
                    redirects[1] = new RedirectPipeImpl();  // placeholder for new output
                }
                /**
                 * 注意是在循环内部逐个启动子进程，而不是一起全部启动。
                 */
                processes.add(builder.start(redirects));
                prevOutput = redirects[1];  // 将prevOutput改成当前刚创建进程的输出
            }
        } catch (Exception ex) {
            // Cleanup processes already started
            processes.forEach(Process::destroyForcibly);
            processes.forEach(p -> {
                try {
                    p.waitFor();        // Wait for it to exit
                } catch (InterruptedException ie) {
                    // If interrupted; continue with next Process
                    Thread.currentThread().interrupt();
                }
            });
            throw ex;
        }
        return processes;
    }
}
