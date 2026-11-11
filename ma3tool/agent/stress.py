import socket, sys, time

N = int(sys.argv[1]) if len(sys.argv) > 1 else 100
s = socket.create_connection(("127.0.0.1", 47321), timeout=8)
f = s.makefile("rwb")
bad = 0
for i in range(N):
    f.write(b"GET\n"); f.flush()
    line = f.readline().decode().strip()
    if not line.startswith("OK "):
        bad += 1
        print("iter", i, "->", line)
        if bad > 3:
            break
print("selesai", N, "iterasi, gagal:", bad)
f.write(b"DIAG\n"); f.flush()
print("diag:", f.readline().decode().strip())
s.close()
