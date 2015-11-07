/*
 * Rsync local transfer session creation
 *
 * Copyright (C) 2013, 2014 Per Lundqvist
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
package com.github.perlundq.yajsync.session;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.Pipe;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

import com.github.perlundq.yajsync.text.Text;
import com.github.perlundq.yajsync.util.BitOps;

public class RsyncLocal
{
    private int _verbosity;
    private boolean _isRecursiveTransfer;
    private boolean _isPreservePermissions;
    private boolean _isPreserveTimes;
    private boolean _isPreserveUser;
    private boolean _isIgnoreTimes;
    private boolean _isDeferredWrite;
    private Charset _charset = Charset.forName(Text.UTF8_NAME);
    private Statistics _statistics = new Statistics();
    private boolean _isTransferDirs = false;
    private boolean _isModuleListing = false;

    public RsyncLocal() {}

    public void setVerbosity(int verbosity)
    {
        _verbosity = verbosity;
    }

    public void setCharset(Charset charset)
    {
        _charset = charset;
    }

    public void setIsModuleListing(boolean isModuleListing)
    {
        _isModuleListing = isModuleListing;
    }

    public void setIsRecursiveTransfer(boolean isRecursiveTransfer)
    {
        _isRecursiveTransfer = isRecursiveTransfer;
    }

    public void setIsPreservePermissions(boolean isPreservePermissions)
    {
        _isPreservePermissions = isPreservePermissions;
    }

    public void setIsPreserveTimes(boolean isPreserveTimes)
    {
        _isPreserveTimes = isPreserveTimes;
    }

    public void setIsPreserveUser(boolean isPreserveUser)
    {
        _isPreserveUser = isPreserveUser;
    }

    public void setIsIgnoreTimes(boolean isIgnoreTimes)
    {
        _isIgnoreTimes = isIgnoreTimes;
    }

    public void setIsDeferredWrite(boolean isDeferredWrite)
    {
        _isDeferredWrite = isDeferredWrite;
    }

    public void setIsTransferDirs(boolean isTransferDirs)
    {
        _isTransferDirs = isTransferDirs;
    }

    private Pipe[] pipePair()
    {
        try {
            Pipe[] pair = { Pipe.open(), Pipe.open() };
            return pair;
        } catch (IOException e){
            throw new RuntimeException(e);
        }
    }

    public boolean transfer(ExecutorService executor,
                            PrintStream out,
                            Iterable<Path> srcPaths,
                            String destinationPathName)
        throws RsyncException, InterruptedException
    {
        byte[] checksumSeed =
            BitOps.toLittleEndianBuf((int) System.currentTimeMillis());
        Pipe[] pipePair = pipePair();
        Pipe toSender = pipePair[0];
        Pipe toReceiver = pipePair[1];

        boolean isTransferDirs = _isTransferDirs ||
                                 _isModuleListing && !_isRecursiveTransfer;
        Sender sender = new Sender(toSender.source(),
                                   toReceiver.sink(),
                                   srcPaths,
                                   _charset,
                                   checksumSeed).
            setIsPreserveUser(_isPreserveUser).
            setIsExitEarlyIfEmptyList(true).
            setIsRecursive(_isRecursiveTransfer).
            setIsTransferDirs(isTransferDirs);
        Generator generator = new Generator(toSender.sink(), _charset,
                                            checksumSeed, out).
            setIsRecursive(_isRecursiveTransfer).
            setIsPreservePermissions(_isPreservePermissions).
            setIsPreserveTimes(_isPreserveTimes).
            setIsPreserveUser(_isPreserveUser).
            setIsIgnoreTimes(_isIgnoreTimes).
            setIsListOnly(_isModuleListing).
            setIsAlwaysItemize(_verbosity > 1);
        Receiver receiver = new Receiver(generator,
                                         toReceiver.source(),
                                         _charset,
                                         destinationPathName).
            setIsExitEarlyIfEmptyList(true).
            setIsRecursive(_isRecursiveTransfer).
            setIsPreservePermissions(_isPreservePermissions).
            setIsPreserveTimes(_isPreserveTimes).
            setIsPreserveUser(_isPreserveUser).
            setIsListOnly(_isModuleListing).
            setIsDeferredWrite(_isDeferredWrite);

        boolean isOK = RsyncTaskExecutor.exec(executor, sender,
                                                     generator, receiver);
        _statistics = receiver.statistics();
        return isOK;
    }

    public Statistics statistics()
    {
        return _statistics;
    }
}
