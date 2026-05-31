using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MirrorReceiverCs
{
    public class MdnsAdvertiser
    {
        private const string MulticastIp = "224.0.0.251";
        private const int MdnsPort = 5353;
        private readonly int _listenPort;
        private readonly string _serviceName; // e.g. "Mirror (HOSTNAME)"
        private readonly string _hostName;    // e.g. "mirror-hostname.local"
        private readonly string _serviceType = "_mirror._tcp.local";
        
        private UdpClient _udpListener;
        private CancellationTokenSource _cts;
        private bool _isRunning;

        public MdnsAdvertiser(int listenPort = 8765)
        {
            _listenPort = listenPort;
            string cleanMachineName = Environment.MachineName.Replace(" ", "-").ToLower();
            _serviceName = $"Mirror ({Environment.MachineName})";
            _hostName = $"{cleanMachineName}.local";
        }

        public void Start()
        {
            if (_isRunning) return;
            _isRunning = true;
            _cts = new CancellationTokenSource();

            // 1. Start mDNS listening thread to respond to queries
            Task.Run(() => ListenLoop(_cts.Token), _cts.Token);

            // 2. Start mDNS advertising thread to send unsolicited announcements
            Task.Run(() => AnnounceLoop(_cts.Token), _cts.Token);
        }

        public void Stop()
        {
            if (!_isRunning) return;
            _isRunning = false;
            _cts?.Cancel();

            if (_udpListener != null)
            {
                try
                {
                    _udpListener.Close();
                }
                catch { }
                _udpListener = null;
            }
        }

        private void ListenLoop(CancellationToken token)
        {
            try
            {
                _udpListener = new UdpClient();
                _udpListener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _udpListener.ExclusiveAddressUse = false;
                _udpListener.Client.Bind(new IPEndPoint(IPAddress.Any, MdnsPort));
                _udpListener.JoinMulticastGroup(IPAddress.Parse(MulticastIp));
                
                Console.WriteLine("[mDNS Bonjour] Listening on port 5353 and joined multicast group");

                while (!token.IsCancellationRequested)
                {
                    IPEndPoint remoteEP = new IPEndPoint(IPAddress.Any, 0);
                    byte[] data = _udpListener.Receive(ref remoteEP);

                    // Check if it's a query for our service.
                    // We check if the packet contains "_mirror" in UTF-8
                    string packetText = Encoding.UTF8.GetString(data);
                    if (packetText.Contains("_mirror"))
                    {
                        Console.WriteLine($"[mDNS Bonjour] Received query from {remoteEP}, sending response");
                        SendResponse();
                    }
                }
            }
            catch (Exception ex)
            {
                if (_isRunning)
                {
                    Console.WriteLine($"[mDNS Bonjour] Listener error: {ex.Message}");
                }
            }
        }

        private async Task AnnounceLoop(CancellationToken token)
        {
            // Send initial announcements
            for (int i = 0; i < 3; i++)
            {
                if (token.IsCancellationRequested) return;
                SendResponse();
                await Task.Delay(1000, token);
            }

            // Periodic announcement every 10 seconds to keep caches warm
            while (!token.IsCancellationRequested)
            {
                try
                {
                    SendResponse();
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[mDNS Bonjour] Error sending announcement: {ex.Message}");
                }
                await Task.Delay(10000, token);
            }
        }

        private void SendResponse()
        {
            string ip = UdpAdvertiser.GetLocalIpAddress();
            if (string.IsNullOrEmpty(ip)) return;

            byte[] responseBytes = BuildResponsePacket(ip);

            using (var sender = new UdpClient())
            {
                sender.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
                var multicastEP = new IPEndPoint(IPAddress.Parse(MulticastIp), MdnsPort);
                sender.Send(responseBytes, responseBytes.Length, multicastEP);
            }
        }

        private byte[] BuildResponsePacket(string ipAddressStr)
        {
            List<byte> packet = new List<byte>();

            // --- Header Section ---
            packet.AddRange(new byte[] { 0x00, 0x00 }); // Transaction ID (always 0 for mDNS responses)
            packet.AddRange(new byte[] { 0x84, 0x00 }); // Flags: Response, Authoritative, No Recursion
            packet.AddRange(new byte[] { 0x00, 0x00 }); // Questions: 0
            packet.AddRange(new byte[] { 0x00, 0x04 }); // Answer RRs: 4 (PTR, SRV, TXT, A)
            packet.AddRange(new byte[] { 0x00, 0x00 }); // Authority RRs: 0
            packet.AddRange(new byte[] { 0x00, 0x00 }); // Additional RRs: 0

            // --- Answer 1: PTR Record ---
            // Name: _mirror._tcp.local
            WriteDomainName(packet, _serviceType);
            packet.AddRange(new byte[] { 0x00, 0x0c }); // Type: PTR (12)
            packet.AddRange(new byte[] { 0x00, 0x01 }); // Class: IN (1)
            packet.AddRange(new byte[] { 0x00, 0x00, 0x00, 0x78 }); // TTL: 120s

            // Target pointer string: Mirror (Hostname)._mirror._tcp.local
            List<byte> ptrTargetBytes = new List<byte>();
            string fullServiceName = $"{_serviceName}.{_serviceType}";
            WriteDomainName(ptrTargetBytes, fullServiceName);
            
            packet.AddRange(BigEndianBytes((ushort)ptrTargetBytes.Count)); // RDLength
            packet.AddRange(ptrTargetBytes); // RData

            // --- Answer 2: SRV Record ---
            // Name: Mirror (Hostname)._mirror._tcp.local
            WriteDomainName(packet, fullServiceName);
            packet.AddRange(new byte[] { 0x00, 0x21 }); // Type: SRV (33)
            packet.AddRange(new byte[] { 0x80, 0x01 }); // Class: IN (1) + Cache Flush (0x8000)
            packet.AddRange(new byte[] { 0x00, 0x00, 0x00, 0x78 }); // TTL: 120s

            List<byte> srvBytes = new List<byte>();
            srvBytes.AddRange(new byte[] { 0x00, 0x00 }); // Priority: 0
            srvBytes.AddRange(new byte[] { 0x00, 0x00 }); // Weight: 0
            srvBytes.AddRange(BigEndianBytes((ushort)_listenPort)); // Port
            WriteDomainName(srvBytes, _hostName); // Target hostname

            packet.AddRange(BigEndianBytes((ushort)srvBytes.Count)); // RDLength
            packet.AddRange(srvBytes); // RData

            // --- Answer 3: TXT Record ---
            // Name: Mirror (Hostname)._mirror._tcp.local
            WriteDomainName(packet, fullServiceName);
            packet.AddRange(new byte[] { 0x00, 0x10 }); // Type: TXT (16)
            packet.AddRange(new byte[] { 0x80, 0x01 }); // Class: IN (1) + Cache Flush (0x8000)
            packet.AddRange(new byte[] { 0x00, 0x00, 0x00, 0x78 }); // TTL: 120s

            List<byte> txtBytes = new List<byte>();
            // Add txt values as length-prefixed strings
            AddTxtValue(txtBytes, "v=1");
            AddTxtValue(txtBytes, "name=" + _serviceName);
            AddTxtValue(txtBytes, "caps=video");

            packet.AddRange(BigEndianBytes((ushort)txtBytes.Count)); // RDLength
            packet.AddRange(txtBytes); // RData

            // --- Answer 4: A Record ---
            // Name: mirror-hostname.local
            WriteDomainName(packet, _hostName);
            packet.AddRange(new byte[] { 0x00, 0x01 }); // Type: A (1)
            packet.AddRange(new byte[] { 0x80, 0x01 }); // Class: IN (1) + Cache Flush (0x8000)
            packet.AddRange(new byte[] { 0x00, 0x00, 0x00, 0x78 }); // TTL: 120s

            IPAddress ip = IPAddress.Parse(ipAddressStr);
            byte[] ipBytes = ip.GetAddressBytes();
            packet.AddRange(BigEndianBytes((ushort)ipBytes.Length)); // RDLength
            packet.AddRange(ipBytes); // RData (4 bytes IPv4 address)

            return packet.ToArray();
        }

        private static void WriteDomainName(List<byte> buffer, string name)
        {
            var parts = name.Split('.');
            foreach (var part in parts)
            {
                if (string.IsNullOrEmpty(part)) continue;
                byte[] bytes = Encoding.UTF8.GetBytes(part);
                buffer.Add((byte)bytes.Length);
                buffer.AddRange(bytes);
            }
            buffer.Add(0); // Null terminator
        }

        private static void AddTxtValue(List<byte> buffer, string kv)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(kv);
            buffer.Add((byte)bytes.Length);
            buffer.AddRange(bytes);
        }

        private static byte[] BigEndianBytes(ushort val)
        {
            byte[] bytes = BitConverter.GetBytes(val);
            if (BitConverter.IsLittleEndian)
            {
                Array.Reverse(bytes);
            }
            return bytes;
        }
    }
}
