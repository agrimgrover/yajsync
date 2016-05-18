/*
 * Rsync system tests
 *
 * Copyright (C) 2014 - 2016 Per Lundqvist
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.perlundq.yajsync.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.github.perlundq.yajsync.RsyncClient;
import com.github.perlundq.yajsync.channels.ChannelException;
import com.github.perlundq.yajsync.channels.net.DuplexByteChannel;
import com.github.perlundq.yajsync.channels.net.SSLChannelFactory;
import com.github.perlundq.yajsync.channels.net.StandardChannelFactory;
import com.github.perlundq.yajsync.filelist.RsyncFileAttributes;
import com.github.perlundq.yajsync.filelist.User;
import com.github.perlundq.yajsync.security.RsyncAuthContext;
import com.github.perlundq.yajsync.session.Module;
import com.github.perlundq.yajsync.session.ModuleException;
import com.github.perlundq.yajsync.session.ModuleProvider;
import com.github.perlundq.yajsync.session.Modules;
import com.github.perlundq.yajsync.session.RestrictedModule;
import com.github.perlundq.yajsync.session.RestrictedPath;
import com.github.perlundq.yajsync.text.Text;
import com.github.perlundq.yajsync.ui.SyncClient;
import com.github.perlundq.yajsync.ui.YajSyncServer;
import com.github.perlundq.yajsync.util.Environment;
import com.github.perlundq.yajsync.util.FileOps;
import com.github.perlundq.yajsync.util.Option;



class FileUtil
{
    public static byte[] generateBytes(int content, int num)
    {
        byte[] res = new byte[num];
        for (int i = 0; i < num; i++) {
            res[i] = (byte) content;
        }
        return res;
    }

    public static void writeToFiles(byte[] content, Path ...path)
        throws IOException
    {
        for (Path p : path) {
            try (FileOutputStream out = new FileOutputStream(p.toFile())) {
                out.write(content);
            }
        }
    }

    public static void writeToFiles(int content, Path ...path)
        throws IOException
    {
        for (Path p : path) {
            try (FileOutputStream out = new FileOutputStream(p.toFile())) {
                out.write(content);
            }
        }
    }

    public static boolean isContentIdentical(Path leftPath, Path rightPath)
        throws IOException
    {
        try (InputStream left_is = Files.newInputStream(leftPath);
             InputStream right_is = Files.newInputStream(rightPath)) {
            while (true) {
                int left_byte = left_is.read();
                int right_byte = right_is.read();
                if (left_byte != right_byte) {
                    return false;
                }
                boolean isEOF = left_byte == -1; // && right_byte == -1;
                if (isEOF) {
                    return true;
                }
            }
        }
    }

    private static boolean isFileSameTypeAndSize(RsyncFileAttributes leftAttrs,
                                                 RsyncFileAttributes rightAttrs)
    {
        int leftType = FileOps.fileType(leftAttrs.mode());
        int rightType = FileOps.fileType(rightAttrs.mode());
        return leftType == rightType && (!FileOps.isRegularFile(leftType) ||
                                         leftAttrs.size() == rightAttrs.size());
    }

    private static SortedMap<Path, Path> listDir(Path path) throws IOException
    {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            SortedMap<Path, Path> files = new TreeMap<>();
            for (Path p : stream) {
                files.put(p.getFileName(), p);
            }
            return files;
        }
    }

    public static boolean isDirectoriesIdentical(Path leftDir, Path rightDir)
        throws IOException
    {
        SortedMap<Path, Path> leftFiles = FileUtil.listDir(leftDir);
        SortedMap<Path, Path> rightFiles = FileUtil.listDir(rightDir);

        if (!leftFiles.keySet().equals(rightFiles.keySet())) {
            return false;
        }

        for (Map.Entry<Path, Path> entrySet : leftFiles.entrySet()) {
            Path name = entrySet.getKey();
            Path leftPath = entrySet.getValue();
            Path rightPath = rightFiles.get(name);

            RsyncFileAttributes leftAttrs = RsyncFileAttributes.stat(leftPath);
            RsyncFileAttributes rightAttrs =
                RsyncFileAttributes.stat(rightPath);
            if (!FileUtil.isFileSameTypeAndSize(leftAttrs, rightAttrs)) {
                return false;
            } else if (leftAttrs.isRegularFile()) {
                boolean isIdentical = FileUtil.isContentIdentical(leftPath,
                                                                  rightPath);
                if (!isIdentical) {
                    return false;
                }
            } else if (leftAttrs.isDirectory()) {
                boolean isIdentical =
                    FileUtil.isDirectoriesIdentical(leftPath, rightPath);
                if (!isIdentical) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isFileSameOwner(RsyncFileAttributes leftAttrs,
                                          RsyncFileAttributes rightAttrs)
    {
        return leftAttrs.user().equals(rightAttrs.user());
    }

    public static boolean isFileSameGroup(RsyncFileAttributes leftAttrs,
                                          RsyncFileAttributes rightAttrs)
    {
        return leftAttrs.group().equals(rightAttrs.group());
    }

    public static boolean isDirectory(Path path)
    {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isFile(Path path)
    {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean exists(Path path)
    {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static long du(Path... srcFiles) throws IOException
    {
        long size = 0;
        for (Path p : srcFiles) {
            size += Files.size(p);
        }
        return size;
    }
}

class SimpleRestrictedModule extends RestrictedModule
{
    private final String _authToken;
    private final Module _module;
    private final String _name;
    private final String _comment;

    public SimpleRestrictedModule(String authToken, Module module, String name,
                                  String comment)
    {
        _authToken = authToken;
        _module = module;
        _name = name;
        _comment = comment;
    }

    @Override
    public String authenticate(RsyncAuthContext authContext, String userName)
    {
        return authContext.response(_authToken.toCharArray());
    }

    @Override
    public Module toModule()
    {
        return _module;
    }

    @Override
    public String name()
    {
        return _name;
    }

    @Override
    public String comment()
    {
        return _comment;
    }
}

class SimpleModule implements Module
{
    private final String _name;
    private final RestrictedPath _path;
    private final String _comment;
    private final boolean _isReadable;
    private final boolean _isWritable;

    SimpleModule(String name, Path root, String comment,
                 boolean isReadable, boolean isWritable)
    {
        _name = name.toString();
        _path = new RestrictedPath(name, root);
        _comment = comment;
        _isReadable = isReadable;
        _isWritable = isWritable;
    }

    @Override
    public String name()
    {
        return _name;
    }

    @Override
    public String comment()
    {
        return _comment;
    }

    @Override
    public RestrictedPath restrictedPath()
    {
        return _path;
    }

    @Override
    public boolean isReadable()
    {
        return _isReadable;
    }

    @Override
    public boolean isWritable()
    {
        return _isWritable;
    }
}

class TestModules implements Modules
{
    private final Map<String, Module> _modules;

    TestModules(Module... modules)
    {
        _modules = new HashMap<>();
        for (Module module : modules) {
            _modules.put(module.name(), module);
        }
    }

    @Override
    public Module get(String moduleName) throws ModuleException
    {
        Module module = _modules.get(moduleName);
        if (module == null) {
            throw new ModuleException("no such module: " + moduleName);
        }
        return module;
    }

    @Override
    public Iterable<Module> all()
    {
        return _modules.values();
    }
}

class TestModuleProvider extends ModuleProvider
{
    private final Modules _modules;

    TestModuleProvider(Modules modules)
    {
        _modules = modules;
    }

    @Override
    public Collection<Option> options()
    {
        return Collections.emptyList();
    }

    @Override
    public void close()
    {
        /* nop */
    }

    @Override
    public Modules newAuthenticated(InetAddress address, Principal principal)
    {
        return _modules;
    }

    @Override
    public Modules newAnonymous(InetAddress address)
    {
        return _modules;
    }
}

@RunWith(Parameterized.class)
public class SystemTest
{
    private static final boolean IS_POSIX_FS = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @Parameters
    public static Collection<ClientTestFactory> data() {
        return Arrays.asList(new ClientTestFactory[] {
                new YajSyncTestFactory(), new NativeRsyncTestFactory()
        });
    }

    @Parameter // first data value (0) is default
    public ClientTestFactory testFactory;

    private final PrintStream _nullOut =
        new PrintStream(new OutputStream() {
            @Override
            public void write(int b) { /* nop */}
        }
    );

    private ExecutorService _service;

    private YajSyncServer newServer(Modules modules)
    {
        YajSyncServer server = new YajSyncServer().setStandardOut(_nullOut).
                                                   setStandardErr(_nullOut);
        server.setModuleProvider(new TestModuleProvider(modules));
        return server;
    }

    private ReturnStatus listFiles(Path src, String ... args)
    {
        SyncClient client = testFactory.newClient();
        String[] nargs = new String[args.length + 1];
        int i = 0;
        for (String arg : args) {
            nargs[i++] = arg;
        }
        nargs[i++] = src.toString();
        int rc = client.start(nargs);
        return new ReturnStatus(rc, client.statistics());
    }

    private ReturnStatus fileCopy(Path src, Path dst, String ... args)
    {
        return testFactory.fileCopy(_service, src, dst, args);
    }

    private ReturnStatus fileCopy(boolean startServer, Path src, Path dst, String ... args)
    {
        return testFactory.fileCopy(startServer, _service, src, dst, args);
    }

    private ReturnStatus recursiveCopyTrailingSlash(Path src, Path dst)
    {
        SyncClient client = testFactory.newClient();
        int rc = client.start(new String[] { "--recursive",
                                             src.toString() + "/",
                                             dst.toString() });
        return new ReturnStatus(rc, client.statistics());
    }

    @Before
    public void setup()
    {
        _service = Executors.newCachedThreadPool();
    }

    @After
    public void teardown()
    {
        _service.shutdownNow();
    }

    @Rule
    public final TemporaryFolder _tempDir = new TemporaryFolder();

    @Test
    public void testFileUtilIdenticalEmptyDirs() throws IOException
    {
        Path left = _tempDir.newFolder("left_dir").toPath();
        Path right = _tempDir.newFolder("right_dir").toPath();
        assertTrue(FileUtil.isDirectoriesIdentical(left, right));
    }

    @Test
    public void testFileUtilNotIdenticalDirs() throws IOException
    {
        Path left = _tempDir.newFolder("left_dir").toPath();
        Path right = _tempDir.newFolder("right_dir").toPath();
        Files.createFile(left.resolve("file1"));
        assertFalse(FileUtil.isDirectoriesIdentical(left, right));
    }

    @Test
    public void testFileUtilIdenticalEmptyFiles() throws IOException
    {
        Path left = _tempDir.newFile("left_file").toPath();
        Path right = _tempDir.newFile("right_file").toPath();
        assertTrue(FileUtil.isContentIdentical(left, right));
    }

    @Test
    public void testFileUtilIdenticalFiles() throws IOException
    {
        Path left = _tempDir.newFile("left_file").toPath();
        Path right = _tempDir.newFile("right_file").toPath();
        FileUtil.writeToFiles(127, left, right);
        assertTrue(FileUtil.isContentIdentical(left, right));
    }

    @Test
    public void testFileUtilNotIdenticalFiles() throws IOException
    {
        Path left = _tempDir.newFile("left_file").toPath();
        Path right = _tempDir.newFile("right_file").toPath();
        FileUtil.writeToFiles(127, left);
        FileUtil.writeToFiles(128, right);
        assertFalse(FileUtil.isContentIdentical(left, right));
    }

    @Test
    public void testFileUtilIdenticalDirsWithSymlinks() throws IOException
    {
        Path left = _tempDir.newFolder("left_dir").toPath();
        Path right = _tempDir.newFolder("right_dir").toPath();
        Path left_file = Files.createFile(left.resolve("file1"));
        Path right_file = Files.createFile(right.resolve("file1"));
        Files.createSymbolicLink(left.resolve("link1"), left_file);
        Files.createSymbolicLink(right.resolve("link1"), right_file);
        assertTrue(FileUtil.isDirectoriesIdentical(left, right));
    }

    @Test
    public void testFileUtilNotIdenticalDirs2() throws IOException
    {
        Path left = _tempDir.newFolder("left_dir").toPath();
        Path right = _tempDir.newFolder("right_dir").toPath();
        Path left_file = Files.createFile(left.resolve("file1"));
        Files.createFile(right.resolve("file1"));
        FileUtil.writeToFiles(0, left_file);
        assertFalse(FileUtil.isDirectoriesIdentical(left, right));
    }

    @Test
    public void testClientNoArgs()
    {
        int rc = testFactory.newClient().start(new String[] {});
        assertTrue(rc == -1 || rc == 1);
    }

    @Test
    public void testClientHelp()
    {
        int rc = testFactory.newClient().start(new String[] { "--help" });
        assertTrue(rc == 0);
    }

    @Test
    public void testLocalListDotDirEmpty() throws IOException
    {
        if (testFactory instanceof NativeRsyncTestFactory) {
            // don't test remote execution by native rsync
            return;
        }

        Path src = _tempDir.newFolder().toPath();
        ReturnStatus status = listFiles(Paths.get("."), "--cwd=" + src);
        int numFiles = 1;
        long fileSize = 0;
        assertTrue(status.rc == 0);
        assertTrue(status.stats != null);
        assertTrue(status.stats.numFiles() == numFiles);
        assertTrue(status.stats.numTransferredFiles() == 0);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testLocalListDotDir() throws IOException
    {
        if (testFactory instanceof NativeRsyncTestFactory) {
            // don't test remote execution by native rsync
            return;
        }

        Path dir = _tempDir.newFolder("dir").toPath();
        Files.createFile(dir.resolve("file"));
        ReturnStatus status = listFiles(Paths.get("."), "--cwd=" + dir);
        int numFiles = 2;
        long fileSize = 0;
        assertTrue(status.rc == 0);
        assertTrue(status.stats != null);
        assertTrue(status.stats.numFiles() == numFiles);
        assertTrue(status.stats.numTransferredFiles() == 0);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientSingleFileCopy() throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path dst = _tempDir.newFile().toPath();
        FileUtil.writeToFiles(0, src);
        int numFiles = 1;
        long fileSize = Files.size(src);
        ReturnStatus status = fileCopy(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientDotDotSrcArg() throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path srcDotDot = _tempDir.newFolder().toPath().
                resolve(Paths.get(Text.DOT_DOT)).
                resolve(src.getFileName());
        Path dst = _tempDir.newFile().toPath();
        FileUtil.writeToFiles(0, src);
        int numFiles = 1;
        long fileSize = Files.size(src);
        ReturnStatus status = fileCopy(srcDotDot, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientEmptyDirCopy() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");
        Path copyOfSrc = dst.resolve(src.getFileName());
        int numDirs = 1;
        int numFiles = 0;
        long fileSize = 0;
        ReturnStatus status = fileCopy(src, dst, "--recursive");
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientEmptyDirTrailingSlashCopy() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");
        int numDirs = 1;
        int numFiles = 0;
        long fileSize = 0;
        ReturnStatus status = recursiveCopyTrailingSlash(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testSiblingSubdirsSubstring() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = _tempDir.newFolder().toPath();
        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);
        int numDirs = 3;
        int numFiles = 2;
        long fileSize = FileUtil.du(srcFile1, srcFile2);
        ReturnStatus status = recursiveCopyTrailingSlash(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectoriesIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientDirCopyDstDeletion() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);
        int numDirs = 3;
        int numFiles = 2;
        long fileSize = FileUtil.du(srcFile1, srcFile2);

        Files.createDirectory(dst);
        Path dstDir1 = dst.resolve("dir.remove");
        Files.createDirectory(dstDir1);
        Path dstFile1 = dstDir1.resolve("file1.remove");
        FileUtil.writeToFiles(10, dstFile1);
        Path dstFile2 = dst.resolve("file2.remove");
        FileUtil.writeToFiles(9, dstFile2);

        Path copyOfSrc = dst.resolve(src.getFileName());
        ReturnStatus status = fileCopy(src, dst, "--recursive", "--delete");

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientDirCopyExcluded() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);
        int numDirs = 3;
        int numFiles = 1;
        long fileSize = FileUtil.du(srcFile1);

        Path copyOfSrc = dst.resolve(src.getFileName());
        ReturnStatus status = fileCopy(src, dst, "--recursive", "--exclude=file2");

        Files.deleteIfExists(srcFile2);

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientDirCopyDstDeleteExcluded() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);
        int numDirs = 3;
        int numFiles = 1;
        long fileSize = FileUtil.du(srcFile2);

        Files.createDirectory(dst);
        Path copyOfSrc = dst.resolve(src.getFileName());
        Files.createDirectory(copyOfSrc);
        Path dstDir1 = copyOfSrc.resolve("dir");
        Files.createDirectory(dstDir1);
        Path dstFile1 = dstDir1.resolve("file1");
        FileUtil.writeToFiles(9, dstFile1);

        ReturnStatus status = fileCopy(src, dst, "--recursive", "--delete-excluded", "--exclude=file1");

        Files.deleteIfExists(srcFile1);

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientDirCopyDstDeleteExcludedAndProtect() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);
        int numDirs = 3;
        int numFiles = 1;
        long fileSize = FileUtil.du(srcFile2);

        Files.createDirectory(dst);
        Path copyOfSrc = dst.resolve(src.getFileName());
        Files.createDirectory(copyOfSrc);
        Path dstDir1 = copyOfSrc.resolve("dir");
        Files.createDirectory(dstDir1);
        Path dstFile1 = dstDir1.resolve("file1");
        FileUtil.writeToFiles(9, dstFile1);

        ReturnStatus status = fileCopy(src, dst, "--recursive", "--delete-excluded", "--exclude=file1", "--filter=protect file1");

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));

        assertTrue(Files.exists(dstFile1));
        Files.deleteIfExists(srcFile1);
        Files.deleteIfExists(dstFile1);

        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientDirCopyDstHide() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);
        int numDirs = 3;
        int numFiles = 1;
        long fileSize = FileUtil.du(srcFile2);

        Files.createDirectory(dst);
        Path copyOfSrc = dst.resolve(src.getFileName());
        Files.createDirectory(copyOfSrc);
        Path dstDir1 = copyOfSrc.resolve("dir");
        Files.createDirectory(dstDir1);
        Path dstFile1 = dstDir1.resolve("file1");

        ReturnStatus status = fileCopy(src, dst, "--recursive", "--filter=hide file1");

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));

        assertTrue(Files.notExists(dstFile1));
        Files.deleteIfExists(srcFile1);

        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientDirCopyDstDeleteExcludedAndClearing() throws IOException
    {
        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);
        int numDirs = 3;
        int numFiles = 2;
        long fileSize = FileUtil.du(srcFile1)+FileUtil.du(srcFile2);

        Files.createDirectory(dst);
        Path copyOfSrc = dst.resolve(src.getFileName());
        Files.createDirectory(copyOfSrc);
        Path dstDir1 = copyOfSrc.resolve("dir");
        Files.createDirectory(dstDir1);
        Path dstFile1 = dstDir1.resolve("file1");
        FileUtil.writeToFiles(9, dstFile1);

        // ReturnStatus status = fileCopy(src, dst, "--recursive", "--ignore-times", "--delete-excluded", "--exclude=file1", "--filter=\"clear\"");
        ReturnStatus status = fileCopy(src, dst, "--recursive", "--ignore-times", "--delete-excluded", "--exclude=file1", "--filter=clear");

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testCopyFileMultipleBlockSize() throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path dst = Paths.get(src.toString() + ".copy");
        int fileSize = 2048;
        int numDirs = 0;
        int numFiles = 1;
        byte[] content = FileUtil.generateBytes(0xF0, fileSize);
        FileUtil.writeToFiles(content, src);
        ReturnStatus status = fileCopy(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testCopyFileSameBlockSize() throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path dst = Paths.get(src.toString() + ".copy");
        int fileSize = 512;
        int numDirs = 0;
        int numFiles = 1;
        byte[] content = FileUtil.generateBytes(0xcd, fileSize);
        FileUtil.writeToFiles(content, src);
        ReturnStatus status = fileCopy(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testCopyFileLessThanBlockSize() throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path dst = Paths.get(src.toString() + ".copy");
        int fileSize = 257;
        int numDirs = 0;
        int numFiles = 1;
        byte[] content = FileUtil.generateBytes(0xbc, fileSize);
        FileUtil.writeToFiles(content, src);
        ReturnStatus status = fileCopy(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testCopyFileNotMultipleBlockSize() throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path dst = Paths.get(src.toString() + ".copy");
        int fileSize = 651;
        int numDirs = 0;
        int numFiles = 1;
        byte[] content = FileUtil.generateBytes(0x19, fileSize);
        FileUtil.writeToFiles(content, src);
        ReturnStatus status = fileCopy(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testCopyFileTwiceNotMultipleBlockSize() throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path dst = Paths.get(src.toString() + ".copy");
        int fileSize = 557;
        int numDirs = 0;
        int numFiles = 1;
        byte[] content = FileUtil.generateBytes(0x18, fileSize);
        FileUtil.writeToFiles(content, src);
        Files.setLastModifiedTime(src, FileTime.fromMillis(0));
        ReturnStatus status = fileCopy(src, dst);
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
        ReturnStatus status2 = fileCopy(false, src, dst);
        assertTrue(status2.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status2.stats.numFiles() == numDirs + numFiles);
        assertTrue(status2.stats.numTransferredFiles() == numFiles);
        assertTrue(status2.stats.totalLiteralSize() == 0);
        assertTrue(status2.stats.totalMatchedSize() == fileSize);
    }

    @Test
    public void testCopyFileTwiceNotMultipleBlockSizeTimes()
        throws IOException
    {
        Path src = _tempDir.newFile().toPath();
        Path dst = Paths.get(src.toString() + ".copy");
        int fileSize = 557;
        int numDirs = 0;
        int numFiles = 1;
        byte[] content = FileUtil.generateBytes(0x18, fileSize);
        FileUtil.writeToFiles(content, src);
        Files.setLastModifiedTime(src, FileTime.fromMillis(0));
        ReturnStatus status = fileCopy(src, dst, "--times");
        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status.stats.numFiles() == numDirs + numFiles);
        assertTrue(status.stats.numTransferredFiles() == numFiles);
        assertTrue(status.stats.totalLiteralSize() == fileSize);
        assertTrue(status.stats.totalMatchedSize() == 0);
        ReturnStatus status2 = fileCopy(false, src, dst);
        assertTrue(status2.rc == 0);
        assertTrue(FileUtil.isContentIdentical(src, dst));
        assertTrue(status2.stats.numFiles() == numDirs + numFiles);
        assertTrue(status2.stats.numTransferredFiles() == 0);
        assertTrue(status2.stats.totalLiteralSize() == 0);
        assertTrue(status2.stats.totalMatchedSize() == 0);
    }

    @Test
    public void testClientCopyPreserveUid() throws IOException
    {
        if (!Environment.getUserName().equals("root")) {
            return;
        }

        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir = src.resolve("dir");
        Path srcFile = srcDir.resolve("file");
        Files.createDirectory(srcDir);
        FileUtil.writeToFiles(1, srcFile);
        FileOps.setUserId(srcFile, User.NOBODY.id());

        Files.createDirectory(dst);
        Path copyOfSrc = dst.resolve(src.getFileName());
        Files.createDirectory(copyOfSrc);
        Path dstDir = copyOfSrc.resolve("dir");
        Path dstFile = dstDir.resolve("file");
        Files.createDirectory(dstDir);
        FileUtil.writeToFiles(1, dstFile);

        ReturnStatus status = fileCopy(src, dst, "--recursive", "--owner",
                                       "--numeric-ids");

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(FileUtil.isFileSameOwner(RsyncFileAttributes.stat(srcFile),
                                            RsyncFileAttributes.stat(dstFile)));
    }

    @Test
    public void testClientCopyPreserveGid() throws IOException
    {
        if (!Environment.getUserName().equals("root")) {
            return;
        }

        Path src = _tempDir.newFolder().toPath();
        Path dst = Paths.get(src.toString() + ".dst");

        Path srcDir = src.resolve("dir");
        Path srcFile = srcDir.resolve("file");
        Files.createDirectory(srcDir);
        FileUtil.writeToFiles(1, srcFile);
        FileOps.setGroupId(srcFile, User.NOBODY.id());

        Files.createDirectory(dst);
        Path copyOfSrc = dst.resolve(src.getFileName());
        Files.createDirectory(copyOfSrc);
        Path dstDir = copyOfSrc.resolve("dir");
        Path dstFile = dstDir.resolve("file");
        Files.createDirectory(dstDir);
        FileUtil.writeToFiles(1, dstFile);

        ReturnStatus status = fileCopy(src, dst, "--recursive", "--group",
                                       "--numeric-ids");

        assertTrue(status.rc == 0);
        assertTrue(FileUtil.isDirectory(dst));
        assertTrue(FileUtil.isDirectoriesIdentical(src, copyOfSrc));
        assertTrue(FileUtil.isFileSameGroup(RsyncFileAttributes.stat(srcFile),
                                            RsyncFileAttributes.stat(dstFile)));
    }

    @Test(timeout=100)
    public void testServerHelp() throws InterruptedException, IOException
    {
        int rc = newServer(new TestModules()).
            setStandardOut(_nullOut).
            start(new String[] { "--help" });
        assertTrue(rc == 0);
    }

    // FIXME: latch might not get decreased if exception occurs
    // FIXME: port might be unavailable, open it here and inject it
    @Test(timeout=100)
    public void testServerConnection() throws InterruptedException
    {
        final CountDownLatch isListeningLatch = new CountDownLatch(1);

        Callable<Integer> serverTask = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception
            {
                Path modulePath = _tempDir.newFolder().toPath();
                Module m = new SimpleModule("test", modulePath,
                                            "a test module", true, false);
                int rc = newServer(new TestModules(m)).
                        setIsListeningLatch(isListeningLatch).
                        start(new String[] { "--port=14415" });
                return rc;
            }
        };
        _service.submit(serverTask);
        isListeningLatch.await();
        SyncClient client = testFactory.newClient();
        int rc = client.start(new String[] { "--port=14415", "localhost::" });
        assertTrue(rc == 0);
    }

    @Test(timeout=1000)
    public void testProtectedServerConnectionWithPasswordFile()
            throws InterruptedException, IOException
    {
        if (!IS_POSIX_FS) {
            return;
        }

        final CountDownLatch isListeningLatch = new CountDownLatch(1);
        final String restrictedModuleName = "Restricted";
        final String authToken = "ëẗÿåäöüﭏ사غ";
        Callable<Integer> serverTask = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception
            {
                Path modulePath = _tempDir.newFolder().toPath();
                Module m = new SimpleModule(restrictedModuleName,
                                            modulePath,
                                            "a test module", true, false);
                RestrictedModule rm = new SimpleRestrictedModule(
                                                 authToken,
                                                 m,
                                                 restrictedModuleName,
                                                 "a restricted module");
                int rc = newServer(new TestModules(rm)).
                        setIsListeningLatch(isListeningLatch).
                        start(new String[] { "--port=14415" });
                return rc;
            }
        };
        _service.submit(serverTask);
        isListeningLatch.await();
        SyncClient client = testFactory.newClient();

        File passwordFile = File.createTempFile("password-file", ".tmp");
        try (PrintStream out = new PrintStream(passwordFile)) {
            out.print(authToken);
        }

        Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
        //add owners permission
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        //add group permissions
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_WRITE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);

        Files.setPosixFilePermissions(Paths.get(passwordFile.toURI()), perms);

        passwordFile.deleteOnExit();

        int rc = client.start(new String[] {
                "--port=14415", "--password-file="+passwordFile.getAbsolutePath(),
                "localhost::" + restrictedModuleName });
        assertTrue(rc == 0);
    }

    @Test(timeout=1000)
    public void testProtectedServerConnectionWithPasswordAtStdin()
            throws InterruptedException
    {
        if (testFactory instanceof NativeRsyncTestFactory) {
            // don't test execution by native rsync: password STDIN is not supported in test execution
            return;
        }

        final CountDownLatch isListeningLatch = new CountDownLatch(1);
        final String restrictedModuleName = "Restricted";
        final String authToken = "ëẗÿåäöüﭏ사غ";
        Callable<Integer> serverTask = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception
            {
                Path modulePath = _tempDir.newFolder().toPath();
                Module m = new SimpleModule(restrictedModuleName,
                                            modulePath,
                                            "a test module", true, false);
                RestrictedModule rm = new SimpleRestrictedModule(
                                                 authToken,
                                                 m,
                                                 restrictedModuleName,
                                                 "a restricted module");
                int rc = newServer(new TestModules(rm)).
                        setIsListeningLatch(isListeningLatch).
                        start(new String[] { "--port=14415" });
                return rc;
            }
        };
        _service.submit(serverTask);
        isListeningLatch.await();
        SyncClient client = testFactory.newClient();
        System.setIn(new ByteArrayInputStream(authToken.getBytes()));
        int rc = client.start(new String[] {
                "--port=14415", "--password-file=-",
                "localhost::" + restrictedModuleName });
        assertTrue(rc == 0);
    }

    @Test(timeout=1000)
    public void testInvalidPassword()
            throws InterruptedException
    {
        if (testFactory instanceof NativeRsyncTestFactory) {
            // don't test execution by native rsync: password STDIN is not supported in test execution
            return;
        }

        final CountDownLatch isListeningLatch = new CountDownLatch(1);
        final String restrictedModuleName = "Restricted";
        final String authToken = "testAuthToken";

        Callable<Integer> serverTask = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception
            {
                Path modulePath = _tempDir.newFolder().toPath();
                Module m = new SimpleModule(restrictedModuleName,
                                            modulePath,
                                            "a test module", true, false);
                RestrictedModule rm = new SimpleRestrictedModule(
                                                 authToken,
                                                 m,
                                                 restrictedModuleName,
                                                 "a restricted module");
                int rc = newServer(new TestModules(rm)).
                        setIsListeningLatch(isListeningLatch).
                        start(new String[] { "--port=14415" });
                return rc;
            }
        };
        _service.submit(serverTask);
        isListeningLatch.await();
        SyncClient client = testFactory.newClient();
        System.setIn(new ByteArrayInputStream((authToken + "fail").getBytes()));
        int rc = client.start(new String[] {
                "--port=14415", "--password-file=-",
                "localhost::" + restrictedModuleName });
        assertTrue(rc != 0);
    }

    @Test(expected=SocketTimeoutException.class, timeout=2000)
    public void testReadTimeout() throws Throwable
    {
        final CountDownLatch isListeningLatch = new CountDownLatch(1);
        final int port = 14416;

        ReadTimeoutTestServer serverThread = new ReadTimeoutTestServer(isListeningLatch, port);
        serverThread.start();
        isListeningLatch.await();

        int _contimeout = 0;
        int _timeout = 1;

        if (!Environment.hasAllocateDirectArray()) {
            Environment.setAllocateDirect(false);
        }

        DuplexByteChannel sock = new StandardChannelFactory().open("localhost",
                                                                   port,
                                                                   _contimeout,
                                                                   _timeout);

        try {
            testTimeoutHelper(sock);
        } finally {
            serverThread.stopServer();
        }
    }

    @Test(expected=SocketTimeoutException.class, timeout=2000)
    public void testConnectionTimeout() throws Throwable
    {
        int _contimeout = 1;
        int _timeout = 0;

        // connect to a non routable ip to provoke the connection timeout
        DuplexByteChannel sock = new StandardChannelFactory().open("10.0.0.0",
                                                                   14415,
                                                                   _contimeout,
                                                                   _timeout);

        testTimeoutHelper(sock);
    }

    @Test(expected=SocketTimeoutException.class, timeout=2000)
    public void testTlsReadTimeout() throws Throwable
    {
        final CountDownLatch isListeningLatch = new CountDownLatch(1);
        final int port = 14416;

        ReadTimeoutTestServer serverThread = new ReadTimeoutTestServer(isListeningLatch, port);
        serverThread.start();
        isListeningLatch.await();

        int _contimeout = 0;
        int _timeout = 1;

        if (!Environment.hasAllocateDirectArray()) {
            Environment.setAllocateDirect(false);
        }

        DuplexByteChannel sock = new SSLChannelFactory().open("localhost",
                                                               port,
                                                               _contimeout,
                                                               _timeout);

        try {
            testTimeoutHelper(sock);
        } finally {
            serverThread.stopServer();
        }
    }

    @Test(expected=SocketTimeoutException.class, timeout=2000)
    public void testTlsConnectionTimeout() throws Throwable
    {
        int _contimeout = 1;
        int _timeout = 0;

        // connect to a non routable ip to provoke the connection timeout
        DuplexByteChannel sock = new SSLChannelFactory().open("10.0.0.0",
                                                              14415,
                                                              _contimeout,
                                                              _timeout);

        testTimeoutHelper(sock);
    }

    private void testTimeoutHelper(DuplexByteChannel sock) throws Throwable
    {
        try {
            new RsyncClient.Builder().buildRemote(sock /* in */,
                                                  sock /* out */,
                                                  true).
                                      receive("", new String[] { "/" }).
                                      to(Paths.get("/"));
        } catch (ChannelException e) {
            throw e.getCause();
        }
    }

    private class ReadTimeoutTestServer extends Thread {

        private final CountDownLatch _isListeningLatch;
        private final int _port;
        private ServerSocket _serverSocket;

        public ReadTimeoutTestServer(CountDownLatch isListeningLatch,
                                     int port)
        {
            _isListeningLatch = isListeningLatch;
            _port = port;
        }

        @Override
        public void run()
        {
            try {
                _serverSocket = new ServerSocket(_port);

                _isListeningLatch.countDown();

                while (true) {
                    _serverSocket.accept();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                stopServer();
            }
        }

        public void stopServer() {
            if (_serverSocket != null && !_serverSocket.isClosed()) {
                try {
                    _serverSocket.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @Ignore
    @Test(timeout=100)
    public void testClientServerDirCopyDstDeleteExcludedAndProtect() throws InterruptedException, IOException
    {
        final CountDownLatch isListeningLatch = new CountDownLatch(1);

        Callable<Integer> serverTask = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception
            {
                Path modulePath = _tempDir.newFolder().toPath();
                Module m = new SimpleModule("test", modulePath,
                                            "a test module", true, false);
                int rc = newServer(new TestModules(m)).
                        setIsListeningLatch(isListeningLatch).
                        start(new String[] { "--port=14415" });
                return rc;
            }
        };
        _service.submit(serverTask);
        isListeningLatch.await();

        Path src = _tempDir.newFolder().toPath();

        Path srcDir1 = src.resolve("dir");
        Path srcDir2 = src.resolve("dir.sub");
        Path srcFile1 = srcDir1.resolve("file1");
        Path srcFile2 = srcDir2.resolve("file2");
        Files.createDirectory(srcDir1);
        Files.createDirectory(srcDir2);
        FileUtil.writeToFiles(7, srcFile1);
        FileUtil.writeToFiles(8, srcFile2);

        SyncClient client = testFactory.newClient();
        int rc = client.start(new String[] { "--port=14415", "--recursive", "--delete-excluded", "--exclude=file1", "--filter=protect file1", srcDir1.toAbsolutePath().toString(), "localhost::test" });
        assertTrue(rc == 0);
    }
}
