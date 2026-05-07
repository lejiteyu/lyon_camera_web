package com.example.webcam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class NetworkManager(private val context: Context) {
    private val TAG = "NetworkManager"
    private val SERVICE_TYPE = "_webcam._tcp."
    private val SERVICE_NAME = "LyonWebCam"
    private val MAGIC_NUMBER = 0x4C594F4E // "LYON"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false
    private val sendMutex = Mutex()

    // Server Side: Register Service
    fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        })
    }

    // Client Side: Discover Service
    fun discoverService(onServiceFound: (String, Int) -> Unit) {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName.contains(SERVICE_NAME)) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            onServiceFound(serviceInfo.host.hostAddress!!, serviceInfo.port)
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        })
    }

    // Server Side: Start listening for frames
    suspend fun startServer(onFrameReceived: (String, ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(0) // Let system pick port
        val port = serverSocket!!.localPort
        registerService(port)
        isRunning = true

        while (isRunning) {
            try {
                val socket = serverSocket?.accept() ?: break
                val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
                launch {
                    handleClientConnection(socket, clientIp, onFrameReceived)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket, clientIp: String, onFrameReceived: (String, ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val inputStream = DataInputStream(socket.getInputStream())
        while (isRunning && !socket.isClosed) {
            try {
                // Check for Magic Number to ensure sync
                if (inputStream.readInt() != MAGIC_NUMBER) {
                    Log.e(TAG, "Stream desync from $clientIp. Closing.")
                    break
                }
                
                val size = inputStream.readInt()
                if (size < 0 || size > 5 * 1024 * 1024) { // Limit to 5MB per frame
                    Log.e(TAG, "Invalid frame size: $size. Closing.")
                    break
                }
                
                val buffer = ByteArray(size)
                inputStream.readFully(buffer)
                onFrameReceived(clientIp, buffer)
            } catch (e: Exception) {
                Log.e(TAG, "Connection error from $clientIp: ${e.message}")
                break
            }
        }
        socket.close()
    }

    // Client Side: Connect and send frames
    private var outputStream: DataOutputStream? = null

    suspend fun connectToServer(host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            clientSocket = Socket(host, port)
            outputStream = DataOutputStream(clientSocket!!.getOutputStream())
            Log.d(TAG, "Connected to server")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
        }
    }

    suspend fun sendFrame(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            sendMutex.withLock {
                outputStream?.apply {
                    writeInt(MAGIC_NUMBER) // Send header
                    writeInt(data.size)
                    write(data)
                    flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        clientSocket?.close()
    }
}
