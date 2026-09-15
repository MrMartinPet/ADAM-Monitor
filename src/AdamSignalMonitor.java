import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free Swing monitor for Advantech ADAM digital inputs over Modbus TCP.
 * Java 17+
 */
public final class AdamSignalMonitor {
    private static final Color DARK_BG = new Color(18, 22, 28);
    private static final Color DARK_CARD = new Color(31, 37, 46);
    private static final Color DARK_TEXT = new Color(238, 242, 247);
    private static final Color MUTED = new Color(151, 161, 176);
    private static final Color GREEN = new Color(39, 190, 105);
    private static final Color RED = new Color(232, 76, 76);
    private static final Color AMBER = new Color(246, 174, 45);
    private static final Color OFF = new Color(78, 87, 100);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JFrame frame = new JFrame("ADAM Signal Monitor");
    private final JPanel cardsPanel = new JPanel();
    private final JLabel summary = new JLabel("Starting...");
    private final JLabel lastUpdate = new JLabel("—");
    private final JButton pauseButton = new JButton("Pause");
    private final Map<DeviceConfig, DeviceCard> cards = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "adam-scheduler"));
    private ExecutorService pollPool;
    private volatile boolean paused;
    private List<DeviceConfig> devices;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            new AdamSignalMonitor().start();
        });
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        return t;
    }

    private void start() {
        devices = DeviceConfig.load(findConfigFile());
        pollPool = Executors.newFixedThreadPool(Math.max(2, Math.min(8, devices.size())), r -> daemon(r, "adam-poll"));
        buildUi();
        frame.setVisible(true);
        scheduler.scheduleAtFixedRate(this::pollAll, 100, 750, TimeUnit.MILLISECONDS);
    }

    private Path findConfigFile() {
        Path besideJar = Paths.get(System.getProperty("user.dir"), "devices.csv");
        if (Files.exists(besideJar)) return besideJar;
        Path projectPath = Paths.get(System.getProperty("user.dir"), "config", "devices.csv");
        return Files.exists(projectPath) ? projectPath : besideJar;
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(980, 650));
        frame.setSize(1280, 820);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(DARK_BG);
        frame.setLayout(new BorderLayout());

        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setBackground(DARK_BG);
        header.setBorder(new EmptyBorder(20, 24, 14, 24));
        JLabel title = new JLabel("ADAM SIGNAL MONITOR");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
        title.setForeground(Color.WHITE);
        header.add(title, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controls.setOpaque(false);
        styleButton(pauseButton);
        JButton refreshButton = new JButton("Poll now");
        styleButton(refreshButton);
        pauseButton.addActionListener(e -> {
            paused = !paused;
            pauseButton.setText(paused ? "Resume" : "Pause");
            if (!paused) pollAll();
        });
        refreshButton.addActionListener(e -> pollAll());
        controls.add(summary);
        controls.add(refreshButton);
        controls.add(pauseButton);
        summary.setForeground(MUTED);
        summary.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        header.add(controls, BorderLayout.EAST);
        frame.add(header, BorderLayout.NORTH);

        cardsPanel.setLayout(new GridLayout(0, 2, 14, 14));
        cardsPanel.setBackground(DARK_BG);
        cardsPanel.setBorder(new EmptyBorder(4, 24, 20, 24));
        for (DeviceConfig device : devices) {
            DeviceCard card = new DeviceCard(device);
            cards.put(device, card);
            cardsPanel.add(card);
        }
        JScrollPane scroll = new JScrollPane(cardsPanel);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(DARK_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        frame.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(23, 28, 35));
        footer.setBorder(new EmptyBorder(9, 24, 9, 24));
        JLabel protocol = new JLabel("Modbus TCP · Function 02 (Discrete Inputs)");
        protocol.setForeground(MUTED);
        lastUpdate.setForeground(MUTED);
        footer.add(protocol, BorderLayout.WEST);
        footer.add(lastUpdate, BorderLayout.EAST);
        frame.add(footer, BorderLayout.SOUTH);

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                scheduler.shutdownNow();
                pollPool.shutdownNow();
            }
        });
    }

    private void styleButton(JButton button) {
        button.setFocusPainted(false);
        button.setBackground(new Color(49, 58, 70));
        button.setForeground(Color.WHITE);
        button.setBorder(new EmptyBorder(8, 13, 8, 13));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void pollAll() {
        if (paused || pollPool.isShutdown()) return;
        for (Map.Entry<DeviceConfig, DeviceCard> entry : cards.entrySet()) {
            DeviceCard card = entry.getValue();
            if (!card.polling.compareAndSet(false, true)) continue;
            pollPool.submit(() -> {
                long started = System.nanoTime();
                try {
                    boolean[] inputs = ModbusTcp.readDiscreteInputs(entry.getKey());
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    SwingUtilities.invokeLater(() -> card.showOnline(inputs, ms));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> card.showOffline(shortMessage(ex)));
                } finally {
                    card.polling.set(false);
                }
            });
        }
        SwingUtilities.invokeLater(this::updateSummary);
    }

    private static String shortMessage(Exception ex) {
        String text = ex.getMessage();
        if (text == null || text.isBlank()) return ex.getClass().getSimpleName();
        return text.length() > 52 ? text.substring(0, 49) + "..." : text;
    }

    private void updateSummary() {
        long online = cards.values().stream().filter(c -> c.online).count();
        summary.setText(online + " / " + cards.size() + " online");
        lastUpdate.setText("Last update: " + LocalTime.now().format(TIME));
    }

    private static final class DeviceCard extends JPanel {
        final DeviceConfig device;
        final AtomicBoolean polling = new AtomicBoolean();
        final JLabel status = new JLabel("CONNECTING");
        final JLabel detail = new JLabel("Waiting for first response");
        final List<SignalLed> leds = new ArrayList<>();
        volatile boolean online;

        DeviceCard(DeviceConfig device) {
            this.device = device;
            setLayout(new BorderLayout(8, 14));
            setBackground(DARK_CARD);
            setBorder(new EmptyBorder(17, 18, 17, 18));

            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            JLabel name = new JLabel(device.name);
            name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
            name.setForeground(DARK_TEXT);
            JLabel address = new JLabel(device.host + ":" + device.port);
            address.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            address.setForeground(MUTED);
            JPanel names = new JPanel(new GridLayout(0, 1, 0, 3));
            names.setOpaque(false);
            names.add(name);
            names.add(address);
            top.add(names, BorderLayout.WEST);
            status.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            status.setForeground(AMBER);
            top.add(status, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);

            int columns = device.count <= 8 ? 4 : 6;
            JPanel signalGrid = new JPanel(new GridLayout(0, columns, 9, 9));
            signalGrid.setOpaque(false);
            for (int i = 0; i < device.count; i++) {
                SignalLed led = new SignalLed(device.signalName(i));
                leds.add(led);
                signalGrid.add(led);
            }
            add(signalGrid, BorderLayout.CENTER);

            detail.setForeground(MUTED);
            detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            add(detail, BorderLayout.SOUTH);
        }

        void showOnline(boolean[] values, long latencyMs) {
            online = true;
            status.setText("● ONLINE");
            status.setForeground(GREEN);
            int active = 0;
            for (int i = 0; i < leds.size(); i++) {
                boolean value = i < values.length && values[i];
                leds.get(i).setOn(value);
                if (value) active++;
            }
            detail.setText(active + " active · " + latencyMs + " ms · " + LocalTime.now().format(TIME));
        }

        void showOffline(String reason) {
            online = false;
            status.setText("● OFFLINE");
            status.setForeground(RED);
            leds.forEach(SignalLed::setUnknown);
            detail.setText(reason);
        }
    }

    private static final class SignalLed extends JPanel {
        private final JLabel lamp = new JLabel("●", SwingConstants.CENTER);
        private final JLabel label;

        SignalLed(String name) {
            setLayout(new BorderLayout(0, 2));
            setOpaque(true);
            setBackground(new Color(25, 30, 38));
            setBorder(new EmptyBorder(8, 5, 7, 5));
            lamp.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            label = new JLabel(name, SwingConstants.CENTER);
            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            label.setForeground(MUTED);
            add(lamp, BorderLayout.CENTER);
            add(label, BorderLayout.SOUTH);
            setUnknown();
        }

        void setOn(boolean on) {
            lamp.setForeground(on ? GREEN : OFF);
            label.setForeground(on ? DARK_TEXT : MUTED);
            setToolTipText(on ? "ON" : "OFF");
        }

        void setUnknown() {
            lamp.setForeground(new Color(63, 69, 78));
            label.setForeground(new Color(112, 120, 132));
            setToolTipText("No connection");
        }
    }

    private record DeviceConfig(String name, String host, int port, int unitId,
                                int startAddress, int count, List<String> signalNames) {
        String signalName(int index) {
            return index < signalNames.size() && !signalNames.get(index).isBlank()
                    ? signalNames.get(index) : "DI " + index;
        }

        static List<DeviceConfig> load(Path file) {
            List<DeviceConfig> result = new ArrayList<>();
            if (Files.exists(file)) {
                try {
                    for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String line = raw.trim();
                        if (line.isEmpty() || line.startsWith("#") || line.toLowerCase(Locale.ROOT).startsWith("name,")) continue;
                        String[] p = line.split(",", -1);
                        if (p.length < 6) continue;
                        List<String> names = p.length < 7 || p[6].isBlank()
                                ? List.of() : Arrays.stream(p[6].split("\\|", -1)).map(String::trim).toList();
                        result.add(new DeviceConfig(p[0].trim(), p[1].trim(), Integer.parseInt(p[2].trim()),
                                Integer.parseInt(p[3].trim()), Integer.parseInt(p[4].trim()),
                                Integer.parseInt(p[5].trim()), names));
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(null, "Could not read devices.csv:\n" + ex.getMessage(),
                            "Configuration error", JOptionPane.ERROR_MESSAGE);
                }
            }
            if (!result.isEmpty()) return result;
            return List.of(
                    defaultDevice("ADAM-6052", "10.33.114.96"),
                    defaultDevice("R404 RIGHT", "10.33.114.102"),
                    defaultDevice("R403", "10.33.114.103"),
                    defaultDevice("LB3", "10.33.114.104"),
                    defaultDevice("FV13", "10.33.114.105"),
                    defaultDevice("FV14", "10.33.114.106"),
                    defaultDevice("FV17", "10.33.114.115")
            );
        }

        static DeviceConfig defaultDevice(String name, String host) {
            return new DeviceConfig(name, host, 502, 1, 0, 8, List.of());
        }
    }

    private static final class ModbusTcp {
        private static final AtomicInteger TRANSACTION = new AtomicInteger(1);

        static boolean[] readDiscreteInputs(DeviceConfig d) throws IOException {
            int transaction = TRANSACTION.updateAndGet(v -> v >= 65535 ? 1 : v + 1);
            byte[] request = new byte[12];
            putU16(request, 0, transaction);
            putU16(request, 2, 0);              // Protocol ID
            putU16(request, 4, 6);              // Remaining bytes
            request[6] = (byte) d.unitId;
            request[7] = 0x02;                  // Read Discrete Inputs
            putU16(request, 8, d.startAddress);
            putU16(request, 10, d.count);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(d.host, d.port), 650);
                socket.setSoTimeout(800);
                socket.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                out.write(request);
                out.flush();

                byte[] mbap = readExact(in, 7);
                int responseTransaction = u16(mbap, 0);
                int length = u16(mbap, 4);
                if (responseTransaction != transaction) throw new IOException("Invalid transaction response");
                if (length < 3 || length > 260) throw new IOException("Invalid Modbus length: " + length);
                byte[] pdu = readExact(in, length - 1);
                int function = pdu[0] & 0xFF;
                if ((function & 0x80) != 0) {
                    int code = pdu.length > 1 ? pdu[1] & 0xFF : -1;
                    throw new IOException("Modbus exception " + code);
                }
                if (function != 0x02 || pdu.length < 2) throw new IOException("Unexpected Modbus response");
                int byteCount = pdu[1] & 0xFF;
                if (pdu.length < 2 + byteCount) throw new IOException("Incomplete Modbus response");
                boolean[] values = new boolean[d.count];
                for (int i = 0; i < d.count; i++) {
                    int dataIndex = 2 + (i / 8);
                    values[i] = dataIndex < pdu.length && ((pdu[dataIndex] >> (i % 8)) & 1) == 1;
                }
                return values;
            }
        }

        private static byte[] readExact(InputStream in, int count) throws IOException {
            byte[] data = new byte[count];
            int offset = 0;
            while (offset < count) {
                int read = in.read(data, offset, count - offset);
                if (read < 0) throw new EOFException("Connection closed by unit");
                offset += read;
            }
            return data;
        }

        private static int u16(byte[] b, int offset) {
            return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
        }

        private static void putU16(byte[] b, int offset, int value) {
            b[offset] = (byte) (value >>> 8);
            b[offset + 1] = (byte) value;
        }
    }
}
