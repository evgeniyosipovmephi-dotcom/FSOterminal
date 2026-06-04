package fsoterminal.channel;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.util.function.Consumer;

/**
 * Обёртка над COM-портом (jSerialComm 2.11.4).
 *
 * Использование:
 *   SerialChannel ch = new SerialChannel();
 *   ch.setReceiveHandler(ackProcessor::feed);
 *   ch.open("COM3", 115200);
 *   ch.send(frame);
 *   ch.close();
 */
public class SerialChannel {

    private SerialPort        port;
    // volatile: пишется из UI/тест-потока, читается из потока-слушателя jSerialComm
    private volatile Consumer<byte[]> receiveHandler;
    // Вызывается при физическом отключении порта (USB выдернут) — мгновенно, без таймаута PROBE
    private volatile Runnable disconnectHandler;

    // -------------------------------------------------------------------------
    // Список портов
    // -------------------------------------------------------------------------

    /**
     * Возвращает строки для отображения в UI: "COM3 — USB Serial Port".
     * Если описание отсутствует — просто "COM3".
     * Для открытия порта передавать эти же строки в open() — он сам извлекает системное имя.
     */
    public static String[] availablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] items = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            String sysName = ports[i].getSystemPortName();
            String desc    = ports[i].getPortDescription();
            items[i] = (desc != null && !desc.isBlank() && !desc.equals(sysName))
                ? sysName + " — " + desc
                : sysName;
        }
        return items;
    }

    /** Извлекает системное имя порта из строки UI ("COM3 — desc" → "COM3"). */
    private static String systemPortName(String item) {
        if (item == null) return null;
        int sep = item.indexOf(" — ");
        return sep > 0 ? item.substring(0, sep) : item;
    }

    // -------------------------------------------------------------------------
    // Открытие / закрытие
    // -------------------------------------------------------------------------

    /**
     * Открыть порт.
     *
     * @param portName системное имя ("COM3", "/dev/ttyUSB0")
     * @param baudRate скорость (обычно 115200)
     * @return true если порт открыт успешно
     */
    public boolean open(String portNameOrItem, int baudRate) {
        if (port != null && port.isOpen()) close();

        port = SerialPort.getCommPort(systemPortName(portNameOrItem));
        port.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        port.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_RECEIVED
                     | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                if ((event.getEventType() & SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) != 0) {
                    Runnable dh = disconnectHandler;
                    if (dh != null) dh.run();
                    return;
                }
                byte[] data = event.getReceivedData();
                if (receiveHandler != null && data != null && data.length > 0)
                    receiveHandler.accept(data);
            }
        });

        return port.openPort();
    }

    /** Закрыть порт. */
    public void close() {
        if (port != null) {
            port.removeDataListener();
            port.closePort();
            port = null;
        }
    }

    // -------------------------------------------------------------------------
    // Отправка
    // -------------------------------------------------------------------------

    /**
     * Отправить байты в порт. Неблокирующий — jSerialComm пишет в буфер ОС.
     * Безопасно вызывать из frameOutput (под synchronized в SlidingWindowSender).
     */
    /**
     * Отправить байты. Synchronized — jSerialComm не гарантирует thread-safety
     * для writeBytes при одновременном вызове из нескольких потоков.
     * (ретрансмит-таймер + jSerialComm receive callback могут писать параллельно)
     */
    public synchronized void send(byte[] data) {
        if (port != null && port.isOpen())
            port.writeBytes(data, data.length);
    }

    // -------------------------------------------------------------------------
    // Приём
    // -------------------------------------------------------------------------

    /**
     * Установить обработчик входящих байтов.
     * Вызывается из потока jSerialComm — обработчик должен быть thread-safe.
     * Обычно: channel.setReceiveHandler(ackProcessor::feed)
     */
    public void setReceiveHandler(Consumer<byte[]> handler) {
        this.receiveHandler = handler;
    }

    /** Установить обработчик физического отключения порта (USB выдернут). */
    public void setDisconnectHandler(Runnable handler) {
        this.disconnectHandler = handler;
    }

    // -------------------------------------------------------------------------
    // Состояние
    // -------------------------------------------------------------------------

    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    /** Системное имя открытого порта или null если закрыт. */
    public String getPortName() {
        return port != null ? port.getSystemPortName() : null;
    }
}
