using System;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MirrorReceiverCs
{
    public class UdpAdvertiser
    {
        private readonly int _listenPort;
        private readonly int _broadcastPort;
        private readonly string _advertisedName;
        private UdpClient _udpClient;
        private CancellationTokenSource _cts;
        private bool _isRunning;

        public UdpAdvertiser(int listenPort = 8765, int broadcastPort = 8768)
        {
            _listenPort = listenPort;
            _broadcastPort = broadcastPort;
            _advertisedName = $"Mirror ({Environment.MachineName})";
        }

        public void Start()
        {
            if (_isRunning) return;
            _isRunning = true;
            _cts = new CancellationTokenSource();

            Task.Run(async () =>
            {
                while (!_cts.Token.IsCancellationRequested)
                {
                    try
                    {
                        SendBroadcast(false);
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[UDP Broadcast] Error in advertising loop: {ex.Message}");
                    }
                    await Task.Delay(2000, _cts.Token);
                }
            }, _cts.Token);
        }

        public void Stop()
        {
            if (!_isRunning) return;
            _isRunning = false;
            _cts?.Cancel();

            try
            {
                // Send three goodbye broadcasts on exit to ensure delivery over unreliable UDP
                for (int i = 0; i < 3; i++)
                {
                    SendBroadcast(true);
                }
                Console.WriteLine("[UDP Broadcast] Sent shutdown goodbye packets");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[UDP Broadcast] Failed to send goodbye packet: {ex.Message}");
            }

            if (_udpClient != null)
            {
                try { _udpClient.Close(); } catch { }
                _udpClient = null;
            }
        }

        private void SendBroadcast(bool quit)
        {
            string ip = GetLocalIpAddress();
            if (string.IsNullOrEmpty(ip)) return;

            string message;
            if (quit)
            {
                message = $"{{\"name\":\"{_advertisedName}\",\"port\":{_listenPort},\"ip\":\"{ip}\",\"quit\":true}}";
            }
            else
            {
                message = $"{{\"name\":\"{_advertisedName}\",\"port\":{_listenPort},\"ip\":\"{ip}\"}}";
            }

            byte[] payload = Encoding.UTF8.GetBytes(message);

            if (_udpClient == null)
            {
                _udpClient = new UdpClient();
                _udpClient.EnableBroadcast = true;
            }

            IPEndPoint endPoint = new IPEndPoint(IPAddress.Broadcast, _broadcastPort);
            _udpClient.Send(payload, payload.Length, endPoint);
        }

        public static string GetLocalIpAddress()
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                string lowerName = ni.Name.ToLower();
                string lowerDesc = ni.Description.ToLower();
                if (lowerName.Contains("veth") || lowerName.Contains("vethernet") ||
                    lowerName.Contains("vmware") || lowerName.Contains("virtual") ||
                    lowerName.Contains("virtualbox") || lowerName.Contains("vbox") ||
                    lowerName.Contains("wsl") || lowerName.Contains("hyper-v") ||
                    lowerName.Contains("docker") || lowerName.Contains("vpn") ||
                    lowerName.Contains("zerotier") || lowerName.Contains("tailscale") ||
                    lowerName.Contains("host-only") || lowerName.Contains("pseudo") ||
                    lowerName.Contains("loopback"))
                    continue;

                if (lowerDesc.Contains("veth") || lowerDesc.Contains("vethernet") ||
                    lowerDesc.Contains("vmware") || lowerDesc.Contains("virtual") ||
                    lowerDesc.Contains("virtualbox") || lowerDesc.Contains("vbox") ||
                    lowerDesc.Contains("wsl") || lowerDesc.Contains("hyper-v") ||
                    lowerDesc.Contains("docker") || lowerDesc.Contains("vpn") ||
                    lowerDesc.Contains("zerotier") || lowerDesc.Contains("tailscale") ||
                    lowerDesc.Contains("host-only") || lowerDesc.Contains("pseudo") ||
                    lowerDesc.Contains("loopback"))
                    continue;

                var ipProps = ni.GetIPProperties();
                foreach (var addr in ipProps.UnicastAddresses)
                {
                    if (addr.Address.AddressFamily == AddressFamily.InterNetwork)
                    {
                        string ipStr = addr.Address.ToString();
                        if (!ipStr.StartsWith("169.254.")) // Exclude APIPA
                        {
                            return ipStr;
                        }
                    }
                }
            }
            return null;
        }
    }
}
