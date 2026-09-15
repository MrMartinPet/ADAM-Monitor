# ADAM Signal Monitor

Java desktop application for visualizing digital input signals from Advantech ADAM units over Modbus TCP.

## Download

Download **ADAM_Monitor_Encrypted.zip** from this repository and extract it with password:

```text
Dometic
```

The ZIP contains `ADAM_Monitor.jar`.

## Start on Windows

1. Install Java 17 or newer.
2. Extract the ZIP.
3. Double-click the JAR, or run:

```cmd
java -jar ADAM_Monitor.jar
```

The computer must be connected to the factory network containing `10.33.114.x`.

## Communication

- Modbus TCP port: `502`
- Unit ID: `1`
- Function: `02 – Read Discrete Inputs`
- Start address: `0`
- Polling interval: `750 ms`

The default IP addresses are built into the JAR. Place `devices.csv` beside the JAR to customize units and signal names.

## Build from source

```cmd
javac -encoding UTF-8 -d out src\AdamSignalMonitor.java
jar --create --file ADAM_Monitor.jar --main-class AdamSignalMonitor -C out .
```

No external Java libraries are required.
