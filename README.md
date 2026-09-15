# S2E RCON Bridge

Lets Stream to Earn reward viewers on a Minecraft server you don't host. S2E thinks it is
running a local server; the bridge forwards every command to your real server over RCON.

Download `s2e-bridge.jar` and `connection.properties` into the same folder.

## 1. Enable RCON on your server

Edit `server.properties` through your host's file manager:

```properties
enable-rcon=true
rcon.port=YOUR_ALLOCATION_PORT
rcon.password=YOUR_RCON_PASSWORD
broadcast-rcon-to-ops=false
```

- **`rcon.port` needs its own port**, separate from the game port. On most panels
  (HolyHosting, Shockbyte) that means adding an extra **allocation** under the **Network**
  tab and using that number here.
- `broadcast-rcon-to-ops=false` keeps command output out of operator chat. The bridge
  pings the server every 60s to hold the connection open, and every reward runs a command,
  so leaving it `true` fills your own chat while you stream.

Restart the server.

## 2. Fill in connection.properties

```properties
host=YOUR_SERVER_ADDRESS
port=YOUR_ALLOCATION_PORT
password=YOUR_RCON_PASSWORD
```

`host` and `port` are your RCON allocation. `password` is the `rcon.password` you just set.

## 3. Select the jar in Stream to Earn

Choose `s2e-bridge.jar` where S2E asks for your server jar. Done.

To check it without S2E: run `java -jar s2e-bridge.jar`, type `list`, then `stop`.

## If something fails

| Message | Fix |
| --- | --- |
| `'host' is empty in connection.properties` | The file was never filled in. |
| `connection failed: Connection refused` | Wrong port, or the allocation is not assigned to this server. |
| `authentication rejected: check rcon.password` | `password` does not match `rcon.password`. |
| `Unable to initialise RCON on 0.0.0.0:25565` *(server log)* | `rcon.port` is the game port. Use a separate allocation. |
| `No rcon password set in server.properties, rcon disabled!` *(server log)* | `rcon.password` is blank. |

## Notes

- `stop`, `save-all`, `reload` and friends are answered locally and never reach your
  server. S2E cannot shut it down by accident.
- Every line S2E sends is recorded in `commands.log`, useful for seeing its exact command
  format.

## Building from source

`./build.sh` rebuilds `s2e-bridge.jar`. Needs a JDK; running the jar does not. It will not
overwrite a `connection.properties` that already has your values in it.
