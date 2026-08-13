using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;

namespace BLE_tcp_driver
{
    class TcpServer
    {
        private const int StatusQueryTimeoutMs = 2000;
        private const int DataFragmentSize = 180;
        private const int CommandFragmentSize = 20;
        private const int DataFragmentDelayMs = 12;

        private TcpListener _listener;
        private readonly BleCore _bleCore;
        private readonly IPAddress _bindAddress;
        private readonly int _port;
        private readonly List<TcpClient> _clients = new List<TcpClient>();
        private readonly object _clientLock = new object();
        private readonly SemaphoreSlim _blePacketGate = new SemaphoreSlim(1, 1);
        private readonly object _oledTransactionLock = new object();
        private TcpClient _oledTransactionOwner;
        private volatile bool _running;

        private sealed class StatusQueryRequest
        {
            public TcpClient Client;
            public byte[] RequestId;
            public long PipelineEpoch;
            public long ConnectionEpoch;
            public long Identity;
            public GattCharacteristic WriteCharacteristic;
            public GattCharacteristic NotifyCharacteristic;
            public bool Dispatched;
        }

        private DeviceStatusInfo _deviceStatus;
        private readonly Queue<StatusQueryRequest> _statusQueryQueue = new Queue<StatusQueryRequest>();
        private StatusQueryRequest _activeStatusQuery;
        private GattCharacteristic _activeNotifyCharacteristic;
        private long _statusPipelineEpoch;
        private long _nextStatusQueryIdentity;
        private bool _statusPipelineFaulted = true;
        private readonly object _statusLock = new object();

        /// <summary>
        /// 日志事件 (从后台线程触发, UI层需BeginInvoke)
        /// </summary>
        public event Action<string> OnLog;

        /// <summary>
        /// TCP客户端数量变化事件
        /// </summary>
        public event Action<int> OnClientCountChanged;

        /// <summary>
        /// 状态查询超时或发送失败后请求 UI 层强制释放并重连 BLE。
        /// </summary>
        public event Action<string> OnBleRecoveryRequired;

        public int Port => _port;
        public int ClientCount { get { lock (_clientLock) return _clients.Count; } }

        public TcpServer(BleCore bleCore, string serverIp = "127.0.0.1", int port = 9000)
        {
            _bleCore = bleCore;
            IPAddress parsedAddress;
            if (!IPAddress.TryParse(serverIp, out parsedAddress) ||
                IPAddress.Any.Equals(parsedAddress) ||
                IPAddress.IPv6Any.Equals(parsedAddress))
            {
                parsedAddress = IPAddress.Loopback;
            }
            _bindAddress = parsedAddress;
            _port = port;
        }

        public void Start()
        {
            if (_running) return;
            _listener = new TcpListener(_bindAddress, _port);
            _listener.Start();
            _running = true;

            // 订阅BLE通知, 转发给所有TCP客户端
            _bleCore.ReceiveNotifyData += OnBleNotify;
            _bleCore.ConnectDeviceSuccess += PauseStatusQueryPipeline;
            _bleCore.DeviceDisconnected += PauseStatusQueryPipeline;
            _bleCore.NotificationsEnabled += ResumeStatusQueryPipeline;

            Log($"TCP服务器已启动, 监听端口: {_port}");
            Task.Run(() => AcceptLoop());
        }

        public void Stop()
        {
            if (!_running) return;
            _running = false;
            _bleCore.ReceiveNotifyData -= OnBleNotify;
            _bleCore.ConnectDeviceSuccess -= PauseStatusQueryPipeline;
            _bleCore.DeviceDisconnected -= PauseStatusQueryPipeline;
            _bleCore.NotificationsEnabled -= ResumeStatusQueryPipeline;

            try { _listener?.Stop(); } catch { }

            lock (_clientLock)
            {
                foreach (var c in _clients)
                    try { c.Close(); } catch { }
                _clients.Clear();
            }
            AbortOledTransaction(null);
            PauseStatusQueryPipeline(null);
            OnClientCountChanged?.Invoke(0);
            Log("TCP服务器已停止");
        }

        /// <summary>
        /// 接受TCP客户端连接循环
        /// </summary>
        private async Task AcceptLoop()
        {
            while (_running)
            {
                try
                {
                    var client = await _listener.AcceptTcpClientAsync();
                    lock (_clientLock) _clients.Add(client);

                    string ep = GetEndpointString(client);
                    Log($"TCP客户端已连接: {ep}");
                    OnClientCountChanged?.Invoke(ClientCount);

                    var _ = Task.Run(() => ClientLoop(client));
                }
                catch (ObjectDisposedException) { break; }
                catch (SocketException) { if (!_running) break; }
                catch (Exception ex)
                {
                    if (_running) Log($"接受连接异常: {ex.Message}");
                }
            }
        }

        /// <summary>
        /// 单个TCP客户端读取循环
        /// </summary>
        private async Task ClientLoop(TcpClient client)
        {
            try
            {
                var stream = client.GetStream();
                byte[] header = new byte[3];

                while (_running && client.Connected)
                {
                    // 读包头: [Type:1][Length:2]
                    int n = await ReadExact(stream, header, 3);
                    if (n < 3) break;

                    PacketType type = (PacketType)header[0];
                    int dataLen = header[1] | (header[2] << 8);

                    // 读包体
                    byte[] data = null;
                    if (dataLen > 0)
                    {
                        if (dataLen > 65535) break; // 防止异常大包
                        data = new byte[dataLen];
                        n = await ReadExact(stream, data, dataLen);
                        if (n < dataLen) break;
                    }

                    await HandlePacketAsync(client, type, data).ConfigureAwait(false);
                }
            }
            catch (IOException ex)
            {
                if (_running) Log("客户端/BLE I/O 异常: " + ex.Message);
            }
            catch (SocketException) { }
            catch (ObjectDisposedException) { }
            catch (Exception ex) { Log($"客户端处理异常: {ex.Message}"); }
            finally
            {
                RemoveClient(client);
            }
        }

        /// <summary>
        /// 处理收到的TCP包, 路由到BLE或返回查询结果
        /// </summary>
        private async Task HandlePacketAsync(TcpClient client, PacketType type, byte[] data)
        {
            switch (type)
            {
                case PacketType.WriteData:
                    await WriteBleDataPacketAsync(client, data).ConfigureAwait(false);
                    Log($"→BLE数据(0x7341) [{data?.Length ?? 0}字节]");
                    break;

                case PacketType.WriteCommand:
                    // 记录状态信息
                    if (ProtocolHelper.IsClaudeStatusUpload(data))
                    {
                        ProtocolHelper.LastClaudeState = data;
                    }
                    if (data != null && data.SequenceEqual(ProtocolHelper.DeviceStatusQueryCommand))
                    {
                        EnqueueStatusQuery(null, null);
                        Log("排队BLE后台状态查询(0x00)");
                    }
                    else
                    {
                        await WriteBleCommandPacketAsync(client, data).ConfigureAwait(false);
                        Log($"→BLE命令(0x7343) [{data?.Length ?? 0}字节]");
                    }
                    break;

                case PacketType.QueryBleStatus:
                    var status = BuildBleStatus();
                    SendToClient(client, ProtocolHelper.BuildBleStatusPacket(status));
                    Log("响应BLE状态查询");
                    break;

                case PacketType.QueryDeviceInfo:
                    DeviceStatusInfo info;
                    lock (_statusLock) info = _deviceStatus;
                    SendToClient(client, ProtocolHelper.BuildDeviceInfoPacket(info));
                    Log("响应设备信息查询");
                    break;

                case PacketType.QueryLiveDeviceStatus:
                    if (data != null && data.Length == 8 && IsStatusPipelineReady())
                    {
                        EnqueueStatusQuery(client, data.ToArray());
                        Log("排队BLE实时状态查询(0x00)");
                    }
                    else
                    {
                        Log("BLE命令特征(0x7343)未就绪，实时状态查询不响应");
                    }
                    break;

                default:
                    Log($"未知包类型: 0x{(byte)type:X2}");
                    break;
            }
        }

        /// <summary>
        /// 从BleCore获取当前BLE连接状态
        /// </summary>
        private BleStatusInfo BuildBleStatus()
        {
            var dev = _bleCore.CurrentDevice;
            bool connected = dev != null &&
                dev.ConnectionStatus == BluetoothConnectionStatus.Connected;

            string name = "";
            string mac = "";
            if (dev != null)
            {
                name = dev.Name ?? "";
                byte[] macBytes = BitConverter.GetBytes(dev.BluetoothAddress);
                Array.Reverse(macBytes);
                mac = BitConverter.ToString(macBytes, 2, 6).Replace('-', ':');
            }

            bool isTarget = _bleCore.CurrentDataCharacteristic != null
                         && _bleCore.CurrentWriteCharacteristic != null
                         && _bleCore.CurrentNotifyCharacteristic != null
                         && _bleCore.IsNotificationReady(_bleCore.CurrentNotifyCharacteristic);

            return new BleStatusInfo
            {
                Connected = connected,
                DeviceName = name,
                MacAddress = mac,
                IsTargetDevice = isTarget
            };
        }

        public void RequestDeviceStatusRefresh()
        {
            EnqueueStatusQuery(null, null);
        }

        private void EnqueueStatusQuery(TcpClient client, byte[] requestId)
        {
            lock (_statusLock)
            {
                if (_statusPipelineFaulted || !_running || !IsStatusPipelineReady())
                    return;

                // Periodic/background refreshes are coalesced; permission requests are not.
                if (requestId == null &&
                    ((_activeStatusQuery != null && _activeStatusQuery.RequestId == null) ||
                     _statusQueryQueue.Any(item => item.RequestId == null)))
                {
                    return;
                }

                _statusQueryQueue.Enqueue(new StatusQueryRequest
                {
                    Client = client,
                    RequestId = requestId
                });
            }

            TryDispatchNextStatusQuery();
        }

        private void TryDispatchNextStatusQuery()
        {
            StatusQueryRequest request;
            lock (_statusLock)
            {
                if (!_running || _statusPipelineFaulted || _activeStatusQuery != null ||
                    _statusQueryQueue.Count == 0)
                {
                    return;
                }

                request = _statusQueryQueue.Dequeue();
                request.PipelineEpoch = _statusPipelineEpoch;
                request.ConnectionEpoch = _bleCore.ConnectionEpoch;
                request.Identity = ++_nextStatusQueryIdentity;
                request.WriteCharacteristic = _bleCore.CurrentWriteCharacteristic;
                request.NotifyCharacteristic = _bleCore.CurrentNotifyCharacteristic;
                _activeStatusQuery = request;
                _activeNotifyCharacteristic = request.NotifyCharacteristic;
            }

            _ = DispatchStatusQueryAsync(request);
        }

        private async Task DispatchStatusQueryAsync(StatusQueryRequest request)
        {
            string failureReason = null;
            bool connectionChanged = false;
            bool writeDispatched = false;

            await _blePacketGate.WaitAsync().ConfigureAwait(false);
            try
            {
                lock (_statusLock)
                {
                    if (!IsActiveStatusQueryLocked(request))
                        return;

                    if (request.ConnectionEpoch != _bleCore.ConnectionEpoch)
                    {
                        connectionChanged = true;
                    }
                    else if (request.WriteCharacteristic == null || request.NotifyCharacteristic == null ||
                        !ReferenceEquals(request.WriteCharacteristic, _bleCore.CurrentWriteCharacteristic) ||
                        !ReferenceEquals(request.NotifyCharacteristic, _bleCore.CurrentNotifyCharacteristic) ||
                        !_bleCore.IsNotificationReady(request.NotifyCharacteristic))
                    {
                        failureReason = "BLE状态查询特征已变更";
                    }
                    else
                    {
                        // Mark the exact active request before the write. A very fast notification
                        // can then be attributed correctly while WriteWithResponse is completing.
                        request.Dispatched = true;
                        writeDispatched = true;
                    }
                }

                if (writeDispatched)
                    await _bleCore.WriteDataToCharacteristicAsync(
                        request.WriteCharacteristic,
                        ProtocolHelper.DeviceStatusQueryCommand).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                failureReason = "BLE状态查询发送失败: " + ex.Message;
            }
            finally
            {
                _blePacketGate.Release();
            }

            if (connectionChanged)
            {
                PauseStatusQueryPipeline(null);
                return;
            }

            if (failureReason != null)
            {
                FailStatusQueryPipeline(request, failureReason);
                return;
            }

            lock (_statusLock)
            {
                // The response may have arrived before WriteWithResponse completed.
                if (!IsActiveStatusQueryLocked(request))
                    return;
            }

            Log(request.RequestId == null ? "→BLE后台状态查询(0x00)" : "→BLE实时状态查询(0x00)");
            ArmStatusQueryTimeout(request);
        }

        private void ArmStatusQueryTimeout(StatusQueryRequest request)
        {
            Task.Run(async () =>
            {
                await Task.Delay(StatusQueryTimeoutMs).ConfigureAwait(false);
                FailStatusQueryPipeline(
                    request,
                    $"BLE状态查询超时({StatusQueryTimeoutMs}ms, id={request.Identity})");
            });
        }

        private bool IsActiveStatusQueryLocked(StatusQueryRequest request)
        {
            return request != null &&
                   ReferenceEquals(_activeStatusQuery, request) &&
                   _activeStatusQuery.Identity == request.Identity &&
                   _activeStatusQuery.PipelineEpoch == request.PipelineEpoch &&
                   _statusPipelineEpoch == request.PipelineEpoch;
        }

        private void FailStatusQueryPipeline(StatusQueryRequest request, string reason)
        {
            bool requestRecovery = false;
            lock (_statusLock)
            {
                if (!IsActiveStatusQueryLocked(request) || _statusPipelineFaulted)
                    return;

                // Fail closed. Do not dispatch the next request: without a device-side
                // nonce a late response could otherwise be assigned to that request.
                _statusPipelineFaulted = true;
                _statusPipelineEpoch++;
                _activeStatusQuery = null;
                _activeNotifyCharacteristic = null;
                _statusQueryQueue.Clear();
                requestRecovery = _running;
            }

            if (!requestRecovery)
                return;

            Log(reason + "，已停止状态管线并请求重连");
            try
            {
                OnBleRecoveryRequired?.Invoke(reason);
            }
            catch (Exception ex)
            {
                Log("触发BLE重连失败: " + ex.Message);
            }
        }

        private async Task WriteBleDataPacketAsync(TcpClient client, byte[] data)
        {
            if (data == null || data.Length == 0)
                throw new InvalidDataException("BLE data packet is empty");

            bool ownsPreparedTransaction = TryConsumeOledTransaction(client);
            if (!ownsPreparedTransaction)
                await _blePacketGate.WaitAsync().ConfigureAwait(false);

            try
            {
                var characteristic = _bleCore.CurrentDataCharacteristic;
                if (characteristic == null)
                    throw new IOException("BLE data characteristic (0x7341) is not ready");

                await WriteBleFragmentsAsync(
                    characteristic,
                    data,
                    DataFragmentSize,
                    DataFragmentDelayMs).ConfigureAwait(false);
            }
            finally
            {
                // A prepared OLED transaction already owns this semaphore. Consuming its DATA
                // packet completes that transaction; a standalone DATA packet owns it normally.
                _blePacketGate.Release();
            }
        }

        private async Task WriteBleCommandPacketAsync(TcpClient client, byte[] data)
        {
            if (data == null || data.Length == 0)
                throw new InvalidDataException("BLE command packet is empty");

            // PREPARE_WRITE must be followed by DATA on the same TCP connection. Anything else
            // would deadlock that connection while it still owns the upload transaction gate.
            if (IsOledTransactionOwner(client))
            {
                AbortOledTransaction(client);
                throw new InvalidDataException("Expected OLED DATA packet after PREPARE_WRITE");
            }

            bool keepGateForOledData = false;
            await _blePacketGate.WaitAsync().ConfigureAwait(false);
            try
            {
                var characteristic = _bleCore.CurrentWriteCharacteristic;
                if (characteristic == null)
                    throw new IOException("BLE command characteristic (0x7343) is not ready");

                await WriteBleFragmentsAsync(
                    characteristic,
                    data,
                    CommandFragmentSize,
                    0).ConfigureAwait(false);

                if (IsPrepareWriteCommand(data))
                {
                    lock (_oledTransactionLock)
                    {
                        if (!_running)
                            throw new IOException("TCP server stopped during OLED PREPARE_WRITE");
                        if (_oledTransactionOwner != null)
                            throw new InvalidOperationException("Another OLED write transaction is active");
                        _oledTransactionOwner = client;
                        keepGateForOledData = true;
                    }
                }
            }
            finally
            {
                if (!keepGateForOledData)
                    _blePacketGate.Release();
            }
        }

        private async Task WriteBleFragmentsAsync(
            GattCharacteristic characteristic,
            byte[] data,
            int fragmentSize,
            int interFragmentDelayMs)
        {
            for (int offset = 0; offset < data.Length; offset += fragmentSize)
            {
                int length = Math.Min(fragmentSize, data.Length - offset);
                var fragment = new byte[length];
                Buffer.BlockCopy(data, offset, fragment, 0, length);
                await _bleCore.WriteDataToCharacteristicAsync(characteristic, fragment).ConfigureAwait(false);

                if (interFragmentDelayMs > 0 && offset + length < data.Length)
                    await Task.Delay(interFragmentDelayMs).ConfigureAwait(false);
            }
        }

        private static bool IsPrepareWriteCommand(byte[] data)
        {
            return data != null && data.Length == 12 &&
                   data[0] == 0xAA && data[1] == 0xBB && data[2] == 0x80 &&
                   data[data.Length - 2] == 0xCC && data[data.Length - 1] == 0xDD;
        }

        private bool IsOledTransactionOwner(TcpClient client)
        {
            lock (_oledTransactionLock)
                return client != null && ReferenceEquals(_oledTransactionOwner, client);
        }

        private bool TryConsumeOledTransaction(TcpClient client)
        {
            lock (_oledTransactionLock)
            {
                if (client == null || !ReferenceEquals(_oledTransactionOwner, client))
                    return false;
                _oledTransactionOwner = null;
                return true;
            }
        }

        private void AbortOledTransaction(TcpClient client)
        {
            bool releaseGate = false;
            lock (_oledTransactionLock)
            {
                if (_oledTransactionOwner != null &&
                    (client == null || ReferenceEquals(_oledTransactionOwner, client)))
                {
                    _oledTransactionOwner = null;
                    releaseGate = true;
                }
            }

            if (releaseGate)
                _blePacketGate.Release();
        }

        private void PauseStatusQueryPipeline(BluetoothLEDevice device)
        {
            lock (_statusLock)
            {
                if (device != null && _bleCore.CurrentDevice != null &&
                    !ReferenceEquals(device, _bleCore.CurrentDevice))
                {
                    return;
                }

                _statusPipelineEpoch++;
                _activeStatusQuery = null;
                _activeNotifyCharacteristic = null;
                _statusQueryQueue.Clear();
                _statusPipelineFaulted = true;
            }
        }

        private void ResumeStatusQueryPipeline(GattCharacteristic characteristic)
        {
            lock (_statusLock)
            {
                if (!_running || characteristic == null ||
                    !ReferenceEquals(characteristic, _bleCore.CurrentNotifyCharacteristic) ||
                    !_bleCore.IsNotificationReady(characteristic))
                {
                    return;
                }

                _statusPipelineEpoch++;
                _activeStatusQuery = null;
                _activeNotifyCharacteristic = null;
                _statusQueryQueue.Clear();
                _statusPipelineFaulted = false;
            }
        }

        private bool IsStatusPipelineReady()
        {
            var notifyCharacteristic = _bleCore.CurrentNotifyCharacteristic;
            return _bleCore.CurrentWriteCharacteristic != null &&
                   notifyCharacteristic != null &&
                   _bleCore.IsNotificationReady(notifyCharacteristic);
        }

        /// <summary>
        /// BLE通知回调 → 过滤设备状态通知, 其余广播给所有TCP客户端
        /// </summary>
        private void OnBleNotify(GattCharacteristic sender, byte[] data)
        {
            // 设备状态通知: 更新缓存，并只对等待本次实时结果的客户端应答。
            if (ProtocolHelper.IsDeviceStatusNotification(data))
            {
                var newStatus = ProtocolHelper.ParseDeviceStatusFromNotification(data);
                StatusQueryRequest completed = null;
                bool staleCharacteristic;
                lock (_statusLock)
                {
                    var currentNotifyCharacteristic = _bleCore.CurrentNotifyCharacteristic;
                    staleCharacteristic = currentNotifyCharacteristic == null ||
                        !ReferenceEquals(sender, currentNotifyCharacteristic);
                    if (!staleCharacteristic)
                    {
                        _deviceStatus = newStatus;
                        var active = _activeStatusQuery;
                        if (active != null && active.Dispatched &&
                            IsActiveStatusQueryLocked(active) &&
                            active.ConnectionEpoch == _bleCore.ConnectionEpoch &&
                            ReferenceEquals(sender, active.NotifyCharacteristic) &&
                            ReferenceEquals(sender, _activeNotifyCharacteristic))
                        {
                            completed = active;
                            _activeStatusQuery = null;
                            _activeNotifyCharacteristic = null;
                        }
                    }
                }

                if (staleCharacteristic)
                {
                    Log("忽略旧BLE通知特征的状态包");
                    return;
                }

                Log($"设备状态更新: 电量={newStatus.BatteryLevel} 信号={newStatus.SignalStrength} " +
                    $"固件={newStatus.FirmwareVersionMain}.{newStatus.FirmwareVersionSub} " +
                    $"工作模式={newStatus.WorkMode} 灯光={newStatus.LightMode} 开关={newStatus.SwitchState}");
                if (completed != null && completed.Client != null && completed.RequestId != null)
                    SendToClient(completed.Client,
                        ProtocolHelper.BuildLiveDeviceStatusPacket(newStatus, completed.RequestId));
                if (completed != null)
                    TryDispatchNextStatusQuery();
                return;
            }

            byte[] packet = ProtocolHelper.BuildPacket(PacketType.BleNotify, data);
            BroadcastToAll(packet);
        }

        /// <summary>
        /// 向所有已连接的TCP客户端广播数据
        /// </summary>
        public void BroadcastToAll(byte[] packet)
        {
            List<TcpClient> snapshot;
            lock (_clientLock) snapshot = new List<TcpClient>(_clients);
            foreach (var c in snapshot)
                SendToClient(c, packet);
        }

        private void SendToClient(TcpClient client, byte[] packet)
        {
            try
            {
                if (client.Connected)
                {
                    lock (client)
                    {
                        var stream = client.GetStream();
                        stream.Write(packet, 0, packet.Length);
                    }
                }
            }
            catch { RemoveClient(client); }
        }

        private void RemoveClient(TcpClient client)
        {
            AbortOledTransaction(client);
            lock (_statusLock)
            {
                if (_activeStatusQuery != null && _activeStatusQuery.Client == client)
                    _activeStatusQuery.Client = null;
                var retained = _statusQueryQueue.Where(item => item.Client != client).ToArray();
                _statusQueryQueue.Clear();
                foreach (var item in retained)
                    _statusQueryQueue.Enqueue(item);
            }
            bool removed;
            lock (_clientLock) removed = _clients.Remove(client);
            if (removed)
            {
                string ep = GetEndpointString(client);
                Log($"TCP客户端已断开: {ep}");
                try { client.Close(); } catch { }
                OnClientCountChanged?.Invoke(ClientCount);
            }
        }

        /// <summary>
        /// 从NetworkStream精确读取count字节
        /// </summary>
        private static async Task<int> ReadExact(NetworkStream stream, byte[] buf, int count)
        {
            int total = 0;
            while (total < count)
            {
                int n = await stream.ReadAsync(buf, total, count - total);
                if (n == 0) return total; // 连接关闭
                total += n;
            }
            return total;
        }

        private static string GetEndpointString(TcpClient client)
        {
            try { return client.Client.RemoteEndPoint?.ToString() ?? "unknown"; }
            catch { return "unknown"; }
        }

        private void Log(string msg) => OnLog?.Invoke(msg);

        /// <summary>
        /// 获取本机局域网IP地址
        /// </summary>
        public static string GetLocalIPAddress()
        {
            try
            {
                var host = Dns.GetHostEntry(Dns.GetHostName());
                var ip = host.AddressList.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork);
                return ip?.ToString() ?? "127.0.0.1";
            }
            catch { return "127.0.0.1"; }
        }
    }
}
