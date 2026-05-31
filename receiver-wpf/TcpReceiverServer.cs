using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MirrorReceiverCs
{
    public class TcpReceiverServer
    {
        private readonly int _port;
        private TcpListener _listener;
        private TcpClient _activeClient;
        private CancellationTokenSource _cts;
        private readonly object _sendLock = new object();
        private bool _isRunning;

        // Events
        public event Action<string> ClientConnected;
        public event Action ClientDisconnected;
        public event Action<string> ControlMessageReceived;
        public event Action<long, byte[]> VideoFrameReceived;
        public event Action<long, byte[]> AudioFrameReceived;

        public TcpReceiverServer(int port = 8765)
        {
            _port = port;
        }

        public void Start()
        {
            if (_isRunning) return;
            _isRunning = true;
            _cts = new CancellationTokenSource();
            _listener = new TcpListener(IPAddress.Any, _port);
            _listener.Start();

            Console.WriteLine($"[TCP Server] Listening on port {_port}");
            Task.Run(() => AcceptLoop(_cts.Token));
        }

        public void Stop()
        {
            if (!_isRunning) return;
            _isRunning = false;
            _cts?.Cancel();

            try { _listener?.Stop(); } catch { }
            DisconnectActiveClient();
        }

        public void SendControl(string json)
        {
            TcpClient client = _activeClient;
            if (client == null || !client.Connected) return;

            try
            {
                byte[] payload = Encoding.UTF8.GetBytes(json);
                byte[] header = new byte[5];
                header[0] = 0x01; // Tag: Control

                byte[] lenBytes = BitConverter.GetBytes(payload.Length);
                if (BitConverter.IsLittleEndian) Array.Reverse(lenBytes);
                Array.Copy(lenBytes, 0, header, 1, 4);

                lock (_sendLock)
                {
                    NetworkStream stream = client.GetStream();
                    stream.Write(header, 0, 5);
                    stream.Write(payload, 0, payload.Length);
                    stream.Flush();
                }
                Console.WriteLine($"[TCP Server] Sent control message: {json}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[TCP Server] Failed to write control frame: {ex.Message}");
                DisconnectActiveClient();
            }
        }

        private async Task AcceptLoop(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    TcpClient client = await _listener.AcceptTcpClientAsync();
                    string remoteIp = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString();

                    if (_activeClient != null && _activeClient.Connected)
                    {
                        // Busy: Reject second client with hello-reject message
                        Console.WriteLine($"[TCP Server] Rejecting incoming connection from {remoteIp} (busy)");
                        Task.Run(() => RejectClient(client));
                    }
                    else
                    {
                        Console.WriteLine($"[TCP Server] Client connected from {remoteIp}");
                        _activeClient = client;
                        client.NoDelay = true;
                        client.LingerState = new LingerOption(true, 0);
                        ClientConnected?.Invoke(remoteIp);
                        
                        // Start reading on a background thread
                        Task.Run(() => ReadLoop(client, token));
                    }
                }
                catch (Exception ex)
                {
                    if (_isRunning)
                    {
                        Console.WriteLine($"[TCP Server] Accept loop error: {ex.Message}");
                    }
                }
            }
        }

        private void RejectClient(TcpClient client)
        {
            try
            {
                string rejectJson = "{\"type\":\"hello-reject\",\"reason\":\"busy\",\"message\":\"Another receiver session is already active.\"}";
                byte[] payload = Encoding.UTF8.GetBytes(rejectJson);
                byte[] header = new byte[5];
                header[0] = 0x01; // Tag: Control

                byte[] lenBytes = BitConverter.GetBytes(payload.Length);
                if (BitConverter.IsLittleEndian) Array.Reverse(lenBytes);
                Array.Copy(lenBytes, 0, header, 1, 4);

                using (NetworkStream stream = client.GetStream())
                {
                    stream.Write(header, 0, 5);
                    stream.Write(payload, 0, payload.Length);
                    stream.Flush();
                }
            }
            catch { }
            finally
            {
                try { client.Close(); } catch { }
            }
        }

        private void ReadLoop(TcpClient client, CancellationToken token)
        {
            try
            {
                using (NetworkStream stream = client.GetStream())
                {
                    byte[] headerBuffer = new byte[5];
                    while (!token.IsCancellationRequested && client.Connected)
                    {
                        // Read 5-byte header
                        ReadExactly(stream, headerBuffer, 5);
                        byte tag = headerBuffer[0];

                        byte[] lenBytes = new byte[4];
                        Array.Copy(headerBuffer, 1, lenBytes, 0, 4);
                        if (BitConverter.IsLittleEndian) Array.Reverse(lenBytes);
                        int length = BitConverter.ToInt32(lenBytes, 0);

                        if (length < 0 || length > 8 * 1024 * 1024)
                        {
                            throw new InvalidDataException($"Invalid frame length: {length}");
                        }

                        // Read payload
                        byte[] payload = new byte[length];
                        ReadExactly(stream, payload, length);

                        // Process frame
                        HandleFrame(tag, payload);
                    }
                }
            }
            catch (Exception ex)
            {
                if (_isRunning && _activeClient == client)
                {
                    Console.WriteLine($"[TCP Server] Read loop closed: {ex.Message}");
                }
            }
            finally
            {
                if (_activeClient == client)
                {
                    DisconnectActiveClient();
                }
            }
        }

        private void ReadExactly(NetworkStream stream, byte[] buffer, int count)
        {
            int offset = 0;
            while (offset < count)
            {
                int read = stream.Read(buffer, offset, count - offset);
                if (read == 0) throw new EndOfStreamException("Socket closed by peer");
                offset += read;
            }
        }

        private void HandleFrame(byte tag, byte[] payload)
        {
            if (tag == 0x01) // Control JSON
            {
                string json = Encoding.UTF8.GetString(payload).Trim();
                ControlMessageReceived?.Invoke(json);
            }
            else if (tag == 0x02) // Video frame
            {
                if (payload.Length >= 8)
                {
                    byte[] ptsBytes = new byte[8];
                    Array.Copy(payload, 0, ptsBytes, 0, 8);
                    if (BitConverter.IsLittleEndian) Array.Reverse(ptsBytes);
                    long pts = BitConverter.ToInt64(ptsBytes, 0);

                    byte[] data = new byte[payload.Length - 8];
                    Array.Copy(payload, 8, data, 0, data.Length);

                    VideoFrameReceived?.Invoke(pts, data);
                }
            }
            else if (tag == 0x03) // Audio frame
            {
                if (payload.Length >= 8)
                {
                    byte[] ptsBytes = new byte[8];
                    Array.Copy(payload, 0, ptsBytes, 0, 8);
                    if (BitConverter.IsLittleEndian) Array.Reverse(ptsBytes);
                    long pts = BitConverter.ToInt64(ptsBytes, 0);

                    byte[] data = new byte[payload.Length - 8];
                    Array.Copy(payload, 8, data, 0, data.Length);

                    AudioFrameReceived?.Invoke(pts, data);
                }
            }
        }

        private void DisconnectActiveClient()
        {
            TcpClient client = _activeClient;
            if (client != null)
            {
                _activeClient = null;
                try { client.Close(); } catch { }
                Console.WriteLine("[TCP Server] Client disconnected");
                ClientDisconnected?.Invoke();
            }
        }
    }
}
