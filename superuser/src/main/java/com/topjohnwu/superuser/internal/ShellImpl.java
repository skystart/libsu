/*
 * Copyright 2018 John "topjohnwu" Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.topjohnwu.superuser.internal;

import android.os.AsyncTask;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

class ShellImpl extends Shell {
    private static final String TAG = "SHELLIMPL";
    private static final String INTAG = "SHELL_IN";
    private static final int UNINT = -2;

    private final ReentrantLock lock;
    private final String token;
    private final Process process;
    private final OutputStream STDIN;
    private final InputStream STDOUT;
    private final InputStream STDERR;
    private final StreamGobbler outGobbler;
    private final StreamGobbler errGobbler;

    private HashMap<String, Boolean> cmds;

    ShellImpl(String... cmd) throws IOException {
        InternalUtils.log(TAG, "exec " + TextUtils.join(" ", cmd));
        status = UNINT;

        process = Runtime.getRuntime().exec(cmd);
        STDIN = process.getOutputStream();
        STDOUT = process.getInputStream();
        STDERR = process.getErrorStream();

        token = ShellUtils.genRandomAlphaNumString(32).toString();
        InternalUtils.log(TAG, "token: " + token);
        outGobbler = new StreamGobbler(new NoCloseInputStream(STDOUT), token);
        errGobbler = new StreamGobbler(new NoCloseInputStream(STDERR), token);

        lock = new ReentrantLock();
        status = UNKNOWN;

        BufferedReader br = new BufferedReader(new InputStreamReader(new NoCloseInputStream(STDOUT)));

        STDIN.write(("echo SHELL_TEST\n").getBytes("UTF-8"));
        STDIN.flush();
        String s = br.readLine();
        if (TextUtils.isEmpty(s) || !s.contains("SHELL_TEST")) {
            throw new IOException();
        }
        status = NON_ROOT_SHELL;

        try {
            STDIN.write(("id\n").getBytes("UTF-8"));
            STDIN.flush();
            s = br.readLine();
            if (TextUtils.isEmpty(s) || !s.contains("uid=0")) {
                throw new IOException();
            }
            status = ROOT_SHELL;
        } catch (IOException ignored) {}

        br.close();
        cmds = new HashMap<>();
    }

    private class NoCloseInputStream extends FilterInputStream {

        NoCloseInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {}
    }

    private class NoCloseOutputStream extends FilterOutputStream {

        NoCloseOutputStream(@NonNull OutputStream out) {
            super(out);
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {}
    }

    @Override
    protected void finalize() throws Throwable {
        close();
    }

    @Override
    public void close() throws IOException {
        if (status < UNKNOWN)
            return;
        InternalUtils.log(TAG, "close");
        status = UNINT;
        outGobbler.interrupt();
        errGobbler.interrupt();
        STDIN.close();
        STDERR.close();
        STDOUT.close();
        process.destroy();
    }


    @Override
    public boolean isAlive() {
        // If status is unknown, it is not alive
        if (status < 0)
            return false;
        try {
            process.exitValue();
            // Process is dead, shell is not alive
            return false;
        } catch (IllegalThreadStateException e) {
            // Process is still running
            return true;
        }
    }

    @Override
    public boolean testCmd(String cmd) {
        Boolean b = cmds.get(cmd);
        if (b != null)
            return b;

        String paths = ShellUtils.fastCmd(this, "echo $PATH");
        for (String path : paths.split(":")) {
            if (ShellUtils.fastCmdResult(this, String.format("[ -x '%s/%s' ]", path, cmd))) {
                cmds.put(cmd, true);
                return true;
            }
        }
        cmds.put(cmd, false);
        return false;
    }

    @Override
    public Throwable execTask(@NonNull Task task) {
        lock.lock();
        ShellUtils.cleanInputStream(STDOUT);
        ShellUtils.cleanInputStream(STDERR);
        try {
            if (!isAlive())
                return null;
            task.run(new NoCloseOutputStream(STDIN),
                    new NoCloseInputStream(STDOUT),
                    new NoCloseInputStream(STDERR));
            return null;
        } catch (Throwable t) {
            InternalUtils.stackTrace(t);
            try {
                close();
            } catch (IOException ignored) {}
            return t;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Throwable execSyncTask(List<String> outList, List<String> errList, @NonNull Task task) {
        return execTask((in, out, err) -> {
            InternalUtils.log(TAG, "runSyncTask");
            outGobbler.begin(outList);
            errGobbler.begin(errList);
            task.run(in, out, err);
            byte[] finalize = String.format("echo %s; echo %s >&2\n", token, token)
                    .getBytes("UTF-8");
            in.write(finalize);
            in.flush();
            outGobbler.waitDone();
            errGobbler.waitDone();
        });
    }

    @Override
    public void execAsyncTask(List<String> outList, List<String> errList,
                              Async.Callback callback, @NonNull Task task) {
        Handler handler = ShellUtils.onMainThread() ? new Handler() : null;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            InternalUtils.log(TAG, "runAsyncTask");
            if (outList == null && errList == null) {
                // Without any output request, we simply run the task
                execTask(task);
            } else {
                if (execSyncTask(outList, errList, task) == null && callback != null) {
                    // Invoke callback if no exceptions occurs and callback is not null
                    Runnable acb = () -> callback.onTaskResult(
                            outList == null ? null : Collections.synchronizedList(outList),
                            errList == null ? null : (errList == outList ? null :
                                    Collections.synchronizedList(errList))
                    );
                    if (handler == null)
                        acb.run();
                    else
                        handler.post(acb);
                }
            }
        });
    }

    @Override
    protected Task createCmdTask(String... commands) {
        return (in, out, err) -> {
            InternalUtils.log(TAG, "runCommands");
            for (String command : commands) {
                in.write(command.getBytes("UTF-8"));
                in.write('\n');
                in.flush();
                InternalUtils.log(INTAG, command);
            }
        };
    }

    @Override
    protected Task createLoadStreamTask(InputStream is) {
        return (in, out, err) -> {
            InternalUtils.log(TAG, "loadInputStream");
            ShellUtils.pump(is, in);
            is.close();
            // Make sure it flushes the shell
            in.write('\n');
            in.flush();
        };
    }
}
