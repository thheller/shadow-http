package shadow.http.server;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A buffered input stream that mirrors the semantics of {@link java.io.BufferedInputStream}
 * but without synchronization. This is safe to use when only a single thread accesses the
 * stream at a time, which is the case for HTTP connection processing.
 *
 * <p>Not thread-safe. Do not share instances across threads without external synchronization.</p>
 */
public class UnsyncBufferedInputStream extends FilterInputStream {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    /**
     * The internal buffer array where the data is stored.
     */
    protected byte[] buf;

    /**
     * The index one greater than the index of the last valid byte in
     * the buffer. Value is in the range [0, buf.length].
     */
    protected int count;

    /**
     * The current position in the buffer. This is the index of the next
     * byte to be read from the buf array. Value is in the range [0, count].
     */
    protected int pos;

    /**
     * The value of the pos field at the time the last mark method was called.
     * -1 if there is no current mark.
     */
    protected int markpos = -1;

    /**
     * The maximum read ahead allowed after a call to the mark method before
     * subsequent calls to the reset method fail.
     */
    protected int marklimit;

    public UnsyncBufferedInputStream(InputStream in) {
        this(in, DEFAULT_BUFFER_SIZE);
    }

    public UnsyncBufferedInputStream(InputStream in, int size) {
        super(in);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = new byte[size];
    }

    private InputStream getInIfOpen() throws IOException {
        InputStream input = in;
        if (input == null) {
            throw new IOException("Stream closed");
        }
        return input;
    }

    private byte[] getBufIfOpen() throws IOException {
        byte[] buffer = buf;
        if (buffer == null) {
            throw new IOException("Stream closed");
        }
        return buffer;
    }

    /**
     * Fills the buffer with more data, taking into account
     * shuffling and other tricks for dealing with marks.
     * Assumes that it is being called by some read method (read() or read(byte[], int, int)).
     * This method also assumes that all data has already been read in,
     * hence pos > count.
     */
    private void fill() throws IOException {
        byte[] buffer = getBufIfOpen();
        if (markpos == -1) {
            pos = 0; /* no mark: throw away the buffer */
        } else if (pos >= buffer.length) { /* no room left in buffer */
            if (markpos > 0) { /* can throw away early part of the buffer */
                int sz = pos - markpos;
                System.arraycopy(buffer, markpos, buffer, 0, sz);
                pos = sz;
                markpos = 0;
            } else if (buffer.length >= marklimit) {
                markpos = -1; /* buffer got too big, invalidate mark */
                pos = 0; /* drop buffer contents */
            } else { /* grow buffer */
                int nsz = Math.min(pos * 2, marklimit);
                if (nsz > marklimit)
                    nsz = marklimit;
                byte[] nbuf = new byte[nsz];
                System.arraycopy(buffer, 0, nbuf, 0, pos);
                buffer = nbuf;
                buf = nbuf;
            }
        }
        count = pos;
        int n = getInIfOpen().read(buffer, pos, buffer.length - pos);
        if (n > 0)
            count = n + pos;
    }

    @Override
    public int read() throws IOException {
        if (pos >= count) {
            fill();
            if (pos >= count)
                return -1;
        }
        return getBufIfOpen()[pos++] & 0xff;
    }

    /**
     * Read bytes into a portion of an array, reading from the underlying
     * stream at most once if necessary.
     */
    private int read1(byte[] b, int off, int len) throws IOException {
        int avail = count - pos;
        if (avail <= 0) {
            /* If the requested length is at least as large as the buffer, and
               if there is no mark/reset activity, do not bother to copy the
               bytes into the local buffer.  In this way buffered streams will
               cascade harmlessly. */
            if (len >= getBufIfOpen().length && markpos == -1) {
                return getInIfOpen().read(b, off, len);
            }
            fill();
            avail = count - pos;
            if (avail <= 0) return -1;
        }
        int cnt = Math.min(avail, len);
        System.arraycopy(getBufIfOpen(), pos, b, off, cnt);
        pos += cnt;
        return cnt;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        getBufIfOpen(); // Check for closed stream
        if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        int n = 0;
        for (;;) {
            int nread = read1(b, off + n, len - n);
            if (nread <= 0)
                return (n == 0) ? nread : n;
            n += nread;
            if (n >= len)
                return n;
            // if not closed but no bytes available, return
            InputStream input = in;
            if (input != null && input.available() <= 0)
                return n;
        }
    }

    @Override
    public long skip(long n) throws IOException {
        getBufIfOpen(); // Check for closed stream
        if (n <= 0) {
            return 0;
        }
        long avail = count - pos;

        if (avail <= 0) {
            // If no mark position set then don't keep in buffer
            if (markpos == -1)
                return getInIfOpen().skip(n);

            // Fill in buffer to save bytes for reset
            fill();
            avail = count - pos;
            if (avail <= 0)
                return 0;
        }

        long skipped = Math.min(avail, n);
        pos += (int) skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        int n = count - pos;
        int avail = getInIfOpen().available();
        return n > (Integer.MAX_VALUE - avail)
                ? Integer.MAX_VALUE
                : n + avail;
    }

    @Override
    public void mark(int readlimit) {
        marklimit = readlimit;
        markpos = pos;
    }

    @Override
    public void reset() throws IOException {
        getBufIfOpen(); // Cause exception if closed
        if (markpos < 0)
            throw new IOException("Resetting to invalid mark");
        pos = markpos;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void close() throws IOException {
        byte[] buffer;
        while ((buffer = buf) != null) {
            buf = null;
            InputStream input = in;
            in = null;
            if (input != null)
                input.close();
            return;
        }
    }
}
