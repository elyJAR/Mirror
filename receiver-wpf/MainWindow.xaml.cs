using System;
using System.Collections.Generic;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace MirrorReceiverCs
{
    public partial class MainWindow : Window
    {
        private UdpAdvertiser _udpAdvertiser;
        private MdnsAdvertiser _mdnsAdvertiser;
        private TcpReceiverServer _tcpServer;

        // Pairing & Handshake State
        private string _currentPin;
        private int _pinAttempts;
        private const int MaxPinAttempts = 3;
        private readonly HashSet<string> _trustedDevices = new HashSet<string>();
        private string _currentClientIp;

        // Projection Window State
        private Window _projectionWindow;
        private Microsoft.Web.WebView2.Wpf.WebView2 _projectionWebView;
        private bool _isProjecting;

        public MainWindow()
        {
            InitializeComponent();
            
            // Register closing handler to cleanup background threads cleanly
            Closing += MainWindow_Closing;
            
            // Start background networking services
            StartServices();

            // Initialize web browser control
            InitializeWebView();
        }

        private void StartServices()
        {
            _udpAdvertiser = new UdpAdvertiser();
            _udpAdvertiser.Start();

            _mdnsAdvertiser = new MdnsAdvertiser();
            _mdnsAdvertiser.Start();

            _tcpServer = new TcpReceiverServer();
            _tcpServer.ClientConnected += OnClientConnected;
            _tcpServer.ClientDisconnected += OnClientDisconnected;
            _tcpServer.ControlMessageReceived += OnControlMessageReceived;
            _tcpServer.VideoFrameReceived += OnVideoFrameReceived;
            _tcpServer.AudioFrameReceived += OnAudioFrameReceived;
            _tcpServer.Start();
        }

        private void StopServices()
        {
            _udpAdvertiser?.Stop();
            _mdnsAdvertiser?.Stop();
            _tcpServer?.Stop();
        }

        private async void InitializeWebView()
        {
            try
            {
                await webView.EnsureCoreWebView2Async();

                // Map local folder html/ to https://mirror-receiver.local/
                string htmlDir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "html");
                webView.CoreWebView2.SetVirtualHostNameToFolderMapping(
                    "mirror-receiver.local",
                    htmlDir,
                    Microsoft.Web.WebView2.Core.CoreWebView2HostResourceAccessKind.Allow);

                webView.Source = new Uri("https://mirror-receiver.local/index.html");
                webView.CoreWebView2.WebMessageReceived += WebView_WebMessageReceived;

                // Send local network details once the webview loads
                webView.CoreWebView2.NavigationCompleted += (s, e) =>
                {
                    webView.ZoomFactor = 1.0;
                    SendIpDetailsToWebView();
                };
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to initialize WebView2: {ex.Message}\nMake sure WebView2 Runtime is installed.", "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void SendIpDetailsToWebView()
        {
            string ip = UdpAdvertiser.GetLocalIpAddress() ?? "Unknown IP";
            var localIpData = new
            {
                type = "local-ip",
                ip = ip + ":8765",
                deviceName = $"Mirror ({Environment.MachineName})",
                execPath = System.Reflection.Assembly.GetExecutingAssembly().Location,
                isPackaged = true
            };
            PostToWebView(JsonConvert.SerializeObject(localIpData));
        }

        private void MainWindow_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            // Close secondary projection window
            CloseProjectionWindow();
            
            // Stop advertisements and servers
            StopServices();
        }

        // --- TCP Server Event Handlers ---

        private void OnClientConnected(string clientIp)
        {
            _currentClientIp = clientIp;
            _pinAttempts = 0;
            _currentPin = new Random().Next(1000, 10000).ToString();

            Console.WriteLine($"[Main Window] Peer connected: {clientIp}, Pin generated: {_currentPin}");

            // Notify WebView of the connection and pairing PIN
            PostToWebView(JsonConvert.SerializeObject(new { type = "peer-connected", address = clientIp }));
            PostToWebView(JsonConvert.SerializeObject(new { type = "pairing-pin", pin = _currentPin }));

            if (_isProjecting)
            {
                PostToProjection(JsonConvert.SerializeObject(new { type = "peer-connected", address = clientIp }));
            }
        }

        private void OnClientDisconnected()
        {
            _currentClientIp = null;
            
            // Reset projection state on disconnect
            Dispatcher.InvokeAsync(CloseProjectionWindow);

            PostToWebView(JsonConvert.SerializeObject(new { type = "peer-disconnected" }));
        }

        private void OnControlMessageReceived(string json)
        {
            try
            {
                var msg = JObject.Parse(json);
                string type = msg["type"]?.ToString();

                // If not explicitly declared, infer type
                if (string.IsNullOrEmpty(type))
                {
                    if (msg["device"] != null && msg["codecs"] != null)
                    {
                        type = "hello";
                    }
                }

                // Forward control message to WebView for logging/state display
                PostToWebView(JsonConvert.SerializeObject(new { type = "control-message", payload = msg }));
                if (_isProjecting)
                {
                    PostToProjection(JsonConvert.SerializeObject(new { type = "control-message", payload = msg }));
                }

                if (type == "hello")
                {
                    var codecsToken = msg["codecs"] as JArray;
                    List<string> codecs = new List<string>();
                    if (codecsToken != null)
                    {
                        foreach (var token in codecsToken) codecs.Add(token.ToString());
                    }

                    string chosenCodec = codecs.Contains("video/hevc") ? "video/hevc" : "video/avc";
                    bool isTrusted = _trustedDevices.Contains(_currentClientIp);

                    var ack = new
                    {
                        type = "hello-ack",
                        receiver = "Mirror PC (C#)",
                        @params = new { width = 1280, height = 720, fps = 30, codec = chosenCodec },
                        pinRequired = !isTrusted
                    };

                    _tcpServer.SendControl(JsonConvert.SerializeObject(ack));

                    if (isTrusted)
                    {
                        PostToWebView(JsonConvert.SerializeObject(new { type = "pairing-success" }));
                        if (_isProjecting)
                        {
                            PostToProjection(JsonConvert.SerializeObject(new { type = "pairing-success" }));
                        }
                    }
                }
                else if (type == "verify-pin")
                {
                    string pin = msg["pin"]?.ToString();
                    bool isMatch = (pin == _currentPin);

                    var authResult = new
                    {
                        type = "auth-result",
                        success = isMatch,
                        message = isMatch ? "Pairing successful" : "Incorrect PIN. Please try again."
                    };

                    _tcpServer.SendControl(JsonConvert.SerializeObject(authResult));

                    if (isMatch)
                    {
                        if (!string.IsNullOrEmpty(_currentClientIp))
                        {
                            _trustedDevices.Add(_currentClientIp);
                        }
                        PostToWebView(JsonConvert.SerializeObject(new { type = "pairing-success" }));
                        if (_isProjecting)
                        {
                            PostToProjection(JsonConvert.SerializeObject(new { type = "pairing-success" }));
                        }
                    }
                    else
                    {
                        _pinAttempts++;
                        if (_pinAttempts >= MaxPinAttempts)
                        {
                            Console.WriteLine("[Main Window] Too many incorrect PIN attempts, disconnecting peer.");
                            _tcpServer.Stop();
                            _tcpServer.Start(); // Restart listener
                        }
                    }
                }
                else if (type == "extend_display")
                {
                    // Toggle projection window triggered from phone
                    Dispatcher.InvokeAsync(ToggleProjection);
                }
                else if (type == "ping")
                {
                    var pong = new
                    {
                        type = "pong",
                        timestamp = msg["timestamp"]
                    };
                    _tcpServer.SendControl(JsonConvert.SerializeObject(pong));
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[Main Window] Error handling control message: {ex.Message}");
            }
        }

        private void OnVideoFrameReceived(long pts, byte[] data)
        {
            string base64 = Convert.ToBase64String(data);
            var frame = new
            {
                type = "video-frame",
                data = base64,
                pts = pts
            };
            string json = JsonConvert.SerializeObject(frame);
            PostToWebView(json);

            if (_isProjecting)
            {
                PostToProjection(json);
            }
        }

        private void OnAudioFrameReceived(long pts, byte[] data)
        {
            string base64 = Convert.ToBase64String(data);
            var frame = new
            {
                type = "audio-frame",
                data = base64,
                pts = pts
            };
            string json = JsonConvert.SerializeObject(frame);
            PostToWebView(json);

            if (_isProjecting)
            {
                PostToProjection(json);
            }
        }

        // --- WebView Message Bridges ---

        private void WebView_WebMessageReceived(object sender, Microsoft.Web.WebView2.Core.CoreWebView2WebMessageReceivedEventArgs e)
        {
            try
            {
                string json = e.TryGetWebMessageAsString();
                var msg = JObject.Parse(json);
                string type = msg["type"]?.ToString();

                if (type == "touch" || type == "key" || type == "request-keyframe")
                {
                    // Forward input gestures to the phone
                    _tcpServer.SendControl(json);
                }
                else if (type == "project")
                {
                    ToggleProjection();
                }
                else if (type == "sync-state" && _isProjecting)
                {
                    // Sync A/V play times and wall clock to projection window
                    PostToProjection(json);
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[Main Window] WebView message error: {ex.Message}");
            }
        }

        private void PostToWebView(string json)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                try
                {
                    if (webView != null && webView.CoreWebView2 != null)
                    {
                        webView.CoreWebView2.PostWebMessageAsString(json);
                    }
                }
                catch { }
            }), DispatcherPriority.Render);
        }

        private void PostToProjection(string json)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                try
                {
                    if (_projectionWebView != null && _projectionWebView.CoreWebView2 != null)
                    {
                        _projectionWebView.CoreWebView2.PostWebMessageAsString(json);
                    }
                }
                catch { }
            }), DispatcherPriority.Render);
        }

        // --- Secondary Display Projection ---

        private void ToggleProjection()
        {
            if (_isProjecting)
            {
                CloseProjectionWindow();
            }
            else
            {
                OpenProjectionWindow();
            }
        }

        private void OpenProjectionWindow()
        {
            var screens = System.Windows.Forms.Screen.AllScreens;
            if (screens.Length < 2)
            {
                MessageBox.Show("Secondary monitor not detected.", "Info", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            // Find secondary monitor
            var primaryScreen = System.Windows.Forms.Screen.PrimaryScreen;
            var secondaryScreen = screens[0];
            if (secondaryScreen.Primary && screens.Length > 1)
            {
                secondaryScreen = screens[1];
            }

            _projectionWindow = new Window
            {
                Title = "Mirror Projection",
                WindowStyle = WindowStyle.None,
                WindowState = WindowState.Maximized,
                Background = Brushes.Black,
                Left = secondaryScreen.Bounds.X,
                Top = secondaryScreen.Bounds.Y,
                Width = secondaryScreen.Bounds.Width,
                Height = secondaryScreen.Bounds.Height,
                UseLayoutRounding = true
            };

            var grid = new System.Windows.Controls.Grid();
            _projectionWebView = new Microsoft.Web.WebView2.Wpf.WebView2();
            grid.Children.Add(_projectionWebView);
            _projectionWindow.Content = grid;

            _projectionWindow.Closed += (s, ev) =>
            {
                _isProjecting = false;
                _projectionWindow = null;
                _projectionWebView = null;
                NotifyProjectionState(false);
            };

            _isProjecting = true;
            NotifyProjectionState(true);
            _projectionWindow.Show();

            InitializeProjectionWebView();
        }

        private async void InitializeProjectionWebView()
        {
            try
            {
                await _projectionWebView.EnsureCoreWebView2Async();

                string htmlDir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "html");
                _projectionWebView.CoreWebView2.SetVirtualHostNameToFolderMapping(
                    "mirror-receiver.local",
                    htmlDir,
                    Microsoft.Web.WebView2.Core.CoreWebView2HostResourceAccessKind.Allow);

                _projectionWebView.Source = new Uri("https://mirror-receiver.local/index.html?mode=projection");
                
                _projectionWebView.CoreWebView2.NavigationCompleted += (s, e) =>
                {
                    _projectionWebView.ZoomFactor = 1.0;
                    // Sync current connection states to projection
                    if (!string.IsNullOrEmpty(_currentClientIp))
                    {
                        PostToProjection(JsonConvert.SerializeObject(new { type = "peer-connected", address = _currentClientIp }));
                        PostToProjection(JsonConvert.SerializeObject(new { type = "pairing-success" }));
                        
                        // Request a video keyframe for the new window
                        _tcpServer.SendControl(JsonConvert.SerializeObject(new { type = "request-keyframe" }));
                    }
                };
            }
            catch { }
        }

        private void CloseProjectionWindow()
        {
            if (_projectionWindow != null)
            {
                try
                {
                    _projectionWindow.Close();
                }
                catch { }
                _projectionWindow = null;
                _projectionWebView = null;
            }
            _isProjecting = false;
            NotifyProjectionState(false);
        }

        private void NotifyProjectionState(bool isProjected)
        {
            PostToWebView(JsonConvert.SerializeObject(new { type = "projection-state", active = isProjected }));
            _tcpServer.SendControl(JsonConvert.SerializeObject(new { type = "projection_state", active = isProjected }));
        }
    }
}
