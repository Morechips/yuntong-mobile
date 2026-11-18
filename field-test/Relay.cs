using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

public sealed class FieldRelay : IDisposable
{
    private readonly TcpListener listener;
    private readonly string host;
    private readonly int port;
    private readonly HashSet<TcpClient> clients = new HashSet<TcpClient>();
    private bool stopped;

    public FieldRelay(string host, int port)
    {
        this.host = host;
        this.port = port;
        listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(4);
        Port = ((IPEndPoint)listener.LocalEndpoint).Port;
        Accept();
    }

    public int Port { get; private set; }

    private async void Accept()
    {
        try
        {
            while (true)
            {
                TcpClient local = await listener.AcceptTcpClientAsync().ConfigureAwait(false);
                TcpClient remote = new TcpClient();
                lock (clients)
                {
                    if (stopped || clients.Count >= 8)
                    {
                        local.Close();
                        remote.Close();
                        if (stopped) return;
                        continue;
                    }
                    clients.Add(local);
                    clients.Add(remote);
                }
                Bridge(local, remote);
            }
        }
        catch (SocketException) { }
        catch (ObjectDisposedException) { }
    }

    private async void Bridge(TcpClient local, TcpClient remote)
    {
        try
        {
            Task connection = remote.ConnectAsync(host, port);
            if (await Task.WhenAny(connection, Task.Delay(4000)).ConfigureAwait(false) != connection)
                return;
            await connection.ConfigureAwait(false);
            NetworkStream input = local.GetStream();
            NetworkStream output = remote.GetStream();
            await Task.WhenAny(input.CopyToAsync(output), output.CopyToAsync(input)).ConfigureAwait(false);
        }
        catch (SocketException) { }
        catch (InvalidOperationException) { }
        catch (System.IO.IOException) { }
        catch (ArgumentException) { }
        finally
        {
            local.Close();
            remote.Close();
            lock (clients)
            {
                clients.Remove(local);
                clients.Remove(remote);
            }
        }
    }

    public void Dispose()
    {
        lock (clients)
        {
            if (stopped) return;
            stopped = true;
            listener.Stop();
            foreach (TcpClient client in clients) client.Close();
            clients.Clear();
        }
    }
}
