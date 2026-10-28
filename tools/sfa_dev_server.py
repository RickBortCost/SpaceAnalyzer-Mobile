#!/usr/bin/env python3
"""Development server for the SFA Link v1 protocol (see docs/PROTOCOL.md).

Streams synthetic waveforms so the Android app can be exercised before the Space desktop side
exists. It is a test tool only and is never shipped in the APK.

  pip install grpcio grpcio-tools
  python3 tools/sfa_dev_server.py --code 123456 [--port 47800] [--rate 20]

A self-signed certificate is generated on first run (needs the openssl binary) and its
SHA-256 fingerprint is printed: compare it with the one the app shows during pairing.
"""
import argparse, hashlib, json, math, queue, socket, subprocess, sys, tempfile, threading, time
from concurrent import futures
from pathlib import Path

import grpc
from grpc_tools import protoc

ROOT = Path(__file__).resolve().parents[1]
GEN = Path(tempfile.mkdtemp(prefix="sfa-gen-"))
if protoc.main(["protoc", f"-I{ROOT / 'app/src/main/proto'}", f"--python_out={GEN}", f"--grpc_python_out={GEN}",
                str(ROOT / "app/src/main/proto/sfa_link.proto")]) != 0:
    sys.exit("protoc failed")
sys.path.insert(0, str(GEN))
import sfa_link_pb2 as pb, sfa_link_pb2_grpc as rpc  # noqa: E402

SIGNALS = [
    (1, "Sine 1 Hz", "V", lambda t: math.sin(2 * math.pi * t)),
    (2, "Sine 0.1 Hz", "bar", lambda t: 5 + 3 * math.sin(2 * math.pi * 0.1 * t)),
    (3, "Saw 0.5 Hz", "°C", lambda t: 20 + 10 * ((t * 0.5) % 1)),
    (4, "Square 0.25 Hz", "", lambda t: float(int(t * 0.5) % 2)),
]


class Service(rpc.SfaLinkServicer):
    def __init__(self, code, rate, history):
        self.code, self.rate = code, rate
        self.lock = threading.Lock()
        self.history = []          # (seq, SampleBatch)
        self.max_history = history
        self.subs = []
        self.seq = 0
        threading.Thread(target=self.produce, daemon=True).start()

    def check(self, context):
        md = dict(context.invocation_metadata())
        if md.get("x-sfa-access-code") != self.code:
            context.abort(grpc.StatusCode.UNAUTHENTICATED, "access code")

    def produce(self):
        t0 = time.time()
        while True:
            now = time.time()
            b = pb.SampleBatch()
            for sid, _, _, f in SIGNALS:
                b.samples.add(signal_id=sid, timestamp_us=int(now * 1e6), value=f(now - t0), quality=0)
            with self.lock:
                self.seq += 1
                b.seq = self.seq
                self.history.append((b.seq, b))
                del self.history[:-self.max_history]
                subs = list(self.subs)
            for q in subs:
                q.put(b)
            time.sleep(1.0 / self.rate)

    def Hello(self, request, context):
        self.check(context)
        return pb.HelloReply(protocol_version=1, server_name=socket.gethostname(), acquisition_name="Dev synthetic")

    def ListSignals(self, request, context):
        self.check(context)
        return pb.ListSignalsReply(signals=[pb.Signal(id=i, name=n, unit=u) for i, n, u, _ in SIGNALS])

    def Subscribe(self, request, context):
        self.check(context)
        q = queue.Queue(maxsize=10000)
        with self.lock:
            backlog = [b for s, b in self.history if s > request.resume_after_seq] if request.resume_after_seq else []
            first_missing = (request.resume_after_seq and
                             (not self.history or self.history[0][0] > request.resume_after_seq + 1))
            self.subs.append(q)
        try:
            gap = bool(first_missing)
            for b in backlog:
                if gap:
                    b = pb.SampleBatch.FromString(b.SerializeToString()); b.gap = True; gap = False
                yield b
            last = backlog[-1].seq if backlog else 0
            while context.is_active():
                try:
                    b = q.get(timeout=1)
                except queue.Empty:
                    continue
                if b.seq > last:
                    yield b
        finally:
            with self.lock:
                self.subs.remove(q)


def make_cert(directory: Path):
    key, crt = directory / "key.pem", directory / "cert.pem"
    if not crt.exists():
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30", "-keyout", str(key),
                        "-out", str(crt), "-subj", "/CN=sfa-dev-server"], check=True, capture_output=True)
    der = ssl_der(crt)
    return key.read_bytes(), crt.read_bytes(), ":".join(f"{b:02X}" for b in hashlib.sha256(der).digest())


def ssl_der(pem_path: Path) -> bytes:
    import ssl
    return ssl.PEM_cert_to_DER_cert(pem_path.read_text())


def beacon(name, port, discovery_port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    msg = json.dumps({"sfa": 1, "name": name, "port": port}).encode()
    while True:
        s.sendto(msg, ("255.255.255.255", discovery_port))
        time.sleep(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=47800)
    ap.add_argument("--discovery-port", type=int, default=47810)
    ap.add_argument("--code", required=True)
    ap.add_argument("--rate", type=float, default=20, help="batches per second")
    ap.add_argument("--history", type=int, default=6000, help="batches kept for resume")
    ap.add_argument("--cert-dir", default=str(Path.home() / ".sfa-dev"))
    a = ap.parse_args()
    d = Path(a.cert_dir); d.mkdir(parents=True, exist_ok=True)
    key, crt, fp = make_cert(d)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8), options=[
        ("grpc.keepalive_permit_without_calls", 1), ("grpc.http2.min_ping_interval_without_data_ms", 5000)])
    rpc.add_SfaLinkServicer_to_server(Service(a.code, a.rate, a.history), server)
    server.add_secure_port(f"0.0.0.0:{a.port}", grpc.ssl_server_credentials([(key, crt)]))
    server.start()
    threading.Thread(target=beacon, args=(socket.gethostname(), a.port, a.discovery_port), daemon=True).start()
    print(f"SFA dev server on :{a.port}\nAccess code: {a.code}\nCertificate SHA-256:\n{fp}", flush=True)
    server.wait_for_termination()


if __name__ == "__main__":
    main()
