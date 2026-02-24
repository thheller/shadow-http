package shadow.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Implements the "permessage-deflate" WebSocket extension per RFC 7692 Section 7.
 *
 * <p>Compression (Section 7.2.1):
 * <ol>
 *   <li>Compress payload with raw DEFLATE (no zlib wrapper).</li>
 *   <li>Ensure the output ends with an empty stored block (0x00 0x00 0xff 0xff).</li>
 *   <li>Strip those 4 trailing octets.</li>
 * </ol>
 *
 * <p>Decompression (Section 7.2.2):
 * <ol>
 *   <li>Append 0x00 0x00 0xff 0xff to the received payload.</li>
 *   <li>Inflate using raw DEFLATE.</li>
 * </ol>
 */
public class WebSocketCompression {

    private static final byte[] DEFLATE_TAIL = {0x00, 0x00, (byte) 0xff, (byte) 0xff};

    // --- Agreed parameters ---

    /** Server does NOT take over LZ77 context between messages (resets deflater each message). */
    public final boolean serverNoContextTakeover;

    /** Client does NOT take over LZ77 context between messages (resets inflater each message). */
    public final boolean clientNoContextTakeover;

    /** Server-side LZ77 window bits (8-15, default 15). */
    public final int serverMaxWindowBits;

    /** Client-side LZ77 window bits (8-15, default 15). */
    public final int clientMaxWindowBits;

    // Raw DEFLATE: pass negative windowBits to Deflater/Inflater
    private final Deflater deflater;
    private final Inflater inflater;

    private static final int BUFFER_SIZE = 8192;

    public WebSocketCompression(boolean serverNoContextTakeover,
                                boolean clientNoContextTakeover,
                                int serverMaxWindowBits,
                                int clientMaxWindowBits) {
        this.serverNoContextTakeover = serverNoContextTakeover;
        this.clientNoContextTakeover = clientNoContextTakeover;
        this.serverMaxWindowBits = serverMaxWindowBits;
        this.clientMaxWindowBits = clientMaxWindowBits;

        // Negative window bits = raw DEFLATE (no zlib header/trailer)
        this.deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        this.inflater = new Inflater(true);
    }

    /**
     * Compresses a message payload per RFC 7692 Section 7.2.1.
     *
     * @param input uncompressed payload bytes
     * @return compressed payload with the trailing 0x00 0x00 0xff 0xff removed
     */
    public byte[] compress(byte[] input) throws IOException {
        if (serverNoContextTakeover) {
            deflater.reset();
        }

        deflater.setInput(input);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
        byte[] buf = new byte[BUFFER_SIZE];

        while (!deflater.finished()) {
            int n = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            baos.write(buf, 0, n);
        }

        // If serverNoContextTakeover we called reset() already so no context to preserve.
        // Otherwise reset only the "finished" state so the context is kept.
        if (!serverNoContextTakeover) {
            deflater.reset();
        }

        byte[] compressed = baos.toByteArray();

        // Strip trailing 0x00 0x00 0xff 0xff (Section 7.2.1 step 3)
        int len = compressed.length;
        if (len >= 4
                && compressed[len - 4] == 0x00
                && compressed[len - 3] == 0x00
                && (compressed[len - 2] & 0xFF) == 0xFF
                && (compressed[len - 1] & 0xFF) == 0xFF) {
            byte[] result = new byte[len - 4];
            System.arraycopy(compressed, 0, result, 0, len - 4);
            return result;
        }

        return compressed;
    }

    /**
     * Decompresses a message payload per RFC 7692 Section 7.2.2.
     *
     * @param input compressed payload bytes (without the trailing 4 bytes)
     * @return decompressed payload bytes
     */
    public byte[] decompress(byte[] input) throws IOException {
        if (clientNoContextTakeover) {
            inflater.reset();
        }

        // Append 0x00 0x00 0xff 0xff (Section 7.2.2 step 1)
        byte[] withTail = new byte[input.length + 4];
        System.arraycopy(input, 0, withTail, 0, input.length);
        System.arraycopy(DEFLATE_TAIL, 0, withTail, input.length, 4);

        inflater.setInput(withTail);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length * 2);
        byte[] buf = new byte[BUFFER_SIZE];

        try {
            int n;
            while ((n = inflater.inflate(buf)) > 0) {
                baos.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException("WebSocket permessage-deflate decompression failed", e);
        }

        // When clientNoContextTakeover is false, the inflater context is preserved
        // automatically since we do not reset it between messages.

        return baos.toByteArray();
    }

    /**
     * Parses the client's "Sec-WebSocket-Extensions" header value and, if the client
     * offers "permessage-deflate", returns an instance configured with the agreed
     * parameters.  Returns {@code null} if the extension is not offered or cannot be
     * accepted.
     *
     * <p>The returned instance also exposes {@link #buildResponseHeaderValue()} so the
     * caller can include the agreed parameters in the server's opening-handshake response.
     *
     * @param headerValue the raw value of the client's Sec-WebSocket-Extensions header
     * @return a configured {@code PerMessageDeflate}, or {@code null}
     */
    public static WebSocketCompression negotiate(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return null;
        }

        // Split on "," to get individual extension negotiation offers
        for (String offer : headerValue.split(",")) {
            offer = offer.trim();
            if (offer.isEmpty()) {
                continue;
            }

            // Split on ";" to get extension name + parameters
            String[] parts = offer.split(";");
            String extName = parts[0].trim();

            if (!"permessage-deflate".equalsIgnoreCase(extName)) {
                continue;
            }

            // Parse parameters
            boolean serverNoCtx = false;
            boolean clientNoCtx = false;
            int serverWin = 15;
            int clientWin = 15;
            boolean clientMaxWindowBitsPresent = false;

            boolean valid = true;
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim();
                if (param.isEmpty()) {
                    continue;
                }
                int eqIdx = param.indexOf('=');
                String key = (eqIdx >= 0 ? param.substring(0, eqIdx) : param).trim().toLowerCase();
                String value = (eqIdx >= 0 ? param.substring(eqIdx + 1).trim().replace("\"", "") : null);

                switch (key) {
                    case "server_no_context_takeover":
                        serverNoCtx = true;
                        break;
                    case "client_no_context_takeover":
                        clientNoCtx = true;
                        break;
                    case "server_max_window_bits":
                        if (value != null) {
                            try {
                                int bits = Integer.parseInt(value);
                                if (bits < 8 || bits > 15) {
                                    valid = false;
                                } else {
                                    serverWin = bits;
                                }
                            } catch (NumberFormatException e) {
                                valid = false;
                            }
                        }
                        break;
                    case "client_max_window_bits":
                        clientMaxWindowBitsPresent = true;
                        if (value != null) {
                            try {
                                int bits = Integer.parseInt(value);
                                if (bits < 8 || bits > 15) {
                                    valid = false;
                                } else {
                                    clientWin = bits;
                                }
                            } catch (NumberFormatException e) {
                                valid = false;
                            }
                        }
                        break;
                    default:
                        // Unknown parameter – decline this offer (Section 7, first bullet)
                        valid = false;
                        break;
                }

                if (!valid) {
                    break;
                }
            }

            if (!valid) {
                continue; // try next offer
            }

            // Build and return the agreed PerMessageDeflate instance
            WebSocketCompression pmd = new WebSocketCompression(serverNoCtx, clientNoCtx, serverWin, clientWin);
            pmd.clientMaxWindowBitsOffered = clientMaxWindowBitsPresent;
            return pmd;
        }

        return null;
    }

    // Whether client_max_window_bits was present in the offer (affects response header)
    private boolean clientMaxWindowBitsOffered = false;

    /**
     * Builds the "Sec-WebSocket-Extensions" response header value for this negotiated
     * extension, to be sent back to the client in the server's opening handshake.
     */
    public String buildResponseHeaderValue() {
        StringBuilder sb = new StringBuilder("permessage-deflate");

        if (serverNoContextTakeover) {
            sb.append("; server_no_context_takeover");
        }
        if (clientNoContextTakeover) {
            sb.append("; client_no_context_takeover");
        }
        if (serverMaxWindowBits != 15) {
            sb.append("; server_max_window_bits=").append(serverMaxWindowBits);
        }
        // Only include client_max_window_bits in response if client offered it (Section 7.1.2.2)
        if (clientMaxWindowBitsOffered && clientMaxWindowBits != 15) {
            sb.append("; client_max_window_bits=").append(clientMaxWindowBits);
        }

        return sb.toString();
    }
}


