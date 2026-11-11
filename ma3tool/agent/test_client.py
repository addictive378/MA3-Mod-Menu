import socket, sys, time

def call(sock, line, timeout=10):
    sock.sendall((line + "\n").encode())
    sock.settimeout(timeout)
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    return buf.decode(errors="replace").strip()

def main():
    s = socket.create_connection(("127.0.0.1", 47321), timeout=8)
    for cmd in (sys.argv[1:] or ["PING", "GET"]):
        print(">", cmd)
        print("<", call(s, cmd))
        time.sleep(0.2)
    s.close()

main()
