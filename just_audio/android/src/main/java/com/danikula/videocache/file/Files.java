package com.danikula.videocache.file;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * Utils for work with files.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class Files {
    static void makeDir(File directory) throws IOException {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IOException("File " + directory + " is not directory!");
            }
        } else {
            boolean isCreated = directory.mkdirs();
            if (!isCreated) {
                throw new IOException(String.format("Directory %s can't be created", directory.getAbsolutePath()));
            }
        }
    }

    static List<File> getLruListFiles(File directory) {
        List<File> result = new LinkedList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            result = Arrays.asList(files);
            Collections.sort(result, new LastAccessedComparator());
        }
        return result;
    }

    static void setLastAccessedNow(File file) throws IOException {
        if (file.exists()) {
            long now = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.nio.file.Files.setAttribute(file.toPath(), "lastAccessTime", now);
            } else {
                setLastModifiedNow(file);
            }
        }
    }

    static void setLastModifiedNow(File file) throws IOException {
        if (file.exists()) {
            long now = System.currentTimeMillis();
            boolean modified = file.setLastModified(now); // on some devices (e.g. Nexus 5) doesn't work
            if (!modified) {
                modify(file);
                if (file.lastModified() < now) {
                    // NOTE: apparently this is a known issue (see: http://stackoverflow.com/questions/6633748/file-lastmodified-is-never-what-was-set-with-file-setlastmodified)
                    Log.w("WARNING", "Last modified date {} is not set for file {}"+new Date(file.lastModified())+ file.getAbsolutePath());
                }
            }
        }
    }

    static void modify(File file) throws IOException {
        long size = file.length();
        if (size == 0) {
            recreateZeroSizeFile(file);
            return;
        }

        RandomAccessFile accessFile = new RandomAccessFile(file, "rwd");
        accessFile.seek(size - 1);
        byte lastByte = accessFile.readByte();
        accessFile.seek(size - 1);
        accessFile.write(lastByte);
        accessFile.close();
    }

    private static void recreateZeroSizeFile(File file) throws IOException {
        if (!file.delete() || !file.createNewFile()) {
            throw new IOException("Error recreate zero-size file " + file);
        }
    }

    private static final class LastAccessedComparator implements Comparator<File> {

        @Override
        public int compare(File lhs, File rhs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    final BasicFileAttributes lhsAttrs = java.nio.file.Files.readAttributes(lhs.toPath(), BasicFileAttributes.class);
                    final BasicFileAttributes rhsAttrs = java.nio.file.Files.readAttributes(rhs.toPath(), BasicFileAttributes.class);
                    return Long.compare(lhsAttrs.lastAccessTime().toMillis(), rhsAttrs.lastAccessTime().toMillis());
                } catch (IOException ignore) {
                }

            }
            return Long.compare(lhs.lastModified(), rhs.lastModified());
        }

    }

}
