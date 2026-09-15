import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stream to Earn -> RCON bridge.
 *
 * S2E launches this jar as if it were a Minecraft server: it spawns the process,
 * reads stdout expecting a vanilla console log, and writes commands to stdin.
 * This bridge emulates that console contract and forwards accepted commands to a
 * remote Minecraft server over RCON.
 *
 * The only thing it asks for is connection.properties: host, port, password.
 * Everything else is fixed below, because it never needs to change per install.
 *
 * stdout is reserved for Minecraft-shaped log lines only. Bridge diagnostics go to
 * stderr so they can never be mistaken for server output.
 */
public class Main {

    /** Single source of truth for the version; build.sh reads it from here. */
    private static final String VERSION = "0.1.2";

    private static final String CONNECTION_FILE = "connection.properties";
    /** Append-only record of every line S2E sends, for inspecting its command format. */
    private static final String COMMAND_LOG_FILE = "commands.log";

    // --- RCON protocol constants -------------------------------------------------
    private static final int TYPE_RESPONSE_VALUE = 0;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_EXEC_COMMAND = 2;
    private static final int TYPE_AUTH = 3;

    /** id/type/two null terminators; the minimum legal payload size. */
    private static final int PACKET_OVERHEAD = 10;
    private static final int MAX_PACKET_LENGTH = 8192;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter AUDIT_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object AUDIT_LOCK = new Object();

    // --- Fixed behavior ----------------------------------------------------------
    private static final int RCON_TIMEOUT_MS = 5000;
    /** A reply this long was probably truncated by Minecraft's 4096-byte chunking. */
    private static final int LIKELY_SPLIT_LENGTH = 4000;
    /** Short wait for a follow-up chunk; expiring simply means the reply is complete. */
    private static final int FOLLOW_UP_TIMEOUT_MS = 400;
    /** Idle RCON connections get dropped, so keep one warm with a read-only command. */
    private static final int KEEPALIVE_SECONDS = 60;
    private static final String KEEPALIVE_COMMAND = "list";

    // Console log emulation. S2E reads these lines to decide the server is up.
    private static final String MC_VERSION = "1.20.1";
    private static final int MC_PORT = 25565;
    private static final String LEVEL_NAME = "world";
    private static final String GAME_TYPE = "SURVIVAL";

    /**
     * Answered locally and never forwarded. Sending "stop" over RCON would shut down
     * the remote server, which is not ours to stop.
     */
    private static final Set<String> BLOCKED_COMMANDS = new LinkedHashSet<>(
            Arrays.asList("stop", "save-all", "save-off", "save-on", "reload", "restart"));

    // --- Connection settings, read from connection.properties --------------------
    private static String rconHost;
    private static int rconPort;
    private static String rconPass;

    // --- Connection state --------------------------------------------------------
    private static Socket socket;
    private static DataInputStream in;
    private static OutputStream out;
    private static int requestId = 0;
    /** Why the last send failed, so commands.log records a cause and not a phrase. */
    private static String lastFailure = "";

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final long startedAt = System.nanoTime();

    public static void main(String[] args) {
        diag("s2e-bridge " + VERSION);
        if (!loadConnectionSettings()) {
            System.exit(1);
        }
        audit("START", "s2e-bridge " + VERSION);
        writeServerFilesIfMissing();

        Runtime.getRuntime().addShutdownHook(new Thread(Main::disconnect, "rcon-close"));

        emitBootLog();
        connect();
        emitReadyLog();
        startKeepAlive();

        readCommandLoop();
        shutdown();
    }

    // =============================================================================
    // Console emulation
    // =============================================================================

    private static void emitBootLog() {
        log("main", "Building unoptimized datafixer");
        log("main", "S2E RCON bridge " + VERSION);
        log("Server thread", "Starting minecraft server version " + MC_VERSION);
        log("Server thread", "Loading properties");
        log("Server thread", "Default game type: " + GAME_TYPE);
        log("Server thread", "Generating keypair");
        // No "Starting Minecraft server on *:PORT" line: nothing is actually bound
        // here, so advertising a port only invites S2E to probe something dead.
        log("Server thread", "Preparing level \"" + LEVEL_NAME + "\"");
    }

    private static void emitReadyLog() {
        log("Server thread", "Preparing start region for dimension minecraft:overworld");
        log("Server thread", "Time elapsed: " + elapsedMillis() + " ms");
        log("Server thread", "Done (" + elapsedSeconds() + "s)! For help, type \"help\"");
    }

    private static void emitStopLog() {
        log("Server thread", "Stopping the server");
        log("Server thread", "Stopping server");
        log("Server thread", "Saving players");
        log("Server thread", "Saving worlds");
        log("Server thread", "ThreadedAnvilChunkStorage (" + LEVEL_NAME + "): All chunks are saved");
        log("Server thread", "ThreadedAnvilChunkStorage: All dimensions are saved");
    }

    private static void log(String message) {
        log("Server thread", message);
    }

    private static synchronized void log(String thread, String message) {
        System.out.println("[" + LocalTime.now().format(CLOCK) + "] [" + thread + "/INFO]: " + message);
        System.out.flush();
    }

    private static void diag(String message) {
        System.err.println("[bridge] " + message);
        System.err.flush();
    }

    private static long elapsedMillis() {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String elapsedSeconds() {
        return String.format(Locale.ROOT, "%.3f", elapsedMillis() / 1000.0);
    }

    // =============================================================================
    // Command loop
    // =============================================================================

    private static void readCommandLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }
                // Recorded verbatim: this is the exact wire format S2E produces.
                audit("RECV", command);
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                dispatch(command);
            }
        } catch (IOException e) {
            diag("stdin closed: " + e.getMessage());
        }
    }

    private static void dispatch(String command) {
        String name = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);

        if (BLOCKED_COMMANDS.contains(name)) {
            handleLocally(name);
            return;
        }

        diag("forwarding: " + command);
        String response = sendCommand(command, true);
        if (response == null) {
            audit("FAIL", command + "  ->  " + lastFailure);
            log("Unable to reach the remote server");
            return;
        }
        audit("SENT", command + "  ->  " + summarize(response));
    }

    /** Never forwarded: these would act on a server that is not ours to control. */
    private static void handleLocally(String name) {
        diag("intercepted locally (not forwarded): " + name);
        audit("BLOCKED", name);
        switch (name) {
            case "stop":
            case "restart":
                running.set(false);
                shutdown();
                break;
            case "save-all":
                log("Saved the game");
                break;
            case "save-off":
                log("Automatic saving is now disabled");
                break;
            case "save-on":
                log("Automatic saving is now enabled");
                break;
            default:
                log("Reloading!");
                break;
        }
    }

    private static void shutdown() {
        if (!running.getAndSet(false)) {
            // Already shutting down from another path.
        }
        emitStopLog();
        disconnect();
        System.out.flush();
        System.exit(0);
    }

    private static void startKeepAlive() {
        if (KEEPALIVE_SECONDS <= 0) {
            return;
        }
        Thread keepAlive = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(KEEPALIVE_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running.get()) {
                    return;
                }
                // RCON servers drop idle connections; a cheap read-only command keeps it warm.
                sendCommand(KEEPALIVE_COMMAND, false);
            }
        }, "rcon-keepalive");
        keepAlive.setDaemon(true);
        keepAlive.start();
    }

    // =============================================================================
    // RCON client
    // =============================================================================

    private static synchronized String sendCommand(String command, boolean echo) {
        lastFailure = "no connection";
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!ensureConnected()) {
                continue; // a refused connect still gets the second attempt
            }
            try {
                String response = execute(command);
                if (echo) {
                    echoResponse(response);
                }
                return response;
            } catch (SocketTimeoutException e) {
                // A partial read desyncs the stream, so the socket cannot be reused.
                lastFailure = "timed out waiting for reply";
                diag(lastFailure + "; dropping connection");
                disconnect();
            } catch (IOException e) {
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
                diag("RCON transport failure: " + lastFailure);
                disconnect();
            }
        }
        return null;
    }

    private static void echoResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return;
        }
        for (String line : stripFormatting(response).split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                log(line);
            }
        }
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return true;
        }
        return connect();
    }

    private static boolean connect() {
        disconnect();
        try {
            diag("connecting to " + rconHost + ":" + rconPort);
            socket = new Socket();
            socket.connect(new InetSocketAddress(rconHost, rconPort), RCON_TIMEOUT_MS);
            socket.setSoTimeout(RCON_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();

            if (!authenticate()) {
                lastFailure = "authentication rejected: check rcon.password";
                diag(lastFailure);
                disconnect();
                return false;
            }
            diag("connected and authenticated");
            return true;
        } catch (IOException e) {
            lastFailure = "connect failed: " + e.getMessage();
            diag(lastFailure);
            disconnect();
            return false;
        }
    }

    private static boolean authenticate() throws IOException {
        int id = nextId();
        sendPacket(id, TYPE_AUTH, rconPass);

        // Some implementations emit an empty RESPONSE_VALUE before the auth verdict.
        for (int i = 0; i < 3; i++) {
            Packet packet = readPacket();
            if (packet.type == TYPE_AUTH_RESPONSE) {
                return packet.id == id; // the server answers -1 on a bad password
            }
        }
        return false;
    }

    /**
     * Runs a command and collects the full response.
     *
     * Minecraft splits output larger than one packet into 4096-byte chunks and sends no
     * end-of-response marker. The Valve convention of bouncing a dummy RESPONSE_VALUE
     * packet back does not work here: Minecraft answers it with "Unknown request 0" or
     * drops the connection. Instead, keep reading only while the last chunk came back
     * full, and treat a short read timeout as the end of the stream.
     */
    private static String execute(String command) throws IOException {
        int commandId = nextId();
        sendPacket(commandId, TYPE_EXEC_COMMAND, command);

        Packet packet = readPacket();
        StringBuilder body = new StringBuilder(packet.body);

        while (packet.body.length() >= LIKELY_SPLIT_LENGTH) {
            socket.setSoTimeout(FOLLOW_UP_TIMEOUT_MS);
            try {
                packet = readPacket();
                body.append(packet.body);
            } catch (SocketTimeoutException e) {
                break; // nothing more queued: the response ended on a full-sized chunk
            } finally {
                socket.setSoTimeout(RCON_TIMEOUT_MS);
            }
        }
        return body.toString();
    }

    private static void sendPacket(int id, int type, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        int length = PACKET_OVERHEAD + bodyBytes.length;

        ByteBuffer buffer = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(bodyBytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);

        out.write(buffer.array());
        out.flush();
    }

    private static Packet readPacket() throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();

        if (length < PACKET_OVERHEAD || length > MAX_PACKET_LENGTH) {
            throw new IOException("Illegal RCON packet length: " + length);
        }

        byte[] payload = new byte[length];
        in.readFully(payload);

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        String body = new String(payload, 8, length - PACKET_OVERHEAD, StandardCharsets.UTF_8);

        return new Packet(id, type, body);
    }

    private static void disconnect() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Closing a broken socket is best effort.
        }
        socket = null;
        in = null;
        out = null;
    }

    /** Request ids must stay positive; -1 is reserved for auth failures. */
    private static int nextId() {
        requestId++;
        if (requestId < 1 || requestId > 0x7000_0000) {
            requestId = 1;
        }
        return requestId;
    }

    private static String stripFormatting(String text) {
        return text.replaceAll("§[0-9A-Fa-fK-Ok-or]", "");
    }

    private static final class Packet {
        final int id;
        final int type;
        final String body;

        Packet(int id, int type, String body) {
            this.id = id;
            this.type = type;
            this.body = body;
        }
    }

    // =============================================================================
    // Command audit log
    // =============================================================================

    /**
     * Appends one line to commands.log. Reopening the file per entry costs nothing at
     * these rates and keeps the record intact if the process is killed.
     */
    private static void audit(String kind, String detail) {
        synchronized (AUDIT_LOCK) {
            try (Writer writer = new FileWriter(COMMAND_LOG_FILE, StandardCharsets.UTF_8, true)) {
                writer.write(LocalDateTime.now().format(AUDIT_CLOCK)
                        + "  " + String.format(Locale.ROOT, "%-7s", kind)
                        + "  " + detail + System.lineSeparator());
            } catch (IOException e) {
                diag("could not write " + COMMAND_LOG_FILE + ": " + e.getMessage());
            }
        }
    }

    /** Flattens a server reply to a single readable line for the audit record. */
    private static String summarize(String response) {
        String flat = stripFormatting(response).replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "(empty reply)";
        }
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }

    // =============================================================================
    // Connection settings
    // =============================================================================

    /**
     * Reads the three values needed to reach the remote RCON endpoint.
     *
     * Missing or incomplete settings abort the launch instead of falling back to a
     * default, so the bridge can never quietly point at somebody else's server.
     */
    private static boolean loadConnectionSettings() {
        File file = new File(CONNECTION_FILE);

        if (!file.exists()) {
            writeConnectionTemplate(file);
            fail("Created " + CONNECTION_FILE + " - fill in host, port and password, then start again.");
            return false;
        }

        Properties props = new Properties();
        try (InputStream input = new FileInputStream(file)) {
            props.load(input);
        } catch (IOException e) {
            fail("Cannot read " + CONNECTION_FILE + ": " + e.getMessage());
            return false;
        }

        rconHost = props.getProperty("host", "").trim();
        rconPass = props.getProperty("password", "").trim();
        String rawPort = props.getProperty("port", "").trim();

        if (rconHost.isEmpty()) {
            fail("'host' is empty in " + CONNECTION_FILE);
            return false;
        }
        if (rconPass.isEmpty()) {
            fail("'password' is empty in " + CONNECTION_FILE);
            return false;
        }
        if (rawPort.isEmpty()) {
            fail("'port' is empty in " + CONNECTION_FILE);
            return false;
        }

        try {
            rconPort = Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            fail("'port' is not a number in " + CONNECTION_FILE + ": " + rawPort);
            return false;
        }
        if (rconPort < 1 || rconPort > 65535) {
            fail("'port' is out of range in " + CONNECTION_FILE + ": " + rconPort);
            return false;
        }

        diag("target: " + rconHost + ":" + rconPort);
        return true;
    }

    private static void writeConnectionTemplate(File file) {
        String contents = ""
                + "# RCON connection to the remote Minecraft server.\n"
                + "# All three values are required.\n"
                + "host=\n"
                + "port=\n"
                + "password=\n";
        writeFile(file, contents, CONNECTION_FILE);
    }

    /** Reported on both streams so the reason is visible from S2E's console too. */
    private static void fail(String message) {
        diag(message);
        log("[S2E Bridge] " + message);
    }

    /** S2E may inspect the server folder, so keep it looking like a real installation. */
    private static void writeServerFilesIfMissing() {
        File eula = new File("eula.txt");
        if (!eula.exists()) {
            writeFile(eula, "eula=true\n", "eula.txt");
        }

        File serverProperties = new File("server.properties");
        if (!serverProperties.exists()) {
            String contents = ""
                    + "enable-rcon=false\n"
                    + "level-name=" + LEVEL_NAME + "\n"
                    + "gamemode=" + GAME_TYPE.toLowerCase(Locale.ROOT) + "\n"
                    + "server-port=" + MC_PORT + "\n"
                    + "max-players=20\n"
                    + "online-mode=true\n"
                    + "motd=S2E RCON Bridge\n";
            writeFile(serverProperties, contents, "server.properties");
        }
    }

    private static void writeFile(File file, String contents, String label) {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            diag("created " + label);
        } catch (IOException e) {
            diag("could not create " + label + ": " + e.getMessage());
        }
    }
}
