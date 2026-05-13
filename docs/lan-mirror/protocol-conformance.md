# Mirror Protocol Conformance Specification

This document defines the wire-level requirements for any "Mirror Receiver" implementation. Passing this checklist ensures compatibility with the `mirror-stream` Android library.

## 1. Discovery (mDNS)
1. [ ] MUST advertise via mDNS (Bonjour/Avahi).
2. [ ] Service type MUST be `_mirror._tcp`.
3. [ ] MUST include TXT record `v=1`.
4. [ ] MUST respond to queries on all network interfaces.

## 2. Transport (TCP)
1. [ ] MUST listen on a TCP port (default 8765).
2. [ ] MUST handle multiple sequential connections (one at a time).
3. [ ] SHOULD reject concurrent connections with a `hello-reject` message.

## 3. Framing
1. [ ] EVERY packet MUST follow the `[Tag:4][Length:4][Payload:N]` format.
2. [ ] `Tag` MUST be `CTRL` (0x43 0x54 0x52 0x4C) or `VIDO` (0x56 0x49 0x44 0x4F).
3. [ ] `Length` MUST be a Big-Endian 32-bit unsigned integer.
4. [ ] Receiver MUST be able to handle frames up to 8MB without crashing.

## 4. Handshake
1. [ ] Receiver MUST wait for the sender to send a `hello` JSON message.
2. [ ] Receiver MUST respond with `hello-ack` containing its receiver name.
3. [ ] Handshake MUST complete within 5 seconds.

## 5. Video Stream
1. [ ] Video frames are raw H.264 NAL units (no start codes, no Annex-B).
2. [ ] Receiver MUST be able to decode NAL type 7 (SPS) and 8 (PPS) before the first IDR.
3. [ ] Receiver MUST support H.264 Baseline Profile Level 3.1.
4. [ ] Receiver SHOULD handle dynamic bitrate changes mid-stream.

## 6. Control Signals
1. [ ] Receiver MUST respond to `ping` with a `pong` containing the same timestamp.
2. [ ] Receiver SHOULD send `request-keyframe` if its decoder experiences a synchronization loss or buffer underflow.
3. [ ] Receiver SHOULD send `bye` before closing the socket.

## 7. Performance
1. [ ] Receiver MUST aim for < 100ms glass-to-glass latency.
2. [ ] Receiver MUST NOT buffer more than 2 frames locally before rendering.
