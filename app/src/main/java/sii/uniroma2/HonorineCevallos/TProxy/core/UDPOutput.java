package sii.uniroma2.HonorineCevallos.TProxy.core;

/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
import android.util.Log;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import sii.uniroma2.HonorineCevallos.TProxy.utils.GlobalAppState;
import sii.uniroma2.HonorineCevallos.TProxy.utils.ByteBufferPool;
import sii.uniroma2.HonorineCevallos.TProxy.utils.ConnectivityHelper;
import sii.uniroma2.HonorineCevallos.TProxy.utils.LRUCache;
import sii.uniroma2.HonorineCevallos.TProxy.logManaging.Packet;
import sii.uniroma2.HonorineCevallos.TProxy.exceptions.AddressHelperException;
import sii.uniroma2.HonorineCevallos.TProxy.logManaging.LogManager;

public class UDPOutput implements Runnable
{
    private static final String TAG = UDPOutput.class.getSimpleName();

    private LocalProxyServer vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private LogManager logManager;
    private Selector selector;
    private static final int MAX_CACHE_SIZE = 50;
    private LRUCache<String, DatagramChannel> channelCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, DatagramChannel>()
            {
                @Override
                public void cleanup(Map.Entry<String, DatagramChannel> eldest)
                {
                    closeChannel(eldest.getValue());
                }
            });

    public UDPOutput(ConcurrentLinkedQueue<Packet> inputQueue, Selector selector, LocalProxyServer vpnService, LogManager _logManager)
    {
        this.inputQueue = inputQueue;
        this.selector = selector;
        this.vpnService = vpnService;
        this.logManager = _logManager;

    }

    @Override
    public void run()
    {
        Log.i(TAG, "Started");
        try
        {

            Thread currentThread = Thread.currentThread();
            while (true)
            {
                Packet currentPacket;
                // TODO: Block when not connected
                do
                {
                    //I can do the poll because in TUNManager, inside the reception thread, i have done the offer().
                    currentPacket = inputQueue.poll();
                    if (currentPacket != null){
                        currentPacket.setIncomming(false);
                        break;
                    }
                    Thread.sleep(10);
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted())
                    break;

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;
                int destinationPort = currentPacket.udpHeader.destinationPort;
                int sourcePort = currentPacket.udpHeader.sourcePort;
                /*We are about to send the intercepted packet to the original destination
                * First, we have intercepted and trapped it from the vpnInterface with the TUNManager Thread.
               */
                String ipAndPort = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;
                //we try to get the correspondent channel from the channel cache.
                DatagramChannel outputChannel = channelCache.get(ipAndPort);
                //If there is no such channel, then we have to open a new one.
                if (outputChannel == null) {

                    /*We write to the log only the packets appartaining to a new UDP session*/
                    logManager.writePacketInfo(currentPacket);

                    outputChannel = DatagramChannel.open();
                    /* Workaround for bug 64819 ( https://code.google.com/p/android/issues/detail?id=64819)
                    The source address of the current channel must be the current real ip address,
                    in order to be received by the real
                    interface of the device.
                    * */
                    InetSocketAddress sa = null;
                    ConnectivityHelper ah = GlobalAppState.connectivityHelper;
                    InetAddress ia= null;
                    try {
                        ia = ah.getIPAddress();
                    } catch (AddressHelperException e) {
                        e.printStackTrace();
                    }
                    sa = new InetSocketAddress( ia , sourcePort);

                    try {
                            outputChannel.socket().setReuseAddress(true);
                            outputChannel.socket().bind(sa);
                        } catch (BindException e) {
                            Log.d(TAG, sa.toString() + " " + e.toString(), e);
                        }
                    try
                    {
                        outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                    }
                    catch (IOException e)
                    {
                        Log.e(TAG, "Connection error: " + ipAndPort, e);
                        closeChannel(outputChannel);
                        ByteBufferPool.release(currentPacket.backingBuffer);
                        continue;
                    }

                    // For simplicity, we use the same thread for both reading and
                    // writing. Here we put the tunnel into non-blocking mode.
                    outputChannel.configureBlocking(false);
                    currentPacket.swapSourceAndDestination();

                    selector.wakeup();
                    outputChannel.register(selector, SelectionKey.OP_READ, currentPacket);

                    vpnService.protect(outputChannel.socket());

                    channelCache.put(ipAndPort, outputChannel);
                }

                try
                {
                    ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                    while (payloadBuffer.hasRemaining())
                        outputChannel.write(payloadBuffer);
                }
                catch (IOException e)
                {
                    Log.e(TAG, "Network write error: " + ipAndPort, e);
                    channelCache.remove(ipAndPort);
                    closeChannel(outputChannel);
                }
                ByteBufferPool.release(currentPacket.backingBuffer);
            }
        }
        catch (InterruptedException e)
        {
            Log.i(TAG, "Stopping");
        }
        catch (IOException e)
        {
            Log.i(TAG, e.toString(), e);
        }
        finally
        {
            closeAll();
        }
    }

    private void closeAll()
    {
        Iterator<Map.Entry<String, DatagramChannel>> it = channelCache.entrySet().iterator();
        while (it.hasNext())
        {
            closeChannel(it.next().getValue());
            it.remove();
        }
    }

    private void closeChannel(DatagramChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            // Ignore
        }
    }

}
