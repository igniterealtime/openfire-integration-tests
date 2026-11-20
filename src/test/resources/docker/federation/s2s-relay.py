# Relay for server-to-server connections in the federated test environment, run by FederatedTestEnvironment when
# Stream Management is enabled. Port 5269 leads to xmpp2, port 5270 to xmpp1.
#
# Signals control the connections open at the time:
#   SIGUSR1  freeze: what either server sends from now on is read and dropped, so it stays unacknowledged.
#   SIGUSR2  break: each connection is closed in an orderly way (FIN) towards both servers, without closing the
#            streams on it, so that the sessions can be resumed. Nothing unread is left on a socket, so no reset is
#            sent. Connections made later are relayed as normal.
import asyncio
import signal

ROUTES = {5269: ("172.50.0.20", 5269), 5270: ("172.50.0.10", 5269)}
connections = set()


class Connection:
    def __init__(self):
        self.frozen = False
        self.writers = []


async def pipe(connection, reader, writer):
    try:
        while data := await reader.read(65536):
            if not connection.frozen:
                writer.write(data)
                await writer.drain()
    except OSError:
        pass
    finally:
        try:
            if writer.can_write_eof() and not writer.is_closing():
                writer.write_eof()
        except OSError:
            pass


async def relay(client_reader, client_writer, route):
    connection = Connection()
    try:
        server_reader, server_writer = await asyncio.open_connection(*route)
    except OSError:
        client_writer.close()
        return
    connection.writers = [client_writer, server_writer]
    connections.add(connection)
    try:
        await asyncio.gather(pipe(connection, client_reader, server_writer),
                             pipe(connection, server_reader, client_writer))
    finally:
        connections.discard(connection)
        for w in connection.writers:
            w.close()


def freeze():
    for connection in connections:
        connection.frozen = True


def break_connections():
    for connection in list(connections):
        connection.frozen = True
        for w in connection.writers:
            try:
                if not w.is_closing():
                    w.write_eof()
            except OSError:
                pass


async def main():
    loop = asyncio.get_running_loop()
    loop.add_signal_handler(signal.SIGUSR1, freeze)
    loop.add_signal_handler(signal.SIGUSR2, break_connections)
    servers = [await asyncio.start_server(lambda r, w, route=route: relay(r, w, route), "0.0.0.0", port, reuse_address=True)
               for port, route in ROUTES.items()]
    print("relay ready", flush=True)
    await asyncio.gather(*(s.serve_forever() for s in servers))


asyncio.run(main())
